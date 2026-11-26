<script setup>
import { adminMoney, utcLabel } from '../admin.js'
import { fundingLabels, refundLabels } from '../orders.js'

defineProps({ progress: { type: Object, required: true } })
</script>

<template>
  <template v-if="progress.refundStatus">
    <h3>退款进度</h3>
    <p :class="['notice', progress.refundStatus === 'COMPLETED' ? 'success' : 'seller-note']" role="status">
      {{ refundLabels[progress.refundStatus] ?? progress.refundStatus }}
    </p>
    <dl class="seller-metrics trade-metrics">
      <div><dt>退款编号</dt><dd>#{{ progress.refundId }}</dd></div>
      <div><dt>{{ progress.refundStatus === 'PENDING_CASH' ? '待退现金' : '现金退款金额' }}</dt><dd>{{ adminMoney(progress.cashRefundAmount) }}</dd></div>
      <div><dt>退款来源</dt><dd>{{ fundingLabels[progress.fundingPath] ?? '—' }}</dd></div>
      <div><dt>已恢复积分</dt><dd>{{ progress.pointsRestored ?? '—' }}</dd></div>
      <div><dt>已冲回奖励</dt><dd>{{ progress.pointsReversed ?? '—' }}</dd></div>
      <div v-if="progress.refundCompletedAt"><dt>退款完成时间</dt><dd>{{ utcLabel(progress.refundCompletedAt) }}</dd></div>
    </dl>
  </template>
  <p v-else class="muted">暂无退款记录。</p>
</template>
