<script setup>
import { onMounted, onUnmounted } from 'vue'
import { adminNavigation, adminProducts, adminSellers, adminUsers, useAdminRead } from '../../admin.js'
import { api } from '../../api.js'

const modules = adminNavigation.slice(1)
const userCount = useAdminRead()
const sellerCount = useAdminRead()
const productCount = useAdminRead()
const counts = { 'admin-users': userCount, 'admin-sellers': sellerCount, 'admin-products': productCount }

function reviewCount(value) {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('待审数量响应无效。')
  return value
}

function loadPending() {
  userCount.run(async signal => reviewCount(await api.get(`${adminUsers}/pending/count`, { signal })))
  sellerCount.run(async signal => reviewCount((await api.get(adminSellers, {
    params: { reviewStatus: 'PENDING', size: 1 }, signal,
  })).total))
  productCount.run(async signal => reviewCount(await api.get(`${adminProducts}/pending/count`, { signal })))
}

onMounted(() => {
  loadPending()
  window.addEventListener('focus', loadPending)
})
onUnmounted(() => window.removeEventListener('focus', loadPending))
</script>

<template>
  <header class="management-page-header"><h1>管理工作台</h1></header>
  <div class="admin-module-grid">
    <RouterLink v-for="entry in modules" :key="entry.name" class="seller-panel admin-module" :to="{ name: entry.name }">
      <span>{{ entry.label }}</span>
      <span v-if="counts[entry.name]?.data.value != null" class="admin-module-count">
        <strong class="management-value">{{ counts[entry.name].data.value }}</strong> 待审核
      </span>
      <small v-else-if="counts[entry.name]?.loading.value" role="status">正在读取待审数量…</small>
      <small v-else-if="counts[entry.name]?.error.value" role="status">待审数量暂不可用</small>
      <span aria-hidden="true" class="admin-module-arrow">↗</span>
    </RouterLink>
  </div>
  <button v-if="Object.values(counts).some(count => count.error.value)" class="button button-outline admin-top-gap"
    type="button" @click="loadPending">刷新待审数量</button>
</template>
