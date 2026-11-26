<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'
import { refreshSellerAccess, sellerHistoryAllowed } from '../auth.js'
import { percent } from '../catalog.js'
import { adminMoney, useAdminRead } from '../admin.js'
import { operationLabels, reviewLabels, sellerLabels } from '../seller.js'
import StatusBadge from '../components/StatusBadge.vue'

const profile = ref(null)
const loading = ref(true)
const error = ref('')
const eligible = ref(false)
const eligibilityError = ref('')
const wallet = useAdminRead()
const ratings = useAdminRead()

const statusText = computed(() => {
  if (eligible.value) return '可以发布和管理商品。'
  if (eligibilityError.value) return '暂时无法确认经营资格，请重试。'
  if (profile.value.reviewStatus === 'PENDING') return '资质等待管理员审核，审核通过并启用后可经营。'
  if (profile.value.reviewStatus === 'REJECTED') return '资质审核未通过，当前无法经营。'
  if (profile.value.operationStatus === 'SUSPENDED') return '店铺暂时关闭，无法发布或上架商品。已付款订单与售后可继续处理。'
  if (profile.value.operationStatus === 'INACTIVE') return '已审核，店铺尚未启用。'
  return '当前无经营资格。正式商家需要有效且启用的等级。'
})

async function load() {
  loading.value = true
  error.value = ''
  profile.value = null
  eligible.value = false
  eligibilityError.value = ''
  wallet.run(signal => api.get('/wallet', { params: { page: 1, size: 1 }, signal }))
  ratings.reset()
  try {
    profile.value = await api.get('/sellers/me')
    try {
      eligible.value = await refreshSellerAccess()
      if (sellerHistoryAllowed()) ratings.run(signal => api.get('/sellers/me/satisfaction', { signal }))
    } catch (failure) {
      eligibilityError.value = failure.message
    }
  } catch (failure) {
    if (failure.code !== 'SELLER_NOT_FOUND') error.value = failure.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="seller-workspace">
    <header class="management-page-header seller-heading">
      <h1>经营概览</h1>
      <RouterLink v-if="eligible" class="button" :to="{ name: 'seller-product-new' }">新建商品</RouterLink>
    </header>
    <p v-if="loading" class="empty-panel" role="status">正在读取卖家资料…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p>
      <button class="button button-outline" type="button" @click="load">重试</button>
    </div>
    <section v-else-if="!profile" class="seller-panel">
      <h2>尚未创建卖家档案</h2>
      <p>个人卖家无需上传资质。正式商家可提交营业执照和身份证图片申请审核。</p>
      <RouterLink class="button" :to="{ name: 'seller-apply' }">申请成为卖家</RouterLink>
    </section>
    <template v-else>
      <section class="seller-panel seller-profile">
        <div class="seller-heading">
          <div>
            <p class="muted">{{ sellerLabels[profile.sellerType] }}</p>
            <h2>{{ profile.shopName }}</h2>
          </div>
          <button class="button button-outline" type="button" @click="load">刷新状态</button>
        </div>
        <p v-if="profile.shopDescription" class="product-description">{{ profile.shopDescription }}</p>
        <dl class="seller-metrics management-metrics">
          <div><dt>审核状态</dt><dd><StatusBadge :state="profile.reviewStatus" :label="reviewLabels[profile.reviewStatus]" /></dd></div>
          <div><dt>经营状态</dt><dd><StatusBadge :state="profile.operationStatus" :label="operationLabels[profile.operationStatus]" /></dd></div>
          <div v-if="profile.sellerType === 'BUSINESS'"><dt>商家等级</dt><dd>{{ profile.levelName || '尚未分配' }}</dd></div>
          <div><dt>平台手续费率</dt><dd>{{ profile.sellerType === 'PERSONAL' ? '免手续费' : profile.feeRate == null ? '尚未分配' : percent(profile.feeRate) }}</dd></div>
          <div><dt>当前经营资格</dt><dd>{{ eligibilityError ? '待确认' : eligible ? '具备' : '暂不具备' }}</dd></div>
        </dl>
        <p :class="['notice', eligible ? 'success' : 'seller-note']" role="status">{{ statusText }}</p>
        <p v-if="profile.closureReason" class="product-description">关闭原因：{{ profile.closureReason }}</p>
      </section>
      <div class="seller-shortcuts management-overview">
        <section class="seller-panel">
          <h2>钱包余额</h2>
          <p v-if="wallet.loading.value" role="status" class="muted">正在读取余额…</p>
          <p v-else-if="wallet.error.value" class="notice error" role="alert">{{ wallet.error.value }}</p>
          <strong v-else-if="wallet.data.value?.balance != null" class="management-value">{{ adminMoney(wallet.data.value.balance) }}</strong>
          <p v-else class="muted">尚未开立钱包</p>
          <RouterLink :to="{ name: 'wallet' }">查看历史流水 →</RouterLink>
        </section>
        <section v-if="sellerHistoryAllowed()" class="seller-panel">
          <h2>店铺评价</h2>
          <p v-if="ratings.loading.value" role="status" class="muted">正在读取评价…</p>
          <p v-else-if="ratings.error.value" class="notice error" role="alert">{{ ratings.error.value }}</p>
          <template v-else-if="ratings.data.value">
            <strong v-if="ratings.data.value.averageRating != null" class="management-value">{{ Number(ratings.data.value.averageRating).toFixed(1) }} / 5</strong>
            <p class="muted">{{ ratings.data.value.reviewCount }} 条评价</p>
          </template>
          <RouterLink :to="{ name: 'reputation' }">查看评价与信誉 →</RouterLink>
        </section>
        <section v-if="eligible" class="seller-panel">
          <h2>我的店铺</h2>
          <RouterLink :to="{ name: 'categories', query: { sellerId: profile.sellerId } }">查看上架商品 →</RouterLink>
          <RouterLink :to="{ name: 'sold-products', query: { sellerId: profile.sellerId } }">查看已售商品与销量 →</RouterLink>
        </section>
      </div>
    </template>
  </section>
</template>
