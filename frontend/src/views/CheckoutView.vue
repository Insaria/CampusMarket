<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { clearUser, session } from '../auth.js'
import { money } from '../catalog.js'
import { useOrderRead } from '../orders.js'
import { useWorkspaceConfirm } from '../workspace.js'
import {
  cartTotals, checkoutEstimate, formatCents, groupCartLines, lineAmount, pendingCheckout, quoteMatches, readCart, rememberCheckout,
  selectedProducts, selectForCheckout, setPendingCheckout,
} from '../cart.js'

const route = useRoute()
const router = useRouter()
const confirm = useWorkspaceConfirm()
const buyerId = session.user.userId
const attempt = ref(pendingCheckout(buyerId))
const selected = attempt.value ? attempt.value.items.map(item => item.productId) : selectedProducts(buyerId)
const loading = ref(false)
const busy = ref(false)
const lines = ref([])
const missing = ref([])
const error = ref('')
const readError = ref('')
const needsRefresh = ref(false)
const pointsBySeller = ref({})
let active = true
let readRequest

const groups = computed(() => groupCartLines(lines.value))
const balanceReload = ref(0)
const { data: balances, loading: balanceLoading, error: balanceError } = useOrderRead([balanceReload],
  async (_, signal) => {
    if (attempt.value || !selected.length) return null
    const [wallet, points] = await Promise.all([
      api.get('/wallet', { params: { size: 1 }, signal }),
      api.get('/points', { params: { size: 1 }, signal }),
    ])
    return { wallet: wallet.balance, points: points.balance }
  })
const totals = computed(() => cartTotals(lines.value.filter(line => !line.issue)))
const blocked = computed(() => Boolean(missing.value.length || lines.value.some(line => line.issue)))

const estimate = computed(() => blocked.value ? null : checkoutEstimate(groups.value, pointsBySeller.value))

// Only documented business rejections establish that this request did not create a new batch.
const rejected = new Set([
  'CHECKOUT_KEY_CONFLICT', 'CART_CHANGED', 'PRODUCT_UNAVAILABLE', 'STOCK_UNAVAILABLE',
  'CHECKOUT_AMOUNT_TOO_LARGE', 'DUPLICATE_CHECKOUT_ITEM', 'INVALID_REQUEST_BODY', 'INVALID_REQUEST_PARAMETER',
  'CHECKOUT_QUOTE_CHANGED', 'INVALID_POINTS_REDEMPTION', 'INSUFFICIENT_WALLET_BALANCE',
  'INSUFFICIENT_POINTS_BALANCE', 'POINTS_DISCOUNT_EXCEEDS_LIMIT', 'ORDER_AMOUNT_NOT_SUPPORTED',
  'SELLER_UNAVAILABLE', 'PAYMENT_AMOUNT_TOO_LARGE', 'VALIDATION_FAILED',
])

onBeforeUnmount(() => {
  active = false
  readRequest?.abort()
})

function sameView() {
  return active && route.name === 'checkout' && session.user?.userId === buyerId
}

async function handleSession(failure) {
  if (failure.status !== 401 && failure.code !== 'ACCOUNT_UNAVAILABLE') return false
  if (sameView()) {
    clearUser()
    await router.replace({ name: 'login', query: { redirect: '/checkout' } })
  }
  return true
}

function useSnapshot(snapshot) {
  lines.value = snapshot.lines
  missing.value = snapshot.missing
}

async function refresh() {
  if (attempt.value || !selected.length || loading.value || busy.value) return
  loading.value = true
  error.value = ''
  readError.value = ''
  readRequest = new AbortController()
  try {
    useSnapshot(await readCart(selected, readRequest.signal))
    needsRefresh.value = false
  } catch (failure) {
    if (sameView() && !await handleSession(failure)) readError.value = failure.message
  } finally {
    loading.value = false
  }
}

function saveAttempt(payload) {
  setPendingCheckout(buyerId, payload)
  attempt.value = payload
}

