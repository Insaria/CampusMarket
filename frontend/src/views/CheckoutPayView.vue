<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { adminMoney } from '../admin.js'
import { clearUser, session } from '../auth.js'
import { positiveId } from '../catalog.js'
import { notSignedIn } from '../finance.js'
import { useWorkspaceConfirm } from '../workspace.js'
import BatchOrders from '../components/BatchOrders.vue'

const route = useRoute()
const confirm = useWorkspaceConfirm()
const router = useRouter()
const id = computed(() => positiveId(route.params.batchId))
const batch = ref(null)
const wallet = ref(null)
const points = ref(null)
const choices = ref({})
const receipt = ref(null)
const attempt = ref(null)
const loading = ref(true)
const busy = ref(false)
const loadError = ref('')
const balanceError = ref('')
const error = ref('')
const reload = ref(0)
let readController

const paid = computed(() => receipt.value ?? batch.value?.payment)
const payable = computed(() => !paid.value && batch.value?.orders.length
  && batch.value.orders.every(order => order.orderStatus === 'PENDING_PAYMENT'))
const cancelled = computed(() => batch.value?.orders.some(order => order.orderStatus === 'CANCELLED'))
const businessErrors = new Set([
  'INSUFFICIENT_WALLET_BALANCE', 'INSUFFICIENT_POINTS_BALANCE', 'POINTS_DISCOUNT_EXCEEDS_LIMIT',
  'INVALID_POINTS_REDEMPTION', 'ORDER_AMOUNT_NOT_SUPPORTED', 'SELLER_UNAVAILABLE', 'PAYMENT_AMOUNT_TOO_LARGE',
  'BATCH_NOT_PAYABLE', 'CHECKOUT_BATCH_NOT_FOUND', 'PAYMENT_FORBIDDEN', 'VALIDATION_FAILED',
])

async function loginAgain() {
  const redirect = route.fullPath
  clearUser()
  await router.replace({ name: 'login', query: { redirect } })
}

async function readBatch(batchId, signal) {
  const latest = await api.get(`/checkout-batches/${batchId}`, { signal })
  if (!signal.aborted) batch.value = latest
}

async function readBalances(signal) {
  const [walletResult, pointsResult] = await Promise.allSettled([
    api.get('/wallet', { params: { size: 1 }, signal }),
    api.get('/points', { params: { size: 1 }, signal }),
  ])
  if (signal.aborted) return
  if (walletResult.status === 'fulfilled') wallet.value = walletResult.value
  if (pointsResult.status === 'fulfilled') points.value = pointsResult.value
  const failures = [walletResult, pointsResult].filter(result => result.status === 'rejected').map(result => result.reason)
  const sessionError = failures.find(notSignedIn)
  if (sessionError) throw sessionError
  balanceError.value = failures.length ? '暂时无法读取钱包或积分余额。' : ''
}

watch([id, reload], async ([batchId], [previousId], onCleanup) => {
  const controller = new AbortController()
  readController = controller
  const { signal } = controller
  onCleanup(() => controller.abort())
  if (batchId !== previousId) {
    batch.value = null
    receipt.value = null
    attempt.value = null
    choices.value = {}
    error.value = ''
    busy.value = false
  }
  wallet.value = null
  points.value = null
  loadError.value = ''
  balanceError.value = ''
  loading.value = true
  try {
    if (!batchId) {
      loadError.value = '结账批次不存在。'
      return
    }
    await Promise.all([readBatch(batchId, signal), readBalances(signal)])
  } catch (failure) {
    if (!signal.aborted && notSignedIn(failure)) await loginAgain()
    else if (!signal.aborted) loadError.value = failure.message
  } finally {
    if (!signal.aborted) loading.value = false
  }
}, { immediate: true })

function prepare() {
  const redemptions = []
  for (const order of batch.value.orders) {
    const input = String(choices.value[order.orderId] ?? '').trim()
    if (!input || input === '0') continue
    if (!/^[1-9][0-9]{0,14}$/.test(input) || BigInt(input) % 100n !== 0n) {
      error.value = '每笔订单的抵扣积分须为 100 的正整数倍，不抵扣可留空。'
      return null
    }
    redemptions.push({ orderId: order.orderId, points: Number(input) })
  }
  return { body: redemptions.length ? { redemptions } : undefined }
}

