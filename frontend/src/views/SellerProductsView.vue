<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sellerProducts } from '../seller.js'
import PageNav from '../components/PageNav.vue'
import SellerProductRow from '../components/SellerProductRow.vue'

const route = useRoute()
const router = useRouter()
const listing = ref(null)
const loading = ref(true)
const error = ref('')
const retry = ref(0)
const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isInteger(value) && value >= 1 && value <= 2147483647 ? value : 1
})
const totalPages = computed(() => listing.value ? Math.ceil(listing.value.total / listing.value.size) : 0)

function goToPage(number) {
  router.push({ query: { page: number === 1 ? undefined : number } })
}

function updateItem(product) {
  if (listing.value) listing.value.items = listing.value.items.map(item => item.id === product.id ? product : item)
}

watch([page, retry], async ([number], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  loading.value = true
  listing.value = null
  error.value = ''
  try {
    const data = await sellerProducts(number)
    if (!current) return
    const last = Math.max(1, Math.ceil(data.total / data.size))
    if (number > last) await router.replace({ query: { page: last === 1 ? undefined : last } })
    else listing.value = data
  } catch (failure) {
    if (current) error.value = failure.message
  } finally {
    if (current) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <section class="seller-workspace">
    <header class="management-page-header seller-heading">
      <h1>我的商品</h1>
      <RouterLink class="button" :to="{ name: 'seller-product-new' }">新建商品</RouterLink>
    </header>
    <p v-if="loading" class="empty-panel" role="status">正在读取商品…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p>
      <button class="button button-outline" type="button" @click="retry++">重试</button>
    </div>
    <template v-else-if="listing">
      <p class="muted management-result-count" role="status">共 {{ listing.total }} 件商品</p>
      <div v-if="listing.items.length" class="seller-product-list">
        <SellerProductRow v-for="product in listing.items" :key="product.id" :product="product" @updated="updateItem" />
      </div>
      <p v-else class="empty-panel">暂无商品。新建草稿后可上传图片并提交审核。</p>
      <PageNav :page="listing.page" :pages="totalPages" label="卖家商品分页" @change="goToPage" />
    </template>
  </section>
</template>
