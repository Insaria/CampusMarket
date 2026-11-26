<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminMoney, utcLabel } from '../admin.js'
import { api } from '../api.js'
import { positiveId } from '../catalog.js'
import { orderLabel, returnLabels, sellerOrders, sellerReturns, useOrderRead } from '../orders.js'
import OrderLines from '../components/OrderLines.vue'
import RefundProgress from '../components/RefundProgress.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useWorkspaceConfirm } from '../workspace.js'

const route = useRoute()
const id = computed(() => positiveId(route.params.requestId))
const retry = ref(0)
const { data, loading, error } = useOrderRead([id, retry], async ([requestId], signal) => {
  if (!requestId) throw new Error('退货申请不存在。')
  const request = await api.get(`${sellerReturns}/${requestId}`, { signal })
  return { request, details: await api.get(`${sellerOrders}/${request.orderId}`, { signal }) }
})
const busy = ref(false)
const feedback = ref('')
const actionError = ref('')
const reply = ref('')
const refundReceipt = ref(null)
const receiptItems = ref(null)
const confirm = useWorkspaceConfirm()
const order = computed(() => data.value?.details.summary.order)
const item = computed(() => data.value?.request)
const progress = computed(() => data.value?.details.returns.find(value => String(value.requestId) === id.value)
  ?? refundReceipt.value ?? {})
const restocks = computed(() => receiptItems.value ?? data.value?.details.restocks.filter(row => String(row.requestId) === id.value) ?? [])
const canReview = computed(() => item.value?.active && item.value.requestStatus === 'PENDING')
const canReceive = computed(() => item.value?.active && item.value.requestStatus === 'APPROVED'
  && order.value?.orderStatus === 'COMPLETED')
const canRefund = computed(() => item.value?.active && ['APPROVED', 'RETURNED'].includes(item.value.requestStatus)
  && order.value?.orderStatus === 'COMPLETED' && (!progress.value.refundStatus || progress.value.refundStatus === 'PENDING_CASH'))

watch(id, () => {
  feedback.value = ''
  actionError.value = ''
  reply.value = ''
  refundReceipt.value = null
  receiptItems.value = null
})

async function mutate(action, decision) {
  if (busy.value || loading.value) return
  actionError.value = ''
  const reviewReason = reply.value.trim()
  if (action === 'decision' && decision === 'REJECT' && !reviewReason) {
    actionError.value = '请填写拒绝意见。'
    return
  }
  const prompt = action === 'refund' ? '确认处理这笔退款？'
    : action === 'receive' ? '确认已收到全部退货商品，且均可重新销售？'
      : decision === 'APPROVE' ? '确认批准这笔退货申请？' : '确认拒绝这笔退货申请？'
  if (!(await confirm(prompt))) return
  const requestId = id.value
  const endpoint = `${sellerReturns}/${requestId}/${action}`
  busy.value = true
  feedback.value = ''
  try {
    if (action === 'decision') {
      await api.patch(endpoint, { decision, reviewReason: reviewReason || null })
      if (requestId === id.value) feedback.value = '已记录退货审核决定。'
    } else if (action === 'refund') {
      const result = await api.post(endpoint)
      if (requestId === id.value) refundReceipt.value = result
    } else {
      const result = await api.post(endpoint, { resellable: true })
      if (requestId === id.value) {
        receiptItems.value = result.items
        feedback.value = '已收到退货验收入库回执。'
      }
    }
  } catch (failure) {
    if (requestId !== id.value) return
    if (action === 'refund' && failure.code === 'SELLER_REFUND_BALANCE_INSUFFICIENT') {
      refundReceipt.value = failure.data
    }
    actionError.value = !failure.status ? '操作结果尚未确认，正在查询服务器记录。' : failure.message
  } finally {
    if (requestId === id.value) retry.value++
    busy.value = false
  }
}
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'seller-returns' }">← 返回退货列表</RouterLink>
    <h1>退货申请详情</h1>
    <p v-if="feedback" class="notice success" role="status">{{ feedback }}</p>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <p v-if="loading" class="empty-panel" role="status">正在读取申请…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p><button class="button button-outline" @click="retry++">重试查询</button>
      <RefundProgress v-if="refundReceipt" :progress="refundReceipt" />
    </div>
    <template v-else-if="item && order">
      <section class="seller-panel">
        <div class="seller-heading">
          <h2>申请 #{{ item.id }}</h2>
          <StatusBadge :state="item.requestStatus" :label="returnLabels[item.requestStatus]" />
        </div>
        <dl class="seller-metrics trade-metrics">
          <div><dt>关联订单</dt><dd><RouterLink :to="{ name: 'seller-order', params: { orderId: item.orderId } }">{{ item.orderNo }}</RouterLink></dd></div>
          <div><dt>买家</dt><dd>{{ order.buyerLoginName }}</dd></div>
          <div><dt>订单状态</dt><dd>{{ orderLabel(item.orderStatus) }}</dd></div>
          <div><dt>原现金实付</dt><dd>{{ adminMoney(order.cashPaid) }}</dd></div>
          <div><dt>原积分抵扣</dt><dd>{{ adminMoney(order.pointsDiscountAmount) }} · {{ order.pointsUsed }} 积分</dd></div>
          <div><dt>申请时间</dt><dd>{{ utcLabel(item.appliedAt) }}</dd></div>
          <div><dt>审核时间</dt><dd>{{ utcLabel(item.reviewedAt) }}</dd></div>
        </dl>
        <OrderLines :lines="data.details.summary.lines" />
        <h3>退货原因</h3><p class="product-description">{{ item.reason }}</p>
        <p v-if="item.reviewReason" class="product-description">审核意见：{{ item.reviewReason }}</p>
        <div v-if="canReview" class="seller-review">
          <label>审核意见（拒绝时必填）<textarea class="order-textarea" v-model="reply" maxlength="500" rows="3" :disabled="busy"></textarea></label>
          <div class="actions">
            <button class="button" type="button" :disabled="busy" @click="mutate('decision', 'APPROVE')">批准退货</button>
            <button class="button button-outline" type="button" :disabled="busy" @click="mutate('decision', 'REJECT')">拒绝退货</button>
          </div>
        </div>
      </section>
      <section class="seller-panel admin-top-gap">
        <div class="seller-heading"><h2>资金处理</h2><button class="button button-outline" type="button" :disabled="busy" @click="retry++">刷新进度</button></div>
        <RefundProgress :progress="progress" />
        <button v-if="canRefund" class="button" type="button" :disabled="busy" @click="mutate('refund')">
          {{ progress.refundStatus === 'PENDING_CASH' ? '重试现金退款' : '发起退款' }}
        </button>
      </section>
      <section class="seller-panel admin-top-gap">
        <h2>退货验收入库</h2>
        <p v-if="item.returnedAt">验收时间：{{ utcLabel(item.returnedAt) }}</p>
        <ul v-if="restocks.length" class="order-lines">
          <li v-for="line in restocks" :key="line.orderItemId">
            <span>{{ data.details.summary.lines.find(value => value.orderItemId === line.orderItemId)?.productName || '#' + line.productId }}</span>
            <strong>恢复库存 {{ line.quantity }} 件</strong>
          </li>
        </ul>
        <p v-else-if="item.returnedAt" class="muted">暂无该申请的入库明细。</p>
        <button v-if="canReceive" class="button button-outline" type="button" :disabled="busy" @click="mutate('receive')">确认验收入库</button>
      </section>
    </template>
  </section>
</template>
