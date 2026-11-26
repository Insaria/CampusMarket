<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminMoney, utcLabel } from '../admin.js'
import { api } from '../api.js'
import { positiveId } from '../catalog.js'
import { buyerOrders, orderLabel, returnLabels, sellerOrders, useOrderRead } from '../orders.js'
import { useWorkspaceConfirm } from '../workspace.js'
import OrderLines from '../components/OrderLines.vue'
import RefundProgress from '../components/RefundProgress.vue'
import OrderReviews from '../components/OrderReviews.vue'
import TradeAppointment from '../components/TradeAppointment.vue'

const props = defineProps({ seller: Boolean })
const route = useRoute()
const confirm = useWorkspaceConfirm()
const id = computed(() => positiveId(route.params.orderId))
const prefix = computed(() => props.seller ? sellerOrders : buyerOrders)
const retry = ref(0)
const busy = ref(false)
const message = ref('')
const actionError = ref('')
const { data, loading, error } = useOrderRead([prefix, id, retry], ([path, orderId], signal) => {
  if (!orderId) throw new Error('订单不存在。')
  return api.get(`${path}/${orderId}`, { signal })
})
const order = computed(() => data.value?.summary.order)

watch([id, prefix], () => {
  message.value = ''
  actionError.value = ''
})

async function action(endpoint, prompt, resultText, label, detail) {
  if (busy.value) return
  const orderId = id.value
  const path = route.path
  const current = () => orderId === id.value && route.path === path
  busy.value = true
  message.value = ''
  actionError.value = ''
  try {
    if (!await confirm(prompt, { label, detail }) || !current()) return
    await api.post(endpoint)
    if (current()) {
      message.value = resultText
      retry.value++
    }
  } catch (failure) {
    if (current()) actionError.value = !failure.status
      ? '操作结果尚未确认，正在查询订单最新状态。' : failure.message
    if (current()) retry.value++
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: seller ? 'seller-orders' : 'orders' }">← 返回订单列表</RouterLink>
    <h1>{{ seller ? '卖家订单详情' : '订单详情' }}</h1>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <p v-if="loading" class="empty-panel" role="status">正在读取订单…</p>
    <div v-else-if="error" class="empty-panel"><p role="alert">{{ error }}</p><button class="button button-outline" @click="retry++">重试</button></div>
    <template v-else-if="order">
      <section class="seller-panel">
        <div class="seller-heading">
          <h2>{{ order.orderNo }}</h2>
          <span class="status-tag" :data-state="order.orderStatus">{{ orderLabel(order.orderStatus) }}</span>
        </div>
        <dl class="seller-metrics trade-metrics" :class="{ 'management-metrics': seller }">
          <div><dt>批次编号</dt><dd>#{{ order.batchId }}</dd></div>
          <div><dt>商家</dt><dd><RouterLink :to="{ name: 'categories', query: { sellerId: order.sellerId } }">{{ order.shopName }}</RouterLink></dd></div>
          <div><dt>买家</dt><dd>{{ order.buyerLoginName }}</dd></div>
          <div><dt>下单时间</dt><dd>{{ utcLabel(order.createdAt) }}</dd></div>
          <div><dt>预约交易时间</dt><dd>{{ utcLabel(order.scheduledTradeAt) }}</dd></div>
          <div><dt>交易地点</dt><dd>{{ order.tradeLocation || '—' }}</dd></div>
          <div><dt>实际交付时间</dt><dd>{{ utcLabel(order.deliveredAt) }}</dd></div>
          <div><dt>买家确认收货</dt><dd>{{ utcLabel(order.buyerConfirmedAt) }}</dd></div>
          <div><dt>系统自动确认</dt><dd>{{ utcLabel(order.autoConfirmedAt) }}</dd></div>
        </dl>
        <div class="actions">
          <template v-if="!seller && order.orderStatus === 'PENDING_PAYMENT' && data.batchCancellable">
            <RouterLink class="button" :to="{ name: 'checkout-pay', params: { batchId: order.batchId } }">前往支付</RouterLink>
            <button class="button button-outline" type="button" :disabled="busy"
              @click="action('/checkout-batches/' + order.batchId + '/cancel',
                '取消整个结账批次？', '结账批次已取消。', '取消批次', '该批次下所有待支付订单都会取消。')">取消整个批次</button>
          </template>
          <button v-if="seller && order.orderStatus === 'PAID'" type="button" class="button" :disabled="busy"
            @click="action(sellerOrders + '/' + order.orderId + '/deliver',
              '确认已完成线下交付？', '已记录线下交付。', '确认交付', '服务器将记录实际交付时间并消耗该订单预留的库存。')">确认交付</button>
          <button v-if="!seller && order.orderStatus === 'DELIVERED'" type="button" class="button" :disabled="busy"
            @click="action(buyerOrders + '/' + order.orderId + '/confirm-receipt',
              '确认已经收到商品？', '已确认收货。', '确认收货', '确认后开始计算 24 小时退货申请期限。')">确认收货</button>
          <RouterLink v-if="!seller && order.orderStatus === 'COMPLETED' && (data.canRequestReturn || data.returns.length)"
            class="button button-outline" :to="{ name: 'apply-return', params: { orderId: order.orderId } }">
            {{ data.canRequestReturn ? '申请退货' : '查看退货进度' }}
          </RouterLink>
        </div>
        <p v-if="busy" role="status">正在处理操作…</p>
        <aside v-if="['PAID', 'DELIVERED', 'COMPLETED'].includes(order.orderStatus) && Number(order.cashPaid) > 0" class="trade-status-note">
          <strong>货款结算规则</strong>
          <p v-if="order.orderStatus !== 'COMPLETED'">本单现金货款由平台托管，等待确认收货。</p>
          <p>确认收货满 24 小时，且无有效退货申请或未完成退款时，才具备结算条件。具体到账以卖家钱包流水为准。</p>
          <RouterLink v-if="seller" :to="{ name: 'wallet' }">查看钱包流水 →</RouterLink>
        </aside>
        <p v-if="data.returnClosesAt && !seller" class="muted">退货申请截止 {{ utcLabel(data.returnClosesAt) }}。</p>
      </section>
      <TradeAppointment v-if="order.orderStatus === 'PAID'" :key="order.orderId" :order-id="order.orderId"
        @confirmed="retry++" />
      <section class="seller-panel admin-top-gap">
        <h2>成交商品</h2>
        <OrderLines :lines="data.summary.lines" :catalog-links="!seller" />
        <dl class="seller-metrics trade-metrics">
          <div><dt>商品报价</dt><dd>{{ adminMoney(data.summary.quotedAmount) }}</dd></div>
          <div><dt>订单应付</dt><dd>{{ adminMoney(order.payableAmount) }}</dd></div>
          <div><dt>现金实付</dt><dd>{{ adminMoney(order.cashPaid) }}</dd></div>
          <div><dt>积分抵扣</dt><dd>{{ adminMoney(order.pointsDiscountAmount) }} · {{ order.pointsUsed }} 积分</dd></div>
          <div v-if="!seller"><dt>原发放奖励</dt><dd>{{ data.pointsRewarded }} 积分</dd></div>
        </dl>
      </section>
      <section class="seller-panel admin-top-gap">
        <h2>订单状态记录</h2>
        <ol v-if="data.events.length" class="order-events">
          <li v-for="event in data.events" :key="event.eventSeq">
            <strong>{{ event.fromStatus ? orderLabel(event.fromStatus) + ' → ' : '' }}{{ orderLabel(event.toStatus) }}</strong>
            <span class="muted">{{ utcLabel(event.createdAt) }} · {{ event.actorType === 'SYSTEM' ? '系统' : '用户' }}{{ event.systemSource === 'AUTO_CONFIRM' ? '自动确认' : '' }}</span>
          </li>
        </ol>
        <p v-else class="muted">暂无状态事件。</p>
      </section>
      <section v-if="data.returns.length" class="seller-panel admin-top-gap">
        <h2>退货与退款</h2>
        <div v-for="item in data.returns" :key="item.requestId" class="order-return">
          <div class="trade-panel-heading">
            <strong>申请 #{{ item.requestId }}</strong>
            <span class="status-tag" :data-state="item.requestStatus">{{ returnLabels[item.requestStatus] || item.requestStatus }}</span>
          </div>
          <RefundProgress :progress="item" />
          <RouterLink v-if="seller" :to="{ name: 'seller-return', params: { requestId: item.requestId } }">查看退货申请</RouterLink>
        </div>
      </section>
      <OrderReviews :key="order.orderId" :details="data" :seller="seller" />
    </template>
  </section>
</template>
