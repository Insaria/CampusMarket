<script setup>
import { adminMoney, utcLabel } from '../admin.js'
import { returnLabels, sellerReturns, useOrderList } from '../orders.js'
import PageNav from '../components/PageNav.vue'
import StatusBadge from '../components/StatusBadge.vue'
import WorkspaceTable from '../components/WorkspaceTable.vue'

const { status, page, retry, data, loading, error, pages, selectStatus } = useOrderList(() => sellerReturns)
</script>

<template>
  <section class="seller-workspace">
    <header class="management-page-header"><h1>退货申请</h1></header>
    <div class="seller-panel management-filter-bar">
      <label class="return-filter">申请状态
        <select :value="status" @change="selectStatus($event.target.value)">
          <option value="">全部申请</option>
          <option v-for="(label, code) in returnLabels" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
      <p v-if="data" class="muted management-result-count" role="status">共 {{ data.total }} 笔</p>
    </div>
    <p v-if="loading" class="empty-panel" role="status">正在查询申请…</p>
    <div v-else-if="error" class="empty-panel"><p role="alert">{{ error }}</p><button class="button button-outline" @click="retry++">重试</button></div>
    <template v-else-if="data">
      <WorkspaceTable v-if="data.items.length" label="退货申请列表">
        <table class="admin-table">
          <thead><tr><th>申请</th><th>订单编号</th><th>应付金额</th><th>状态</th><th>申请时间</th><th>操作</th></tr></thead>
          <tbody><tr v-for="item in data.items" :key="item.id">
            <th scope="row">#{{ item.id }}</th><td>{{ item.orderNo }}</td><td>{{ adminMoney(item.payableAmount) }}</td>
            <td><StatusBadge :state="item.requestStatus" :label="returnLabels[item.requestStatus]" /></td>
            <td>{{ utcLabel(item.appliedAt) }}</td>
            <td><RouterLink class="button button-outline" :to="{ name: 'seller-return', params: { requestId: item.id } }">查看申请</RouterLink></td>
          </tr></tbody>
        </table>
      </WorkspaceTable>
      <p v-else class="empty-panel">暂无符合条件的退货申请。</p>
      <PageNav :page="page" :pages="pages" label="退货申请分页" @change="page = $event" />
    </template>
  </section>
</template>
