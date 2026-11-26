<script setup>
import { utcLabel } from '../admin.js'
import { session } from '../auth.js'

defineProps({ items: { type: Array, required: true } })
</script>

<template>
  <ul v-if="items.length" class="review-list">
    <li v-for="review in items" :key="review.id">
      <div class="review-meta">
        <strong>{{ review.reviewerId === session.user?.userId ? '我发出的评价' : '我收到的评价' }}</strong>
        <span>{{ review.returnRequestId != null ? '退货评价 #' + review.returnRequestId : '购买交易评价' }}</span>
        <time :datetime="review.createdAt">{{ utcLabel(review.createdAt) }}</time>
      </div>
      <p v-if="review.orderNo" class="muted">订单 {{ review.orderNo }}</p>
      <p :class="review.rating == null ? 'muted' : 'review-stars'">{{ review.rating == null ? '仅文字评价' : review.rating + ' / 5 星' }}</p>
      <p v-if="review.content" class="product-description">{{ review.content }}</p>
    </li>
  </ul>
  <p v-else class="muted">暂无交易评价。</p>
</template>
