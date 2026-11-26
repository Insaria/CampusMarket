<script setup>
import { adminMoney, utcLabel } from '../admin.js'
import { useFinancePage } from '../finance.js'
import PageNav from '../components/PageNav.vue'

const { page, pages, data, loading, error, reload } = useFinancePage(() => '/checkout-batches')
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'account' }">← 个人中心</RouterLink>
    <header class="trade-panel-heading"><h1>待支付订单</h1><RouterLink :to="{ name: 'orders' }">查看所有订单 →</RouterLink></header>
    <p v-if="loading" class="empty-panel" role="status">正在读取待支付批次…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p><button class="button button-outline" type="button" @click="reload++">重试</button>
    </div>
    <template v-else-if="data">
      <div v-if="data.items.length" class="admin-table-wrap">
        <table class="admin-table finance-table">
          <thead><tr><th>批次编号</th><th>下单时间</th><th>订单数</th><th>订单总额</th><th>操作</th></tr></thead>
          <tbody><tr v-for="batch in data.items" :key="batch.batchId">
            <th scope="row">#{{ batch.batchId }}</th>
            <td>{{ utcLabel(batch.createdAt) }}</td>
            <td>{{ batch.orderCount }}</td>
            <td>{{ adminMoney(batch.quotedAmount) }}</td>
            <td><RouterLink class="button" :to="{ name: 'checkout-pay', params: { batchId: batch.batchId } }">去支付</RouterLink></td>
          </tr></tbody>
        </table>
      </div>
      <div v-else class="empty-panel">
        <p>暂无待支付订单。</p><RouterLink class="button button-outline" :to="{ name: 'cart' }">查看购物车</RouterLink>
      </div>
      <PageNav :page="page" :pages="pages" label="待支付批次分页" @change="page = $event" />
    </template>
  </section>
</template>
