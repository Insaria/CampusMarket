<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'
import { clearUser, session } from '../auth.js'
import { cartTotals, formatCents, groupCartLines, pendingCheckout, readCart, selectedProducts, selectForCheckout, setCartQuantity } from '../cart.js'
import CartLine from '../components/CartLine.vue'

const router = useRouter()
const buyerId = session.user.userId
const rows = ref([])
const initialSelection = selectedProducts(buyerId)
const selected = ref(initialSelection.length ? initialSelection : null)
const loading = ref(true)
const saving = ref(false)
const readError = ref('')
const actionError = ref('')
const replayPending = computed(() => Boolean(pendingCheckout(buyerId)))
const eligible = computed(() => rows.value.filter(line => !line.issue))
const chosen = computed(() => eligible.value.filter(line => selected.value?.includes(line.productId)))
const groups = computed(() => groupCartLines(rows.value))
const totals = computed(() => cartTotals(chosen.value))

async function handleSession(failure) {
  if (failure.status !== 401 && failure.code !== 'ACCOUNT_UNAVAILABLE') return false
  clearUser()
  await router.replace({ name: 'login', query: { redirect: '/cart' } })
  return true
}

async function load() {
  loading.value = true
  readError.value = ''
  try {
    rows.value = (await readCart()).lines
    const ids = eligible.value.map(line => line.productId)
    selected.value = selected.value === null ? ids.slice(0, 100) : ids.filter(id => selected.value.includes(id))
  } catch (failure) {
    if (!await handleSession(failure)) readError.value = failure.message
  } finally {
    loading.value = false
  }
}

function toggle(id, checked) {
  if (checked && selected.value.length >= 100) {
    actionError.value = '一次最多结算 100 种商品。'
    return
  }
  actionError.value = ''
  selected.value = checked ? [...selected.value, id] : selected.value.filter(value => value !== id)
}

async function update(id, quantity) {
  if (saving.value || loading.value) return
  saving.value = true
  actionError.value = ''
  try {
    if (quantity == null) await api.delete(`/cart/items/${id}`)
    else await setCartQuantity(id, quantity)
    await load()
  } catch (failure) {
    if (!await handleSession(failure)) {
      actionError.value = failure.message
      if (['PRODUCT_NOT_FOUND', 'STOCK_UNAVAILABLE'].includes(failure.code)) await load()
    }
  } finally {
    saving.value = false
  }
}

function checkout() {
  if (replayPending.value || chosen.value.length) {
    selectForCheckout(buyerId, chosen.value.map(line => line.productId))
    router.push({ name: 'checkout' })
  }
}

onMounted(load)
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'categories' }">← 继续浏览</RouterLink>
    <div class="seller-heading">
      <h1>购物车</h1>
      <button class="button button-outline" type="button" :disabled="loading || saving" @click="load">刷新购物车</button>
    </div>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <div v-if="replayPending" class="notice seller-note">
      上一次结账的结果尚未确认。<button class="text-button" type="button" @click="checkout">继续确认原请求</button>
    </div>
    <p v-if="loading" class="empty-panel" role="status">正在核对购物车…</p>
    <div v-else-if="readError" class="empty-panel">
      <p role="alert">{{ readError }}</p>
      <button class="button button-outline" type="button" @click="load">重试</button>
    </div>
    <div v-else-if="!rows.length" class="empty-panel">
      <p>购物车里还没有商品。</p>
      <RouterLink class="button" :to="{ name: 'categories' }">浏览商品</RouterLink>
    </div>
    <template v-else>
      <label class="seller-check cart-select">
        <input type="checkbox" :checked="eligible.length > 0 && selected.length === Math.min(eligible.length, 100)"
          :disabled="saving || !eligible.length" @change="selected = $event.target.checked ? eligible.slice(0, 100).map(line => line.productId) : []">
        {{ eligible.length > 100 ? '选择前 100 种可结账商品' : '全选可结账商品' }}
      </label>
      <p v-if="eligible.length > 100" class="muted">每次最多结算 100 种商品。</p>
      <div class="trade-split">
        <div class="seller-product-list">
          <section v-for="[sellerId, items] in groups" :key="sellerId" class="seller-panel trade-cart-group">
            <header class="trade-panel-heading">
              <h2>{{ items[0].shopName || '未能读取店铺信息' }}</h2>
              <span class="muted">{{ items.length }} 种商品</span>
            </header>
            <CartLine v-for="line in items" :key="line.productId" :line="line" :checked="selected.includes(line.productId)"
              :busy="saving" :limit-reached="selected.length >= 100" @update="update" @remove="update" @select="toggle" />
          </section>
        </div>
        <aside class="seller-panel trade-summary">
          <h2>结账摘要</h2>
          <p class="muted">已选 {{ chosen.length }} 种，共 {{ totals.quantity }} 件</p>
          <div class="trade-summary-total"><span>商品合计</span><strong>{{ formatCents(totals.amount) }}</strong></div>
          <button class="button" type="button" :disabled="saving || !chosen.length || replayPending" @click="checkout">去确认结账</button>
        </aside>
      </div>
    </template>
  </section>
</template>
