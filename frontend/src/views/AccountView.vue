<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api.js'
import { sellerHistoryAllowed, session } from '../auth.js'
import { unreadCount } from '../notifications.js'
import { useOrderRead } from '../orders.js'
import { operationLabels, reviewLabels, sellerLabels } from '../seller.js'
import PageNav from '../components/PageNav.vue'
import ProductCard from '../components/ProductCard.vue'

const route = useRoute()
const user = computed(() => session.user)
const isUser = computed(() => user.value?.roleCode === 'USER')
const showFavorites = computed(() => isUser.value && route.query.tab === 'favorites')
const page = ref(1)
const retry = ref(0)
const sellerRetry = ref(0)
const profileRetry = ref(0)
const profile = useOrderRead([user, profileRetry], ([current], signal) =>
  current ? api.get('/users/me/profile', { signal }) : null)
const details = profile.data
const profileLoading = profile.loading
const profileError = profile.error
const genderLabels = { MALE: '男', FEMALE: '女', OTHER: '其他' }
const seller = useOrderRead([user, sellerRetry], async ([current], signal) => {
  if (current?.roleCode !== 'USER') return null
  try {
    return await api.get('/sellers/me', { signal })
  } catch (failure) {
    if (failure.code === 'SELLER_NOT_FOUND') return null
    throw failure
  }
})

const favorites = useOrderRead([user, showFavorites, page, retry], async ([current, selected, number], signal) => {
  if (current?.roleCode !== 'USER' || !selected) return null
  const result = await api.get('/favorites', { params: { page: number, size: 12 }, signal })
  if (!signal.aborted && number > 1 && !result.items.length) page.value = Math.max(1, Math.ceil(result.total / result.size))
  return result
})
watch(showFavorites, () => { page.value = 1 })

const shortcuts = [
  ['orders', '我的订单'], ['cart', '购物车'], ['wallet', '我的钱包'],
  ['points', '我的积分'], ['pending-payments', '待支付订单'], ['reputation', '我的信誉'],
]
</script>

<template>
  <section v-if="user" class="account-page" aria-labelledby="account-title">
    <header class="stage72-page-header"><h1 id="account-title">个人中心</h1></header>
    <div class="account-layout">
      <aside class="seller-panel account-profile" aria-labelledby="account-profile-title">
        <h2 id="account-profile-title">账号资料</h2>
        <dl>
          <div><dt>用户编号</dt><dd>{{ user.userId }}</dd></div>
          <div><dt>当前角色</dt><dd>{{ user.roleCode === 'ADMIN' ? '管理员' : sellerHistoryAllowed() ? '卖家' : '普通用户' }}</dd></div>
          <template v-if="details">
            <div><dt>登录名</dt><dd>{{ details.loginName }}</dd></div>
            <div><dt>姓名</dt><dd>{{ details.realName }}</dd></div>
            <div><dt>手机</dt><dd>{{ details.phone }}</dd></div>
            <div><dt>邮箱</dt><dd>{{ details.email }}</dd></div>
            <div><dt>城市</dt><dd>{{ details.city || '—' }}</dd></div>
            <div><dt>性别</dt><dd>{{ genderLabels[details.genderCode] ?? details.genderCode ?? '—' }}</dd></div>
            <div><dt>注册审核</dt><dd><span class="status-tag" :data-state="details.registrationStatus">{{ details.registrationStatus === 'APPROVED' ? '已通过' : details.registrationStatus }}</span></dd></div>
            <div><dt>账号状态</dt><dd><span class="status-tag" :data-state="details.accountStatus">{{ details.accountStatus === 'ENABLED' ? '正常' : details.accountStatus }}</span></dd></div>
          </template>
        </dl>
        <p v-if="profileLoading" class="muted" role="status">正在读取个人资料…</p>
        <div v-else-if="profileError" class="notice error" role="alert">
          {{ profileError }} <button type="button" class="text-button" @click="profileRetry++">重试</button>
        </div>
      </aside>

      <div class="account-content">
        <section class="seller-panel" aria-labelledby="account-shortcuts-title">
          <h2 id="account-shortcuts-title">常用功能</h2>
          <div class="account-shortcuts">
            <template v-if="isUser">
              <RouterLink v-for="[name, label] in shortcuts" :key="name" :to="{ name }">{{ label }} <span aria-hidden="true">→</span></RouterLink>
              <RouterLink :to="{ name: 'account', query: { tab: 'favorites' }, hash: '#favorites' }"
                :aria-current="showFavorites ? 'location' : undefined">我的收藏 <span aria-hidden="true">→</span></RouterLink>
              <RouterLink :to="{ name: 'seller' }">卖家中心 <span aria-hidden="true">→</span></RouterLink>
            </template>
            <RouterLink v-if="session.access === 'ADMIN'" :to="{ name: 'admin' }">管理员后台 <span aria-hidden="true">→</span></RouterLink>
            <RouterLink :to="{ name: 'notifications' }">站内消息
              <span v-if="unreadCount > 0" class="unread-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
              <span v-else aria-hidden="true">→</span>
            </RouterLink>
          </div>
        </section>

        <section v-if="isUser" class="seller-panel account-shop">
          <h2>卖家档案</h2>
          <p v-if="seller.loading.value" role="status">正在读取卖家档案…</p>
          <div v-else-if="seller.error.value">
            <p class="notice error" role="alert">{{ seller.error.value }}</p>
            <button type="button" class="button button-outline" @click="sellerRetry++">重试</button>
          </div>
          <template v-else-if="seller.data.value">
            <p><strong>{{ seller.data.value.shopName }}</strong> · {{ sellerLabels[seller.data.value.sellerType] }}</p>
            <p class="muted">{{ reviewLabels[seller.data.value.reviewStatus] }} · {{ operationLabels[seller.data.value.operationStatus] }}</p>
            <RouterLink v-if="session.access === 'MERCHANT'" class="button button-outline"
              :to="{ name: 'categories', query: { sellerId: seller.data.value.sellerId } }">查看店铺</RouterLink>
          </template>
          <RouterLink v-else class="button button-outline" :to="{ name: 'seller-apply' }">申请成为卖家</RouterLink>
        </section>

        <section v-if="showFavorites" id="favorites" class="seller-panel" aria-labelledby="favorites-title">
          <h2 id="favorites-title">我的收藏</h2>
          <p v-if="favorites.loading.value" role="status">正在读取收藏…</p>
          <div v-else-if="favorites.error.value">
            <p class="notice error" role="alert">{{ favorites.error.value }}</p>
            <button type="button" class="button button-outline" @click="retry++">重试</button>
          </div>
          <template v-else-if="favorites.data.value">
            <div v-if="favorites.data.value.items.length" class="product-grid account-favorites">
              <ProductCard v-for="product in favorites.data.value.items" :key="product.id" :product="product" />
            </div>
            <p v-else class="muted">暂无可显示的收藏。<RouterLink :to="{ name: 'categories' }">浏览商品</RouterLink></p>
            <PageNav label="收藏分页" :page="favorites.data.value.page"
              :pages="Math.ceil(favorites.data.value.total / favorites.data.value.size)" @change="page = $event" />
          </template>
        </section>
      </div>
    </div>
  </section>
</template>