async function submit() {
  if (busy.value || loading.value) return
  if (!attempt.value && (needsRefresh.value || blocked.value || !lines.value.length)) return
  busy.value = true
  error.value = ''
  try {
    if (!attempt.value) {
      readRequest = new AbortController()
      const latest = await readCart(selected, readRequest.signal)
      if (!sameView()) return
      const unchanged = quoteMatches(lines.value, latest.lines)
      useSnapshot(latest)
      if (blocked.value || !unchanged) {
        error.value = blocked.value ? '购物车中有商品发生变化，请检查后再结账。' : '商品价格或购买数量已变化，请确认更新后的报价。'
        return
      }
      const redemptions = []
      for (const [sellerId] of groups.value) {
        const raw = String(pointsBySeller.value[sellerId] ?? '').trim()
        if (!raw || raw === '0') continue
        const points = Number(raw)
        if (!/^[0-9]+$/.test(raw) || !Number.isSafeInteger(points) || points <= 0 || points % 100 !== 0) {
          error.value = '每家店铺的抵扣积分须为 100 的正整数倍。'
          return
        }
        redemptions.push({ sellerId, points })
      }
      if (!await confirm('确认下单并支付？', {
        label: '确认支付',
        detail: estimate.value
          ? '预计扣款 ' + formatCents(estimate.value.cash) + '。实际扣款以服务端回执为准。'
          : '请核对所填积分，每家店铺须保留正数现金支付。',
      }) || !sameView()) return
      saveAttempt({
        requestKey: crypto.randomUUID(),
        items: lines.value.map(({ productId, quantity }) => ({ productId, quantity })),
        quotedAmount: formatCents(totals.value.amount).slice(1),
        redemptions,
      })
    }
    // An ambiguous retry replays the exact payload even when the committed order removed these cart items.
    const { requestKey, items, quotedAmount, redemptions } = attempt.value
    const paid = quotedAmount != null
    const checkout = { requestKey, items }
    const result = paid
      ? await api.post('/cart/checkout-and-pay', { checkout, quotedAmount, redemptions })
      : await api.post('/cart/checkout', checkout)
    if (result?.batchId == null || result.quotedAmount == null || !result.orders?.length || (paid && !result.payment)) {
      throw new Error('后端未返回完整的结账回执。')
    }
    if (!sameView()) return
    saveAttempt(null)
    rememberCheckout(buyerId, result)
    selectForCheckout(buyerId, [])
    await router.replace({ name: 'checkout-result', params: { batchId: result.batchId } })
  } catch (failure) {
    if (await handleSession(failure) || !sameView()) return
    if (attempt.value && rejected.has(failure.code)) {
      saveAttempt(null)
      needsRefresh.value = true
      error.value = failure.message
    } else if (attempt.value) {
      error.value = `${failure.message} 本次结果尚未确认，请用原请求重试。`
    } else {
      error.value = failure.message
    }
  } finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'cart' }">← 返回购物车</RouterLink>
    <h1>确认结账</h1>
    <div v-if="attempt" class="seller-panel">
      <h2>{{ busy ? '正在提交结账…' : '确认上一次结账结果' }}</h2>
      <p>将使用同一请求键、商品数量和抵扣选择向服务器确认。</p>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <button class="button" type="button" :disabled="busy" @click="submit">{{ busy ? '正在提交…' : '重试原结账请求' }}</button>
    </div>
    <p v-else-if="!selected.length" class="empty-panel">请先在购物车选择要结账的商品。</p>
    <p v-else-if="loading" class="empty-panel" role="status">正在核对所选商品…</p>
    <div v-else-if="readError" class="empty-panel">
      <p role="alert">{{ readError }}</p>
      <button class="button button-outline" type="button" @click="refresh">重新核对</button>
    </div>
    <template v-else>
      <p v-if="missing.length" class="notice seller-note" role="status">部分所选商品已移出购物车，请返回购物车调整选择。</p>
      <div class="trade-split">
        <div class="seller-product-list">
          <section v-for="[sellerId, items] in groups" :key="sellerId" class="seller-panel">
            <h2>{{ items[0].shopName || '未能读取店铺信息' }}</h2>
            <ul class="checkout-lines">
              <li v-for="item in items" :key="item.productId">
                <div>
                  <strong>{{ item.name }}</strong>
                  <p v-if="item.issue" class="notice seller-note">{{ item.issue }}</p>
                  <p v-else class="muted">{{ money(item.price) }} × {{ item.quantity }}</p>
                </div>
                <span v-if="!item.issue">{{ formatCents(lineAmount(item)) }}</span>
              </li>
            </ul>
            <p v-if="items.every(item => !item.issue)" class="checkout-subtotal">店铺预估小计 {{ formatCents(cartTotals(items).amount) }}</p>
            <label v-if="!blocked">此店铺抵扣积分（选填）
              <input v-model="pointsBySeller[sellerId]" type="text" inputmode="numeric" pattern="[0-9]+" maxlength="16" :disabled="busy" placeholder="不抵扣">
            </label>
          </section>
        </div>
        <aside class="seller-panel trade-summary">
          <h2>支付摘要</h2>
          <dl class="trade-amounts" v-if="!blocked">
            <div><dt>商品报价 · {{ totals.quantity }} 件</dt><dd>{{ formatCents(totals.amount) }}</dd></div>
            <div><dt>预计积分抵扣</dt><dd>{{ estimate ? formatCents(estimate.discount) : '请核对积分' }}</dd></div>
            <div class="trade-summary-total"><dt>预计钱包实付</dt><dd><strong>{{ estimate ? formatCents(estimate.cash) : '—' }}</strong></dd></div>
          </dl>
          <p v-else>请先处理不可结账的商品。</p>
          <dl v-if="balances" class="trade-amounts trade-balances">
            <div><dt><RouterLink :to="{ name: 'wallet' }">钱包余额</RouterLink></dt><dd>{{ balances.wallet == null ? '尚未开立' : money(balances.wallet) }}</dd></div>
            <div><dt><RouterLink :to="{ name: 'points' }">可用积分</RouterLink></dt><dd>{{ balances.points ?? '尚未开立' }}</dd></div>
          </dl>
          <p v-else-if="balanceLoading" class="muted" role="status">正在查询钱包和积分…</p>
          <p v-else-if="balanceError" class="muted">暂时无法读取余额。 <button class="text-button" type="button" @click="balanceReload++">重新查询</button></p>
          <p class="muted">每 100 积分抵 1 元，每家店铺须支付正数现金。以服务端回执为准。</p>
          <p v-if="error" class="notice error" role="alert">{{ error }}</p>
          <button v-if="needsRefresh || blocked" class="button button-outline" type="button" :disabled="busy" @click="refresh">重新核对</button>
          <button v-else class="button" type="button" :disabled="busy || !lines.length" @click="submit">
            {{ busy ? '正在核对…' : '下单并支付' }}
          </button>
        </aside>
      </div>
    </template>
  </section>
</template>
