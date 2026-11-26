<script setup>
import { onMounted, reactive, ref } from 'vue'
import { accountLabels, adminUsers, useAdminPage, useAdminRead, utcLabel } from '../../admin.js'
import { api } from '../../api.js'
import { reviewLabels } from '../../seller.js'
import AdminFeedback from '../../components/AdminFeedback.vue'
import AdminWalletRecharge from '../../components/AdminWalletRecharge.vue'
import PageNav from '../../components/PageNav.vue'
import StatusBadge from '../../components/StatusBadge.vue'
import WorkspaceTable from '../../components/WorkspaceTable.vue'
import { useWorkspaceConfirm } from '../../workspace.js'

const filters = reactive({ loginName: '', registrationStatus: '', accountStatus: '' })
const { data: result, loading, error, pages, go, search } = useAdminPage(adminUsers)
const detail = useAdminRead()
const selection = ref(null)
const busy = ref(false)
const actionError = ref('')
const message = ref('')
const profile = ref(null)
const confirm = useWorkspaceConfirm()

function show(id) {
  selection.value = id
  profile.value = null
  actionError.value = ''
  message.value = ''
  detail.run(signal => api.get(`${adminUsers}/${id}`, { signal }))
}

function close() {
  selection.value = null
  detail.reset()
}

function filter() {
  close()
  search(filters)
}

async function change(kind, value, label) {
  const user = detail.data.value
  if (busy.value || !user) return
  if (!(await confirm(`确认${label}「${user.loginName}」？`, { label }))) return
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    const updated = await api.patch(`${adminUsers}/${user.id}/${kind}`, kind === 'status' ? { status: value } : { decision: value })
    detail.data.value = updated
    message.value = `${label}操作已完成。`
    await go()
  } catch (failure) {
    actionError.value = failure.message
  } finally {
    busy.value = false
  }
}

function editProfile() {
  const { realName, phone, email, city, genderCode } = detail.data.value
  profile.value = { realName, phone, email, city: city ?? '', genderCode: genderCode ?? '' }
}

async function saveProfile(remove = false) {
  if (busy.value || (remove && !(await confirm('确认逻辑删除账号？', {
    label: '确认删除', detail: `「${detail.data.value.loginName}」删除后无法登录。`,
  })))) return
  const path = `${adminUsers}/${detail.data.value.id}`
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    detail.data.value = remove ? await api.delete(path) : await api.patch(`${path}/profile`, {
      ...profile.value, city: profile.value.city || null, genderCode: profile.value.genderCode || null,
    })
    profile.value = null
    message.value = remove ? '账号已逻辑删除。' : '用户资料已保存。'
    await go()
  } catch (failure) {
    actionError.value = failure.message
  } finally {
    busy.value = false
  }
}

onMounted(filter)
</script>

