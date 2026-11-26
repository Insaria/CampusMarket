<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout, session } from './auth.js'
import { refreshUnread, unreadCount } from './notifications.js'

import ConfirmationProvider from './components/ConfirmationProvider.vue'

const router = useRouter()
const route = useRoute()
const accountMenu = ref(null)
const leaving = ref(false)
const error = ref('')
let unreadTimer

function closeAccountMenu(restoreFocus = false) {
  if (!accountMenu.value?.open) return
  accountMenu.value.open = false
  if (restoreFocus) accountMenu.value.querySelector('summary')?.focus()
}

watch(() => route.fullPath, () => closeAccountMenu())

watch(() => session.user?.userId, () => {
  unreadCount.value = null
  refreshUnread()
}, { immediate: true })

onMounted(() => {
  window.addEventListener('focus', refreshUnread)
  unreadTimer = window.setInterval(() => {
    if (!document.hidden && session.user) refreshUnread()
  }, 30000)
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshUnread)
  window.clearInterval(unreadTimer)
})

async function signOut() {
  leaving.value = true
  error.value = ''
  try {
    await logout()
    await router.replace({ name: 'home' })
  } catch (failure) {
    error.value = failure.message
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <div class="site">
    <header class="site-header">
      <div class="container header-inner">
        <RouterLink class="brand" :to="{ name: 'home' }">
          <span class="brand-mark" aria-hidden="true">CM</span>
          <span class="brand-name">CampusMarket<small>校园二手交易</small></span>
        </RouterLink>
        <nav class="primary-nav" aria-label="主导航">
          <RouterLink :to="{ name: 'home' }">首页</RouterLink>
          <RouterLink :to="{ name: 'categories' }">商品分类</RouterLink>
          <RouterLink v-if="session.ready && session.user?.roleCode !== 'ADMIN'" :to="{ name: 'cart' }">购物车</RouterLink>
        </nav>
        <div class="header-account">
          <template v-if="session.user">
            <RouterLink class="header-link" :to="{ name: 'notifications' }">
              消息 <span v-if="unreadCount > 0" class="unread-badge" :aria-label="unreadCount + ' 条未读'">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
            </RouterLink>
            <details ref="accountMenu" class="account-menu" @keydown.esc="closeAccountMenu(true)">
              <summary>我的账户 <span aria-hidden="true">⌄</span></summary>
              <nav class="account-links-menu" aria-label="账户导航" @click="closeAccountMenu()">
                <RouterLink :to="{ name: 'account' }">个人中心</RouterLink>
                <template v-if="session.user.roleCode === 'USER'">
                  <RouterLink :to="{ name: 'orders' }">我的订单</RouterLink>
                  <RouterLink :to="{ name: 'wallet' }">我的钱包</RouterLink>
                  <RouterLink :to="{ name: 'points' }">我的积分</RouterLink>
                  <RouterLink :to="{ name: 'pending-payments' }">待支付订单</RouterLink>
                  <RouterLink :to="{ name: 'seller' }">卖家中心</RouterLink>
                </template>
                <RouterLink v-if="session.access === 'ADMIN'" :to="{ name: 'admin' }">管理员后台</RouterLink>
                <button class="account-signout" type="button" :disabled="leaving" @click.stop="signOut">
                  {{ leaving ? '正在退出…' : '退出登录' }}
                </button>
              </nav>
            </details>
          </template>
          <template v-else-if="session.ready">
            <RouterLink class="header-link" :to="{ name: 'login' }">登录</RouterLink>
            <RouterLink class="button" :to="{ name: 'register' }">注册</RouterLink>
          </template>
        </div>
      </div>
    </header>
    <main class="container main">
      <p v-if="error || session.error" class="notice error" role="alert">{{ error || session.error }}</p>
      <ConfirmationProvider><RouterView /></ConfirmationProvider>
    </main>
    <footer class="site-footer">
      <div class="container footer-inner">
        <RouterLink class="footer-brand" :to="{ name: 'home' }">CampusMarket</RouterLink>
        <RouterLink :to="{ name: 'categories' }">浏览商品</RouterLink>
        <RouterLink :to="{ name: 'sold-products' }">已售商品</RouterLink>
      </div>
    </footer>
  </div>
</template>
