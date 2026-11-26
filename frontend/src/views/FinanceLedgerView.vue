<script setup>
import { computed } from 'vue'
import { adminMoney, utcLabel } from '../admin.js'
import { ledgerKinds, useFinancePage } from '../finance.js'
import { sellerHistoryAllowed } from '../auth.js'
import PageNav from '../components/PageNav.vue'

const props = defineProps({ kind: { type: String, required: true } })
const seller = computed(sellerHistoryAllowed)
const points = computed(() => props.kind === 'points')
const { page, pages, data, loading, error, reload } = useFinancePage(() => '/' + props.kind)
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'account' }">← 个人中心</RouterLink>
    <h1>{{ points ? '我的积分' : '我的钱包' }}</h1>
    <p v-if="loading" class="empty-panel" role="status">正在读取账户与流水…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p><button class="button button-outline" type="button" @click="reload++">重试</button>
    </div>
    <template v-else-if="data">
      <div class="seller-panel finance-balance trade-balance-card">
        <span>{{ points ? '可用积分' : '可用钱包余额' }}</span>
        <strong v-if="data.balance != null">{{ points ? data.balance + ' 积分' : adminMoney(data.balance) }}</strong>
        <strong v-else>{{ points ? '尚未开立积分账户' : '尚未开立钱包账户' }}</strong>
        <p v-if="points" class="muted">每 100 积分抵 1 元，每笔商家订单须保留正数现金支付。每笔实付现金每满 1 元奖励 1 积分。退款按原流水恢复抵扣积分并冲回奖励。</p>
        <template v-else-if="seller">
          <p class="muted">待结算托管货款尚未计入可用余额。到账记录见下方「卖家结算」流水。</p>
          <RouterLink :to="{ name: 'seller-orders' }">查看收到的订单 →</RouterLink>
        </template>
        <RouterLink v-else :to="{ name: 'orders' }">查看我的订单 →</RouterLink>
      </div>
      <h2 class="admin-top-gap">{{ points ? '积分流水' : '钱包流水' }}</h2>
      <div v-if="data.items.length" class="admin-table-wrap" role="region" tabindex="0" :aria-label="points ? '积分流水表' : '钱包流水表'">
        <table class="admin-table finance-table">
          <thead><tr><th>交易时间</th><th>类型</th><th>关联订单</th><th>{{ points ? '积分变动' : '收支金额' }}</th></tr></thead>
          <tbody><tr v-for="entry in data.items" :key="entry.id">
            <td>{{ utcLabel(entry.createdAt) }}</td>
            <th scope="row">{{ ledgerKinds[entry.entryKind] || entry.entryKind }}</th>
            <td>
              <RouterLink v-if="entry.orderId != null" :to="{
                name: ['SELLER_SETTLEMENT', 'SELLER_REFUND_RECOVERY'].includes(entry.entryKind) ? 'seller-order' : 'order',
                params: { orderId: entry.orderId },
              }">{{ entry.orderNo ?? '#' + entry.orderId }}</RouterLink>
              <span v-else>{{ entry.orderNo ?? '—' }}</span>
            </td>
            <td :class="entry.direction === 'CREDIT' ? 'finance-credit' : 'finance-debit'">
              {{ entry.direction === 'CREDIT' ? '+' : '−' }}{{ points ? entry.quantity : adminMoney(entry.amount) }}
            </td>
          </tr></tbody>
        </table>
      </div>
      <p v-else class="empty-panel">暂无{{ points ? '积分' : '钱包' }}流水。</p>
      <PageNav :page="page" :pages="pages" :label="points ? '积分流水分页' : '钱包流水分页'" @change="page = $event" />
    </template>
  </section>
</template>
