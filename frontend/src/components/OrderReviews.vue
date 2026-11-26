<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api.js'
import { utcLabel } from '../admin.js'
import { session } from '../auth.js'
import ReviewForm from './ReviewForm.vue'
import TradeReviewList from './TradeReviewList.vue'

const props = defineProps({ details: { type: Object, required: true }, seller: Boolean })
const order = computed(() => props.details.summary.order)
const reviews = ref(null)
const productSnapshot = ref(null)
const loading = ref(true)
const error = ref('')
const productError = ref('')
let tradeRequest
let productRequest

const productByLine = computed(() => new Map((productSnapshot.value ?? props.details.itemReviews ?? [])
  .map(item => [item.orderItemId, item])))
const myReviews = computed(() => reviews.value?.filter(item => item.reviewerId === session.user?.userId) ?? [])
const purchased = computed(() => myReviews.value.some(item => item.returnRequestId == null))
const returnReviews = computed(() => new Set(myReviews.value.filter(item => item.returnRequestId != null)
  .map(item => item.returnRequestId)))
const receivedReturns = computed(() => props.details.returns.filter(item => item.requestStatus === 'RETURNED' && item.returnedAt))
const canTrade = computed(() => props.details.canReview && reviews.value && !error.value && !loading.value)
const purchaseEndpoint = computed(() => props.seller ? `/sellers/me/orders/${order.value.orderId}/buyer-review`
  : `/orders/${order.value.orderId}/seller-review`)

async function loadTrade() {
  tradeRequest?.abort()
  const controller = new AbortController()
  tradeRequest = controller
  loading.value = true
  error.value = ''
  try {
    const result = await api.get(`/orders/${order.value.orderId}/trade-reviews`, { signal: controller.signal })
    if (!controller.signal.aborted) reviews.value = result
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure.message
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

async function loadProductReviews() {
  productRequest?.abort()
  const controller = new AbortController()
  productRequest = controller
  productError.value = ''
  try {
    const result = await api.get(`/orders/${order.value.orderId}`, { signal: controller.signal })
    if (!controller.signal.aborted) productSnapshot.value = result.itemReviews
  } catch (failure) {
    if (!controller.signal.aborted) productError.value = '暂时无法刷新商品评价。'
  }
}

onMounted(loadTrade)
onBeforeUnmount(() => {
  tradeRequest?.abort()
  productRequest?.abort()
})
</script>

<template>
  <section v-if="!seller && (details.canReview || productByLine.size)" class="seller-panel admin-top-gap">
    <h2>商品评价</h2>
    <p v-if="productError" class="notice error" role="alert">
      {{ productError }} <button class="text-button" type="button" @click="loadProductReviews">重试查询</button>
    </p>
    <article v-for="line in details.summary.lines" :key="line.orderItemId" class="review-entry">
      <h3>{{ line.productName }}</h3>
      <template v-if="productByLine.has(line.orderItemId)">
        <p class="muted">已评价 · {{ utcLabel(productByLine.get(line.orderItemId).createdAt) }}</p>
        <p v-if="productByLine.get(line.orderItemId).rating != null" class="review-stars">{{ productByLine.get(line.orderItemId).rating }} / 5 星</p>
        <p class="product-description">{{ productByLine.get(line.orderItemId).content }}</p>
      </template>
      <ReviewForm v-else-if="details.canReview" :endpoint="`/order-items/${line.orderItemId}/review`" title="评价该商品"
        @refresh="loadProductReviews" />
    </article>
  </section>
  <section class="seller-panel admin-top-gap">
    <h2>交易评价</h2>
    <p v-if="loading" role="status">正在查阅交易评价…</p>
    <p v-else-if="error" class="notice error" role="alert">
      {{ error }} <button class="text-button" type="button" @click="loadTrade">重试查询</button>
    </p>
    <TradeReviewList v-else-if="reviews" :items="reviews" />
    <p v-if="purchased" class="muted">你已提交本单的购买交易评价。</p>
    <p v-else-if="!details.canReview" class="muted">当前订单不可提交评价。</p>
    <ReviewForm v-else-if="details.canReview && reviews" v-show="canTrade" :endpoint="purchaseEndpoint"
      :title="seller ? '评价买家的购买交易' : '评价卖家服务'" @refresh="loadTrade" />
    <template v-if="seller && details.canReview && reviews">
      <div v-for="item in receivedReturns" :key="item.requestId" class="review-entry">
        <p v-if="returnReviews.has(item.requestId)" class="muted">你已评价退货 #{{ item.requestId }}。</p>
        <ReviewForm v-else v-show="canTrade" :endpoint="`/sellers/me/return-requests/${item.requestId}/buyer-review`"
          :title="`评价已验收退货 #${item.requestId}`" @refresh="loadTrade" />
      </div>
    </template>
  </section>
</template>
