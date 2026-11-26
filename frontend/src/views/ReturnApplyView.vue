<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminMoney, utcLabel } from '../admin.js'
import { api } from '../api.js'
import { session } from '../auth.js'
import { positiveId } from '../catalog.js'
import { buyerOrders, returnLabels, useOrderRead } from '../orders.js'
import { useWorkspaceConfirm } from '../workspace.js'
import OrderLines from '../components/OrderLines.vue'
import RefundProgress from '../components/RefundProgress.vue'

const route = useRoute()
const confirm = useWorkspaceConfirm()
const id = computed(() => positiveId(route.params.orderId))
const retry = ref(0)
const { data, loading, error } = useOrderRead([id, retry], ([orderId], signal) => {
  if (!orderId) throw new Error('订单不存在。')
  return api.get(`${buyerOrders}/${orderId}`, { signal })
})
const windowClosed = computed(() => {
  const deadline = data.value?.returnClosesAt
  return !data.value?.canRequestReturn && deadline && Date.parse(deadline + 'Z') <= Date.now()
})
const reason = ref('')
const busy = ref(false)
const actionError = ref('')
const recorded = ref(null)
const pending = ref(null)
const storageKey = computed(() => `campus-return-attempt:${session.user?.userId}:${id.value}`)
let memoryAttempt
const finalErrors = new Set(['RETURN_REASON_REQUIRED', 'RETURN_REQUEST_KEY_CONFLICT', 'RETURN_WINDOW_CLOSED',
  'ORDER_NOT_RETURNABLE', 'ACTIVE_RETURN_REQUEST_EXISTS', 'ORDER_NOT_FOUND', 'VALIDATION_FAILED'])

watch(storageKey, (key) => {
  reason.value = ''
  actionError.value = ''
  recorded.value = null
  try {
    pending.value = JSON.parse(sessionStorage.getItem(key))
  } catch {
    pending.value = null
  }
  if (memoryAttempt?.key === key) pending.value = memoryAttempt.value
}, { immediate: true })

function remember(key, value) {
  memoryAttempt = value ? { key, value } : undefined
  if (key === storageKey.value) pending.value = value
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value))
    else sessionStorage.removeItem(key)
  } catch {
    // The original key remains available for retry within the page.
  }
}

async function submit() {
  if (busy.value || loading.value || (!data.value?.canRequestReturn && !pending.value)) return
  const text = reason.value.trim()
  actionError.value = ''
  if (!text || text.length > 500) {
    actionError.value = '请填写 1 至 500 字的退货原因。'
    return
  }
  busy.value = true
  const key = storageKey.value
  const orderId = id.value
  try {
    if (!pending.value && !await confirm('提交整笔订单退货申请？', {
      label: '提交申请', detail: '申请将交给当前订单的卖家审核。',
    })) return
    if (key !== storageKey.value) return
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))),
      byte => byte.toString(16).padStart(2, '0')).join('')
    if (key !== storageKey.value) return
    if (pending.value && pending.value.hash !== hash) {
      actionError.value = '上次提交结果尚未确认。重试时请填写相同的退货原因。'
      return
    }
    const attempt = pending.value ?? { requestKey: crypto.randomUUID(), hash }
    remember(key, attempt)
    const item = await api.post(`${buyerOrders}/${orderId}/return-requests`, { requestKey: attempt.requestKey, reason: text })
    remember(key, null)
    if (key !== storageKey.value) return
    recorded.value = item
    reason.value = ''
    retry.value++
  } catch (failure) {
    if (finalErrors.has(failure.code)) remember(key, null)
    if (key !== storageKey.value) return
    if (finalErrors.has(failure.code)) retry.value++
    actionError.value = pending.value && (!failure.status || failure.status >= 500)
      ? '申请结果尚未确认。可以按原退货原因重试，或刷新查询申请进度。' : failure.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="seller-workspace seller-narrow trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'order', params: { orderId: route.params.orderId } }">← 返回订单详情</RouterLink>
    <h1>退货申请</h1>
    <p v-if="recorded" class="notice success" role="status">
      已收到申请 #{{ recorded.id }} 的回执，审核状态：{{ returnLabels[recorded.requestStatus] }}。
    </p>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <p v-if="loading" class="empty-panel" role="status">正在读取订单与申请…</p>
    <div v-else-if="error" class="empty-panel"><p role="alert">{{ error }}</p><button class="button button-outline" @click="retry++">重试</button></div>
    <template v-else-if="data">
      <section class="seller-panel">
        <h2>{{ data.summary.order.orderNo }}</h2>
        <p>{{ data.summary.order.shopName }} · 现金实付 {{ adminMoney(data.summary.order.cashPaid) }}</p>
        <OrderLines :lines="data.summary.lines" />
        <div class="trade-status-note">确认收货后 24 小时内可申请退货。<span v-if="data.returnClosesAt">截止时间 {{ utcLabel(data.returnClosesAt) }}。</span></div>
      </section>
      <form v-if="data.canRequestReturn || (pending && !data.returns.some(item => item.active))" class="seller-panel admin-top-gap"
        @submit.prevent="submit">
        <h2>申请整笔订单退货</h2>
        <p v-if="pending" class="notice seller-note">上次申请结果待确认。请使用相同的退货原因重试。</p>
        <label>退货原因<textarea class="order-textarea" v-model="reason" maxlength="500" rows="4" required :disabled="busy"></textarea></label>
        <p class="muted trade-character-count">{{ reason.length }} / 500</p>
        <div class="actions">
          <button class="button" type="submit" :disabled="busy">{{ busy ? '正在提交…' : pending ? '重试原申请' : '提交退货申请' }}</button>
          <button class="button button-outline" type="button" :disabled="busy" @click="retry++">刷新进度</button>
        </div>
      </form>
      <p v-else-if="!data.returns.length" class="notice seller-note admin-top-gap">{{ windowClosed ? '已超过确认收货后的 24 小时退货期限。' : '该订单当前不符合申请条件。' }}</p>
      <section v-if="data.returns.length" class="seller-panel admin-top-gap">
        <div class="seller-heading">
          <h2>申请记录</h2>
          <button class="button button-outline" type="button" @click="retry++">刷新进度</button>
        </div>
        <article v-for="item in data.returns" :key="item.requestId" class="order-return">
          <div class="trade-panel-heading">
            <h3>申请 #{{ item.requestId }}</h3><span class="status-tag" :data-state="item.requestStatus">{{ returnLabels[item.requestStatus] || item.requestStatus }}</span>
          </div>
          <p class="muted">{{ utcLabel(item.appliedAt) }}</p>
          <p class="product-description">{{ item.reason }}</p>
          <p v-if="item.reviewReason" class="product-description">审核意见：{{ item.reviewReason }}</p>
          <p v-if="item.returnedAt">验收时间：{{ utcLabel(item.returnedAt) }}</p>
          <RefundProgress :progress="item" />
        </article>
      </section>
    </template>
  </section>
</template>
