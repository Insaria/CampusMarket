<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api.js'
import { session } from '../auth.js'
import { checkoutReceipt } from '../cart.js'
import { adminMoney } from '../admin.js'
import { positiveId } from '../catalog.js'
import BatchOrders from '../components/BatchOrders.vue'

const route = useRoute()
const server = ref(null)
const loading = ref(true)
const error = ref('')
const retry = ref(0)
const result = computed(() => server.value ?? checkoutReceipt(session.user?.userId, route.params.batchId))
const payable = computed(() => result.value?.orders.length && result.value.orders
  .every(order => order.orderStatus === 'PENDING_PAYMENT'))

watch([() => route.params.batchId, retry], async ([param], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  server.value = null
  loading.value = true
  error.value = ''
  try {
    const id = positiveId(param)
    if (id) {
      const snapshot = await api.get(`/checkout-batches/${id}`, { signal: request.signal })
      if (!request.signal.aborted) server.value = snapshot
    } else error.value = '结账批次不存在。'
  } catch (failure) {
    if (!request.signal.aborted) error.value = failure.message
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'cart' }">← 返回购物车</RouterLink>
    <h1>结账结果</h1>
    <p v-if="loading && !result" class="empty-panel" role="status">正在读取结账回执…</p>
    <div v-if="error" class="notice error" role="alert">
      {{ error }} <button class="text-button" type="button" @click="retry++">重新查询</button>
    </div>
    <template v-if="result">
      <section class="seller-panel">
        <p class="notice success" role="status">{{ result.payment ? '钱包支付成功，已创建对应的商家订单。' : '已收到服务器结账回执。' }}</p>
        <dl class="seller-metrics trade-metrics">
          <div><dt>结账批次编号</dt><dd>{{ result.batchId }}</dd></div>
          <div><dt>商家订单数</dt><dd>{{ result.orders.length }}</dd></div>
          <div><dt>批次总报价</dt><dd class="product-price">{{ adminMoney(result.quotedAmount) }}</dd></div>
          <template v-if="result.payment">
            <div><dt>实扣钱包</dt><dd>{{ adminMoney(result.payment.paidAmount) }}</dd></div>
            <div><dt>积分抵扣</dt><dd>{{ result.payment.pointsUsed }} 积分 · {{ adminMoney(result.payment.pointsDiscountAmount) }}</dd></div>
          </template>
        </dl>
      </section>
      <BatchOrders :orders="result.orders" />
      <div class="actions admin-top-gap">
        <RouterLink v-if="payable || result.payment" :class="['button', { 'button-outline': !payable }]"
          :to="{ name: 'checkout-pay', params: { batchId: result.batchId } }">{{ payable ? '去支付' : '查看支付回执' }}</RouterLink>
        <RouterLink :class="['button', { 'button-outline': payable }]" :to="{ name: 'orders' }">查看我的订单</RouterLink>
        <RouterLink class="button button-outline" :to="{ name: 'categories' }">继续浏览</RouterLink>
        <RouterLink class="button button-outline" :to="{ name: 'pending-payments' }">待支付订单</RouterLink>
      </div>
    </template>
  </section>
</template>
