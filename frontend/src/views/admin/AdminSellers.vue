<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminSellers, useAdminPage, useAdminRead } from '../../admin.js'
import { api } from '../../api.js'
import { operationLabels, reviewLabels, sellerLabels } from '../../seller.js'
import AdminDocument from '../../components/AdminDocument.vue'
import AdminFeedback from '../../components/AdminFeedback.vue'
import PageNav from '../../components/PageNav.vue'
import StatusBadge from '../../components/StatusBadge.vue'
import WorkspaceTable from '../../components/WorkspaceTable.vue'
import { useWorkspaceConfirm } from '../../workspace.js'

const status = ref('PENDING')
const includePersonal = ref(false)
const closureReason = ref('')
const { data: result, loading, error, pages, go, search } = useAdminPage(adminSellers)
const detail = useAdminRead()
const levels = useAdminRead()
const enabled = computed(() => levels.data.value?.filter(level => level.enabled) ?? [])
const selection = ref(null)
const levelCode = ref('')
const reason = ref('')
const busy = ref(false)
const actionError = ref('')
const message = ref('')
const confirm = useWorkspaceConfirm()

function loadLevels() {
  return levels.run(signal => api.get(`${adminSellers}/levels`, { signal }))
}

function show(id) {
  selection.value = id
  levelCode.value = ''
  reason.value = ''
  closureReason.value = ''
  actionError.value = ''
  message.value = ''
  detail.run(signal => api.get(`${adminSellers}/${id}`, { signal }))
}

function close() {
  selection.value = null
  detail.reset()
}

function filter() {
  close()
  search({ reviewStatus: status.value, includePersonal: includePersonal.value })
}

async function review(decision) {
  const seller = detail.data.value
  const approval = decision === 'APPROVED'
  const label = approval ? '批准' : '拒绝'
  if (busy.value || seller?.reviewStatus !== 'PENDING') return
  if (!approval && !reason.value) {
    actionError.value = '拒绝商家时请填写审核意见。'
    return
  }
  if (approval && !enabled.value.some(level => level.levelCode === levelCode.value)) {
    actionError.value = '请选择启用的商家等级。'
    return
  }
  if (!(await confirm(`确认${label}「${seller.shopName}」的商家申请？`, { label: label + '申请' }))) return
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    const input = { decision, reviewReason: reason.value || null }
    if (approval) input.levelCode = levelCode.value
    detail.data.value = await api.patch(`${adminSellers}/${seller.id}/review`, input)
    message.value = `已${label}商家申请。`
    await go()
  } catch (failure) {
    actionError.value = failure.message
  } finally {
    busy.value = false
  }
}

async function changeOperation(closing) {
  const seller = detail.data.value
  if (busy.value || seller?.reviewStatus !== 'APPROVED') return
  const label = closing ? '关闭店铺' : '重新开放'
  if (closing && !closureReason.value) {
    actionError.value = '请填写关闭原因。'
    return
  }
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    if (!await confirm(`确认${label}「${seller.shopName}」？`, {
      label,
      detail: closing ? '商品将停止展示和销售，已有订单仍可处理。'
        : '仅仍符合公开条件的原上架商品会恢复展示。',
    })) return
    detail.data.value = await api.patch(`${adminSellers}/${seller.id}/operation`, {
      status: closing ? 'SUSPENDED' : 'ACTIVE', reason: closing ? closureReason.value : null,
    })
    closureReason.value = ''
    message.value = closing ? '店铺已关闭，卖家已收到通知。' : '店铺已重新开放，卖家已收到通知。'
    await go()
  } catch (failure) {
    actionError.value = failure.message
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  filter()
  loadLevels()
})
</script>

