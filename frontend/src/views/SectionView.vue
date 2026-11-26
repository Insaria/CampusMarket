<script setup>
import { session } from '../auth.js'

defineProps({ title: { type: String, required: true }, showAccount: Boolean })

const roles = { USER: '普通用户', MERCHANT: '卖家', ADMIN: '管理员' }
</script>

<template>
  <section class="auth-card">
    <h1>{{ title }}</h1>
    <dl v-if="showAccount && session.user">
      <dt>用户编号</dt>
      <dd>{{ session.user.userId }}</dd>
      <dt>当前身份</dt>
      <dd>{{ roles[session.access] || session.access }}</dd>
    </dl>
    <p v-else-if="title !== '页面未找到'" class="muted">该业务页面将在后续阶段开放。</p>
    <div v-if="showAccount && session.user?.roleCode === 'USER'" class="actions account-links">
      <RouterLink class="button button-outline" :to="{ name: 'reputation' }">我的信誉</RouterLink>
      <RouterLink class="button button-outline" :to="{ name: 'wallet' }">我的钱包</RouterLink>
      <RouterLink class="button button-outline" :to="{ name: 'points' }">我的积分</RouterLink>
      <RouterLink class="button button-outline" :to="{ name: 'pending-payments' }">待支付订单</RouterLink>
      <RouterLink class="button" :to="{ name: 'orders' }">我的订单</RouterLink>
    </div>
    <RouterLink class="button button-outline" :to="{ name: 'home' }">返回首页</RouterLink>
  </section>
</template>