async function pay() {
  if (busy.value || loading.value || !payable.value || (!attempt.value && (loadError.value || balanceError.value))) return
  error.value = ''
  const payload = attempt.value ?? prepare()
  if (!payload) return
  const batchId = id.value
  const userId = session.user?.userId
  const signal = readController.signal
  const active = () => !signal.aborted && id.value === batchId && session.user?.userId === userId
  busy.value = true
  try {
    if (!attempt.value && !await confirm('确认钱包支付？', {
      label: '确认支付', detail: '将按服务端校验后的金额扣除钱包余额和所选积分。',
    })) return
    if (!active()) return
    attempt.value = payload
    const result = await api.post(`/checkout-batches/${batchId}/pay`, payload.body)
    if (String(result?.batchId) !== batchId || result?.paidAmount == null || result.pointsUsed == null
        || result.pointsDiscountAmount == null || !result.orderIds?.length) {
      throw new Error('未收到完整的支付回执。')
    }
    if (!active()) return
    receipt.value = result
    attempt.value = null
  } catch (failure) {
    if (!active()) return
    if (notSignedIn(failure)) {
      await loginAgain()
      return
    }
    if (businessErrors.has(failure.code)) {
      attempt.value = null
      error.value = failure.message
    } else {
      error.value = '支付结果尚未确认。可以重新查询，或使用相同抵扣明细重试。'
      return
    }
  } finally {
    if (active()) busy.value = false
  }
  if (!active()) return
  // A POST response is a receipt. Read current order status separately because an idempotent reply may refer to an earlier payment.
  loading.value = true
  try {
    await Promise.all([readBatch(batchId, signal), readBalances(signal)])
  } catch (failure) {
    if (active() && notSignedIn(failure)) await loginAgain()
    else if (active()) loadError.value = '暂时无法刷新批次或余额。'
  } finally {
    if (active()) loading.value = false
  }
}
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'pending-payments' }">← 待支付订单</RouterLink>
    <h1>钱包支付</h1>
    <p v-if="loading && !batch" class="empty-panel" role="status">正在读取结账批次…</p>
    <div v-if="loadError || balanceError" class="notice error" role="alert">
      {{ loadError || balanceError }}
      <button class="text-button" type="button" :disabled="busy || loading" @click="reload++">重新查询</button>
    </div>
    <template v-if="batch">
      <div class="seller-panel">
        <dl class="seller-metrics trade-metrics">
          <div><dt>结账批次</dt><dd>#{{ batch.batchId }}</dd></div>
          <div><dt>订单总额</dt><dd>{{ adminMoney(batch.quotedAmount) }}</dd></div>
          <div><dt>钱包余额</dt><dd>{{ wallet?.balance != null ? adminMoney(wallet.balance) : wallet ? '尚未开立' : '暂不可用' }}</dd></div>
          <div><dt>可用积分</dt><dd>{{ points?.balance != null ? points.balance : points ? '尚未开立' : '暂不可用' }}</dd></div>
        </dl>
      </div>
      <BatchOrders :orders="batch.orders" />
      <section v-if="paid" class="seller-panel admin-top-gap" aria-labelledby="payment-receipt">
        <h2 id="payment-receipt">支付回执</h2>
        <p class="notice success" role="status">服务器已确认该批次支付成功。</p>
        <dl class="seller-metrics trade-metrics">
          <div><dt>实际现金支付</dt><dd class="product-price">{{ adminMoney(paid.paidAmount) }}</dd></div>
          <div><dt>实际使用积分</dt><dd>{{ paid.pointsUsed }}</dd></div>
          <div><dt>实际抵扣金额</dt><dd>{{ adminMoney(paid.pointsDiscountAmount) }}</dd></div>
        </dl>
        <div class="actions">
          <RouterLink class="button" :to="{ name: 'orders' }">查看我的订单</RouterLink>
          <RouterLink class="button button-outline" :to="{ name: 'points' }">查看积分流水</RouterLink>
        </div>
      </section>
      <form v-else-if="payable" class="seller-panel admin-top-gap payment-form" @submit.prevent="pay">
        <h2>选择积分抵扣</h2>
        <p class="muted">每 100 积分抵扣 1 元，由卖家承担。每笔订单必须保留正数现金支付。不使用积分可留空。</p>
        <div class="payment-inputs">
          <label v-for="order in batch.orders" :key="order.orderId">
            {{ order.orderNo }} 的抵扣积分（选填）
            <input v-model="choices[order.orderId]" inputmode="numeric" maxlength="15" placeholder="不抵扣"
              :disabled="busy || loading || !!attempt">
          </label>
        </div>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <p v-if="wallet && wallet.balance == null && !attempt" class="notice seller-note">当前没有可用钱包账户。</p>
        <div class="actions">
          <button class="button" type="submit" :disabled="busy || loading || (!attempt && (!wallet || wallet.balance == null || !!loadError || !!balanceError))">
            {{ busy ? '正在支付…' : attempt ? '重试原支付' : '确认钱包支付' }}
          </button>
          <button v-if="attempt" class="button button-outline" type="button" :disabled="busy || loading" @click="reload++">查询支付状态</button>
        </div>
      </form>
      <p v-else class="notice seller-note admin-top-gap">{{ cancelled ? '该批次包含已取消订单，无法支付。' : '该批次当前不可支付。' }}</p>
    </template>
  </section>
</template>
