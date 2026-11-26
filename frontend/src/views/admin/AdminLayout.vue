<script setup>
import { adminNavigation } from '../../admin.js'
import WorkspaceLayout from '../../components/WorkspaceLayout.vue'

const groups = [
  { entries: adminNavigation.slice(0, 1) },
  { label: '运营与审核', entries: adminNavigation.slice(1, 4) },
  { label: '等级与结算', entries: adminNavigation.slice(4) },
]
</script>

<template>
  <WorkspaceLayout title="平台管理" home="admin" :groups="groups" class="admin-management">
    <div class="admin-content"><RouterView /></div>
  </WorkspaceLayout>
</template>