<template>
  <header class="management-page-header">
    <h1>商家审核</h1><p class="muted">正式商家资质审核与店铺经营管理</p>
  </header>
  <form class="seller-panel admin-filters" @submit.prevent="filter">
    <label>审核状态
      <select v-model="status">
        <option value="">全部</option><option v-for="code in ['PENDING', 'APPROVED', 'REJECTED']" :key="code" :value="code">{{ reviewLabels[code] }}</option>
      </select>
    </label>
    <label class="seller-check"><input v-model="includePersonal" type="checkbox" :disabled="busy"> 包含个人卖家</label>
    <button class="button" type="submit" :disabled="busy">查询</button>
  </form>
  <AdminFeedback :loading="loading" :error="error" @retry="go()">
    <template v-if="result">
      <p class="management-result-count muted" role="status">共 {{ result.total }} 家商家</p>
      <WorkspaceTable v-if="result.items.length" label="商家审核列表">
        <table class="admin-table">
          <thead><tr><th>店铺</th><th>类型</th><th>审核</th><th>经营状态</th><th>操作</th></tr></thead>
          <tbody><tr v-for="seller in result.items" :key="seller.id">
            <th scope="row">{{ seller.shopName }}</th><td>{{ sellerLabels[seller.sellerType] || '正式商家' }}</td>
            <td><StatusBadge :state="seller.reviewStatus" :label="reviewLabels[seller.reviewStatus]" /></td>
            <td><StatusBadge :state="seller.operationStatus" :label="operationLabels[seller.operationStatus]" /></td>
            <td><button class="button button-outline" type="button" :disabled="busy" @click="show(seller.id)">查看资料</button></td>
          </tr></tbody>
        </table>
      </WorkspaceTable>
      <p v-else class="empty-panel">没有符合条件的店铺。</p>
      <PageNav v-if="!busy" :page="result.page" :pages="pages" label="商家审核分页" @change="go" />
    </template>
  </AdminFeedback>
  <section v-if="selection !== null" class="seller-panel admin-detail" aria-labelledby="admin-seller-detail">
    <div class="seller-heading">
      <h2 id="admin-seller-detail">商家资料</h2>
      <button class="button button-outline" type="button" :disabled="busy" @click="close">关闭</button>
    </div>
    <AdminFeedback :loading="detail.loading.value" :error="detail.error.value" @retry="show(selection)">
      <template v-if="detail.data.value">
        <dl class="seller-metrics">
          <div><dt>店铺</dt><dd>{{ detail.data.value.shopName }}</dd></div>
          <div><dt>账号编号</dt><dd>{{ detail.data.value.userId }}</dd></div>
          <div><dt>商家类型</dt><dd>{{ sellerLabels[detail.data.value.sellerType] || '正式商家' }}</dd></div>
          <div><dt>审核 / 经营</dt><dd>{{ reviewLabels[detail.data.value.reviewStatus] }} / {{ operationLabels[detail.data.value.operationStatus] }}</dd></div>
          <div><dt>等级编号</dt><dd>{{ detail.data.value.levelCode ?? '未分配' }}</dd></div>
        </dl>
        <p v-if="detail.data.value.shopDescription" class="product-description">{{ detail.data.value.shopDescription }}</p>
        <p v-if="detail.data.value.reviewReason" class="product-description">审核意见：{{ detail.data.value.reviewReason }}</p>
        <div v-if="detail.data.value.sellerType !== 'PERSONAL'" :key="detail.data.value.id" class="admin-documents">
          <AdminDocument :seller-id="detail.data.value.id" kind="BUSINESS_LICENSE" label="营业执照" />
          <AdminDocument :seller-id="detail.data.value.id" kind="IDENTITY_DOCUMENT" label="身份证明" />
        </div>
        <div v-if="detail.data.value.reviewStatus === 'APPROVED' && ['ACTIVE', 'SUSPENDED'].includes(detail.data.value.operationStatus)"
          class="seller-review">
          <h3>店铺经营</h3>
          <template v-if="detail.data.value.operationStatus === 'ACTIVE'">
            <label>关闭原因<textarea v-model.trim="closureReason" maxlength="500" rows="3" :disabled="busy"></textarea></label>
            <button class="button button-outline" type="button" :disabled="busy" @click="changeOperation(true)">关闭店铺</button>
          </template>
          <template v-else>
            <p v-if="detail.data.value.closureReason" class="product-description">关闭原因：{{ detail.data.value.closureReason }}</p>
            <button class="button" type="button" :disabled="busy" @click="changeOperation(false)">重新开放店铺</button>
          </template>
        </div>
        <template v-if="detail.data.value.reviewStatus === 'PENDING'">
          <label>审核意见（拒绝时必填）
            <textarea v-model.trim="reason" maxlength="500" rows="3" :disabled="busy"></textarea>
          </label>
          <AdminFeedback :loading="levels.loading.value" :error="levels.error.value" @retry="loadLevels">
            <label>批准等级
              <select v-model="levelCode" :disabled="busy">
                <option value="">请选择启用的等级</option>
                <option v-for="level in enabled" :key="level.id" :value="level.levelCode">{{ level.levelName }}</option>
              </select>
            </label>
            <p v-if="!enabled.length" class="muted">当前没有已启用的等级，暂不可批准。</p>
          </AdminFeedback>
          <div class="actions admin-top-gap">
            <button class="button" type="button" :disabled="busy || levelCode === ''" @click="review('APPROVED')">批准</button>
            <button class="button button-outline" type="button" :disabled="busy" @click="review('REJECTED')">拒绝</button>
          </div>
        </template>
      </template>
    </AdminFeedback>
    <p v-if="actionError" class="notice error admin-top-gap" role="alert">{{ actionError }}</p>
    <p v-if="message" class="notice success admin-top-gap" role="status">{{ message }}</p>
  </section>
</template>