<template>
  <header class="management-page-header"><h1>用户管理</h1></header>
  <form class="seller-panel admin-filters" @submit.prevent="filter">
    <label>登录名<input v-model.trim="filters.loginName" maxlength="64" placeholder="输入登录名片段"></label>
    <label>注册审核
      <select v-model="filters.registrationStatus">
        <option value="">全部</option><option v-for="code in ['PENDING', 'APPROVED', 'REJECTED']" :key="code" :value="code">{{ reviewLabels[code] }}</option>
      </select>
    </label>
    <label>账号状态
      <select v-model="filters.accountStatus">
        <option value="">全部</option><option v-for="(label, code) in accountLabels" :key="code" :value="code">{{ label }}</option>
      </select>
    </label>
    <button class="button" type="submit" :disabled="busy">查询</button>
  </form>
  <AdminFeedback :loading="loading" :error="error" @retry="go()">
    <template v-if="result">
      <p class="management-result-count muted" role="status">共 {{ result.total }} 位用户</p>
      <WorkspaceTable v-if="result.items.length" label="用户列表">
        <table class="admin-table">
          <thead><tr><th>登录名</th><th>角色</th><th>注册审核</th><th>账号状态</th><th>操作</th></tr></thead>
          <tbody><tr v-for="user in result.items" :key="user.id">
            <th scope="row">{{ user.loginName }}</th><td>{{ user.roleCode === 'ADMIN' ? '管理员' : '普通用户' }}</td>
            <td><StatusBadge :state="user.registrationStatus" :label="reviewLabels[user.registrationStatus]" /></td>
            <td><StatusBadge :state="user.accountStatus" :label="accountLabels[user.accountStatus]" /></td>
            <td><button class="button button-outline" type="button" :disabled="busy" @click="show(user.id)">查看</button></td>
          </tr></tbody>
        </table>
      </WorkspaceTable>
      <p v-else class="empty-panel">没有符合条件的用户。</p>
      <PageNav v-if="!busy" :page="result.page" :pages="pages" label="用户分页" @change="go" />
    </template>
  </AdminFeedback>
  <section v-if="selection !== null" class="seller-panel admin-detail" aria-labelledby="admin-user-detail">
    <div class="seller-heading">
      <h2 id="admin-user-detail">用户资料</h2>
      <button class="button button-outline" type="button" :disabled="busy" @click="close">关闭</button>
    </div>
    <AdminFeedback :loading="detail.loading.value" :error="detail.error.value" @retry="show(selection)">
      <template v-if="detail.data.value">
        <dl class="seller-metrics">
          <div><dt>编号</dt><dd>{{ detail.data.value.id }}</dd></div>
          <div><dt>登录名</dt><dd>{{ detail.data.value.loginName }}</dd></div>
          <div><dt>姓名</dt><dd>{{ detail.data.value.realName || '—' }}</dd></div>
          <div><dt>手机号</dt><dd>{{ detail.data.value.phone || '—' }}</dd></div>
          <div><dt>邮箱</dt><dd>{{ detail.data.value.email || '—' }}</dd></div>
          <div><dt>城市</dt><dd>{{ detail.data.value.city || '—' }}</dd></div>
          <div><dt>性别代码</dt><dd>{{ detail.data.value.genderCode || '—' }}</dd></div>
          <div><dt>注册时间</dt><dd>{{ utcLabel(detail.data.value.createdAt) }}</dd></div>
          <div><dt>注册审核</dt><dd>{{ reviewLabels[detail.data.value.registrationStatus] }}</dd></div>
          <div><dt>账号状态</dt><dd>{{ accountLabels[detail.data.value.accountStatus] }}</dd></div>
        </dl>
        <div class="actions">
          <template v-if="detail.data.value.roleCode === 'USER' && detail.data.value.accountStatus !== 'DELETED' && detail.data.value.registrationStatus === 'PENDING'">
            <button class="button" type="button" :disabled="busy" @click="change('registration', 'APPROVED', '批准注册')">批准注册</button>
            <button class="button button-outline" type="button" :disabled="busy" @click="change('registration', 'REJECTED', '拒绝注册')">拒绝注册</button>
          </template>
          <template v-if="detail.data.value.roleCode === 'USER' && detail.data.value.accountStatus !== 'DELETED'">
            <button class="button button-outline" type="button" :disabled="busy" @click="editProfile">编辑资料</button>
            <button v-if="detail.data.value.accountStatus === 'ENABLED'" class="button button-outline" type="button" :disabled="busy"
              @click="change('status', 'DISABLED', '停用账号')">停用账号</button>
            <template v-else>
              <button class="button" type="button" :disabled="busy" @click="change('status', 'ENABLED', '启用账号')">启用账号</button>
              <button class="button button-outline" type="button" :disabled="busy" @click="saveProfile(true)">逻辑删除</button>
            </template>
          </template>
        </div>
        <form v-if="profile" class="admin-filters admin-top-gap" @submit.prevent="saveProfile()">
          <label>姓名<input v-model.trim="profile.realName" required maxlength="64"></label>
          <label>手机号<input v-model.trim="profile.phone" required pattern="\+[1-9][0-9]{1,14}"></label>
          <label>邮箱<input v-model.trim="profile.email" required maxlength="254" type="email"></label>
          <label>城市<input v-model.trim="profile.city" maxlength="64"></label>
          <label>性别代码<input v-model.trim="profile.genderCode" maxlength="16" pattern="[A-Z][A-Z0-9_]{0,15}"></label>
          <button class="button" type="submit" :disabled="busy">保存资料</button>
          <button class="button button-outline" type="button" :disabled="busy" @click="profile = null">取消编辑</button>
        </form>
        <AdminWalletRecharge v-if="detail.data.value.roleCode === 'USER' && detail.data.value.accountStatus !== 'DELETED'"
          :key="detail.data.value.id" :user="detail.data.value" />
      </template>
    </AdminFeedback>
    <p v-if="actionError" class="notice error admin-top-gap" role="alert">{{ actionError }}</p>
    <p v-if="message" class="notice success admin-top-gap" role="status">{{ message }}</p>
  </section>
</template>
