<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminMoney, adminSettlements, paramsOf, useAdminPage, useAdminRead, utcLabel } from '../../admin.js'
import { api } from '../../api.js'
import { percent, positiveId } from '../../catalog.js'
import AdminFeedback from '../../components/AdminFeedback.vue'
import PageNav from '../../components/PageNav.vue'
import WorkspaceTable from '../../components/WorkspaceTable.vue'

const filters = reactive({ from: '', to: '', sellerId: '', levelCode: '', groupBy: '' })
const report = useAdminRead()
const { data: details, loading, error, pages, go, search } = useAdminPage(adminSettlements)
const validationError = ref('')
const appliedGroup = ref('')
let applied = {}

function loadReport() {
  const params = paramsOf({ ...applied, groupBy: appliedGroup.value })
  return report.run(signal => api.get(`${adminSettlements}/fees`, { params, signal }))
}

function filter() {
  validationError.value = ''
  const sellerId = filters.sellerId.trim()
  if (sellerId && !positiveId(sellerId)) {
    validationError.value = '请输入有效的商家编号。'
    return
  }
  const from = filters.from ? new Date(filters.from + 'Z') : null
  const to = filters.to ? new Date(filters.to + 'Z') : null
  if ((from && Number.isNaN(+from)) || (to && Number.isNaN(+to)) || (from && to && from >= to)) {
    validationError.value = '请输入有效的 UTC 时间，开始时间须早于结束时间。'
    return
  }
  applied = paramsOf({ from: from?.toISOString(), to: to?.toISOString(), sellerId, levelCode: filters.levelCode })
  appliedGroup.value = filters.groupBy
  loadReport()
  search(applied)
}

onMounted(filter)
</script>

<template>
  <header class="management-page-header"><h1>平台结算</h1></header>
  <form class="seller-panel admin-filters" @submit.prevent="filter">
    <label>开始时间（UTC）<input v-model="filters.from" type="datetime-local"></label>
    <label>结束时间（UTC，不含）<input v-model="filters.to" type="datetime-local"></label>
    <label>商家编号<input v-model.trim="filters.sellerId" inputmode="numeric" maxlength="19" placeholder="全部商家"></label>
    <label>快照等级编号<input v-model.number="filters.levelCode" type="number" min="1" max="5" placeholder="全部等级"></label>
    <label>汇总分组<select v-model="filters.groupBy"><option value="">不分组</option><option value="SELLER">按商家</option><option value="LEVEL">按等级</option></select></label>
    <button class="button" type="submit">查询结算</button>
    <p v-if="validationError" class="notice error" role="alert">{{ validationError }}</p>
  </form>
  <section class="admin-top-gap" aria-labelledby="fees-heading">
    <h2 id="fees-heading">手续费汇总</h2>
    <AdminFeedback :loading="report.loading.value" :error="report.error.value" @retry="loadReport">
      <template v-if="report.data.value">
        <dl class="seller-metrics admin-totals">
          <div class="seller-panel"><dt>平台手续费</dt><dd>{{ adminMoney(report.data.value.totals.feeAmount) }}</dd></div>
          <div class="seller-panel"><dt>结算笔数</dt><dd>{{ report.data.value.totals.settlementCount }}</dd></div>
          <div class="seller-panel"><dt>已结算金额</dt><dd>{{ adminMoney(report.data.value.totals.settledAmount) }}</dd></div>
        </dl>
        <template v-if="appliedGroup">
          <WorkspaceTable v-if="report.data.value.groups.length" label="分组结算统计">
            <table class="admin-table">
              <thead><tr><th>{{ appliedGroup === 'SELLER' ? '商家编号' : '快照等级' }}</th><th>结算笔数</th><th>已结算金额</th><th>手续费</th></tr></thead>
              <tbody><tr v-for="group in report.data.value.groups" :key="group.sellerId ?? group.levelCode ?? 'no-level'">
                <th scope="row">{{ appliedGroup === 'SELLER' ? group.sellerId : group.levelCode ?? '无等级快照' }}</th>
                <td>{{ group.settlementCount }}</td><td>{{ adminMoney(group.settledAmount) }}</td><td>{{ adminMoney(group.feeAmount) }}</td>
              </tr></tbody>
            </table>
          </WorkspaceTable>
          <p v-else class="empty-panel">暂无分组结算记录。</p>
        </template>
      </template>
    </AdminFeedback>
  </section>
  <section class="admin-top-gap" aria-labelledby="settlement-details">
    <h2 id="settlement-details">订单结算明细</h2>
    <AdminFeedback :loading="loading" :error="error" @retry="go()">
      <template v-if="details">
        <p class="muted management-result-count" role="status">共 {{ details.total }} 笔</p>
        <WorkspaceTable v-if="details.items.length" label="订单结算明细">
          <table class="admin-table admin-settlements-table">
            <thead><tr>
              <th>结算 / 订单</th><th>商家</th><th>等级 / 费率快照</th><th>计费基数</th>
              <th>结算金额</th><th>实际手续费</th><th>商家净收入</th><th>结算时间</th>
            </tr></thead>
            <tbody><tr v-for="item in details.items" :key="item.settlementId">
              <th scope="row">#{{ item.settlementId }} / #{{ item.orderId }}</th>
              <td>#{{ item.sellerId }}</td>
              <td>{{ item.levelCodeSnapshot ?? '—' }} / {{ item.feeRateSnapshot == null ? '—' : percent(item.feeRateSnapshot) }}</td>
              <td>{{ adminMoney(item.feeBaseAmount) }}</td>
              <td>{{ adminMoney(item.settledAmount) }}</td>
              <td>{{ adminMoney(item.feeAmount) }}</td>
              <td>{{ adminMoney(item.sellerNetAmount) }}</td>
              <td>{{ utcLabel(item.settledAt) }}</td>
            </tr></tbody>
          </table>
        </WorkspaceTable>
        <p v-else class="empty-panel">没有符合条件的结算记录。</p>
        <PageNav :page="details.page" :pages="pages" label="结算分页" @change="go" />
      </template>
    </AdminFeedback>
  </section>
</template>
