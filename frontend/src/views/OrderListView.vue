<script setup>
import { adminMoney, utcLabel } from '../admin.js'
import { buyerOrders, orderLabel, sellerOrders, useOrderList } from '../orders.js'
import OrderLines from '../components/OrderLines.vue'
import PageNav from '../components/PageNav.vue'
import StatusBadge from '../components/StatusBadge.vue'

const props = defineProps({ seller: Boolean })
const filters = ['', 'PENDING_PAYMENT', 'PAID', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'RETURNS']
const { status, page, retry, data, loading, error, pages, selectStatus } = useOrderList(() => props.seller ? sellerOrders : buyerOrders)
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink v-if="!seller" class="catalog-back" :to="{ name: 'account' }">← 个人中心</RouterLink>
    <h1>{{ seller ? '收到的订单' : '我的订单' }}</h1>
    <div class="order-filters" :class="{ 'management-filter-tabs': seller }" role="group" aria-label="订单筛选">
      <button v-for="filter in filters" :key="filter" :class="['button', status === filter ? '' : 'button-outline']"
        :aria-pressed="status === filter" type="button" @click="selectStatus(filter)">
        {{ filter === 'RETURNS' ? '退货退款' : filter === 'DELIVERED' && seller ? '已交付' : orderLabel(filter) || '全部订单' }}
      </button>
    </div>
    <p v-if="loading" class="empty-panel" role="status">正在查询订单…</p>
    <div v-else-if="error" class="empty-panel"><p role="alert">{{ error }}</p><button class="button button-outline" @click="retry++">重试</button></div>
    <template v-else-if="data">
      <p v-if="!data.items.length" class="empty-panel">没有符合条件的订单。</p>
      <div v-else class="seller-product-list">
        <article v-for="{ order, lines, quotedAmount } in data.items" :key="order.orderId" class="seller-panel">
          <div class="seller-heading">
            <div>
              <h2><RouterLink :to="{ name: seller ? 'seller-order' : 'order', params: { orderId: order.orderId } }">{{ order.orderNo }}</RouterLink></h2>
              <p class="trade-order-meta">
                <span v-if="seller">买家 {{ order.buyerLoginName }}</span>
                <RouterLink v-else :to="{ name: 'categories', query: { sellerId: order.sellerId } }">{{ order.shopName }}</RouterLink>
                <span class="muted">批次 #{{ order.batchId }}</span>
              </p>
            </div>
            <StatusBadge :state="order.orderStatus" :label="seller && order.orderStatus === 'DELIVERED' ? '已交付' : orderLabel(order.orderStatus)" />
          </div>
          <OrderLines :lines="lines" :catalog-links="!seller" />
          <dl class="seller-metrics trade-metrics">
            <div><dt>商品报价</dt><dd>{{ adminMoney(quotedAmount) }}</dd></div>
            <div><dt>现金实付</dt><dd>{{ adminMoney(order.cashPaid) }}</dd></div>
            <div><dt>积分抵扣</dt><dd>{{ adminMoney(order.pointsDiscountAmount) }}</dd></div>
            <div><dt>下单时间</dt><dd>{{ utcLabel(order.createdAt) }}</dd></div>
            <div v-if="order.scheduledTradeAt"><dt>预约交易时间</dt><dd>{{ utcLabel(order.scheduledTradeAt) }}</dd></div>
            <div><dt>交付时间</dt><dd>{{ utcLabel(order.deliveredAt) }}</dd></div>
            <div><dt>确认收货</dt><dd>{{ utcLabel(order.buyerConfirmedAt ?? order.autoConfirmedAt) }}</dd></div>
          </dl>
          <RouterLink class="button button-outline" :to="{ name: seller ? 'seller-order' : 'order', params: { orderId: order.orderId } }">
            {{ seller && order.orderStatus === 'PAID' ? '查看预约与交付' : '查看详情' }}
          </RouterLink>
        </article>
      </div>
      <PageNav :page="page" :pages="pages" label="订单分页" @change="page = $event" />
    </template>
  </section>
</template>
