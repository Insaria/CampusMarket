<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminMoney, adminSellers, useAdminPage, useAdminRead } from '../../admin.js'
import { api } from '../../api.js'
import { percent } from '../../catalog.js'
import AdminFeedback from '../../components/AdminFeedback.vue'
import PageNav from '../../components/PageNav.vue'
import StatusBadge from '../../components/StatusBadge.vue'
import WorkspaceTable from '../../components/WorkspaceTable.vue'
import { useWorkspaceConfirm } from '../../workspace.js'

const levels = useAdminRead()
const metrics = useAdminRead()
const { data: result, loading, error, pages, go, search } = useAdminPage(adminSellers)
const selection = ref(null)
const levelCode = ref('')
const reason = ref('')
const busy = ref(false)
const actionError = ref('')
const message = ref('')
const confirm = useWorkspaceConfirm()
const choices = computed(() => levels.data.value?.filter(
  level => level.enabled && level.levelCode !== metrics.data.value?.seller.levelCode) ?? [])

function loadLevels() {
  return levels.run(signal => api.get(`${adminSellers}/levels`, { signal }))
}

function show(id) {
  selection.value = id
  levelCode.value = ''
  reason.value = ''
  actionError.value = ''
  message.value = ''
  metrics.run(signal => api.get(`${adminSellers}/${id}/metrics`, { signal }))
}

function close() {
  selection.value = null
  metrics.reset()
}

async function submit() {
  const current = metrics.data.value
  const target = choices.value.find(level => level.levelCode === levelCode.value)
  if (busy.value || current?.seller.reviewStatus !== 'APPROVED') return
  if (!reason.value || !target) {
    actionError.value = '请选择已启用的新等级并填写调级原因。'
    return
  }
  if (!(await confirm(`确认将「${current.seller.shopName}」调整为「${target.levelName}」？`, {
    label: '确认调级', detail: `新手续费率为 ${percent(target.feeRate)}。`,
  }))) return
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    const seller = await api.patch(`${adminSellers}/${current.seller.id}/level`, { levelCode: target.levelCode, reason: reason.value })
    metrics.data.value = { ...current, seller }
    levelCode.value = ''
    reason.value = ''
    message.value = '商家等级已更新。'
    await go()
  } catch (failure) {
    actionError.value = failure.message
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  loadLevels()
  search({ reviewStatus: 'APPROVED' })
})
</script>

<template>
  <header class="management-page-header"><h1>商家等级</h1></header>
  <AdminFeedback :loading="levels.loading.value" :error="levels.error.value" @retry="loadLevels">
    <WorkspaceTable v-if="levels.data.value?.length" label="平台等级与费率">
      <table class="admin-table">
        <thead><tr><th>等级编号</th><th>等级名称</th><th>手续费率</th><th>状态</th></tr></thead>
        <tbody><tr v-for="level in levels.data.value" :key="level.id">
          <th scope="row">{{ level.levelCode }}</th><td>{{ level.levelName }}</td>
          <td>{{ percent(level.feeRate) }}</td><td><StatusBadge :state="level.enabled ? 'ENABLED' : 'DISABLED'"
            :label="level.enabled ? '已启用' : '已停用'" /></td>
        </tr></tbody>
      </table>
    </WorkspaceTable>
    <p v-else class="empty-panel">后端尚无等级数据。</p>
  </AdminFeedback>

  <h2 class="admin-top-gap">已审核正式商家</h2>
  <AdminFeedback :loading="loading" :error="error" @retry="go()">
    <template v-if="result">
      <p class="muted management-result-count">共 {{ result.total }} 家商家</p>
      <WorkspaceTable v-if="result.items.length" label="已审核正式商家">
        <table class="admin-table">
          <thead><tr><th>店铺</th><th>商家编号</th><th>当前等级</th><th>操作</th></tr></thead>
          <tbody><tr v-for="seller in result.items" :key="seller.id">
            <th scope="row">{{ seller.shopName }}</th><td>{{ seller.id }}</td>
            <td>{{ levels.data.value?.find(level => level.levelCode === seller.levelCode)?.levelName ?? seller.levelCode ?? '未分配' }}</td>
            <td><button class="button button-outline" type="button" :disabled="busy" @click="show(seller.id)">查看与调级</button></td>
          </tr></tbody>
        </table>
      </WorkspaceTable>
      <p v-else class="empty-panel">暂无已审核的正式商家。</p>
      <PageNav v-if="!busy" :page="result.page" :pages="pages" label="商家等级分页" @change="go" />
    </template>
  </AdminFeedback>
  <section v-if="selection !== null" class="seller-panel admin-detail" aria-labelledby="admin-level-detail">
    <div class="seller-heading">
      <h2 id="admin-level-detail">经营数据与调级</h2>
      <button class="button button-outline" type="button" :disabled="busy" @click="close">关闭</button>
    </div>
    <AdminFeedback :loading="metrics.loading.value" :error="metrics.error.value" @retry="show(selection)">
      <template v-if="metrics.data.value">
        <dl class="seller-metrics">
          <div><dt>店铺</dt><dd>{{ metrics.data.value.seller.shopName }}</dd></div>
          <div><dt>当前等级编号</dt><dd>{{ metrics.data.value.seller.levelCode ?? '未分配' }}</dd></div>
          <div><dt>已结算交易额</dt><dd>{{ adminMoney(metrics.data.value.settlements.settledAmount) }}</dd></div>
          <div><dt>已结算笔数</dt><dd>{{ metrics.data.value.settlements.settlementCount }}</dd></div>
          <div><dt>商家满意度</dt><dd>{{ metrics.data.value.satisfaction.positiveRate == null ? '暂无星级' : percent(metrics.data.value.satisfaction.positiveRate) }}</dd></div>
        </dl>
        <form v-if="metrics.data.value.seller.reviewStatus === 'APPROVED' && choices.length" class="admin-level-form" @submit.prevent="submit">
          <label>新等级<select v-model="levelCode" required :disabled="busy">
            <option value="" disabled>选择已启用的等级</option>
            <option v-for="level in choices" :key="level.id" :value="level.levelCode">{{ level.levelName }} · {{ percent(level.feeRate) }}</option>
          </select></label>
          <label>调级原因<textarea v-model.trim="reason" maxlength="500" required rows="3" :disabled="busy"></textarea></label>
          <button class="button" type="submit" :disabled="busy">确认调级</button>
        </form>
        <p v-else class="muted">当前没有可选的新等级。</p>
      </template>
    </AdminFeedback>
    <p v-if="actionError" class="notice error admin-top-gap" role="alert">{{ actionError }}</p>
    <p v-if="message" class="notice success admin-top-gap" role="status">{{ message }}</p>
  </section>
</template>
