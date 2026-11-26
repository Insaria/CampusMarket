<script setup>
import { computed } from 'vue'
import { sellerHistoryAllowed, session } from '../auth.js'
import WorkspaceLayout from '../components/WorkspaceLayout.vue'

const groups = computed(() => [
  {
    label: '经营管理',
    entries: [
      { name: 'seller', label: '经营概览' },
      ...(session.access === 'MERCHANT' ? [
        { name: 'seller-products', label: '我的商品', pages: ['seller-product-new', 'seller-product-edit'] },
      ] : []),
      ...(sellerHistoryAllowed() ? [
        { name: 'seller-orders', label: '收到的订单', pages: ['seller-order'] },
        { name: 'seller-returns', label: '退货申请', pages: ['seller-return'] },
      ] : []),
    ],
  },
  {
    label: '资金与沟通',
    entries: [
      { name: 'wallet', label: '钱包与流水' },
      { name: 'reputation', label: '评价与信誉' },
      { name: 'notifications', label: '站内消息' },
    ],
  },
])
</script>

<template>
  <WorkspaceLayout title="卖家中心" home="seller" :groups="groups" class="seller-management">
    <RouterView />
  </WorkspaceLayout>
</template>
