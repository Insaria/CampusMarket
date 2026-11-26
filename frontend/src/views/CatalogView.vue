<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { useOrderRead } from '../orders.js'
import { catalogFilters, listQuery, validatePrices } from '../catalog.js'
import CatalogFilters from '../components/CatalogFilters.vue'
import PageNav from '../components/PageNav.vue'
import ProductCard from '../components/ProductCard.vue'

const route = useRoute()
const router = useRouter()
const filters = computed(() => catalogFilters(route.query))
const categories = ref([])
const categoryError = ref('')
const result = ref(null)
const error = ref('')
const loading = ref(true)
const retry = ref(0)
const sellerId = computed(() => filters.value.sellerId)
const { data: shop } = useOrderRead([sellerId, retry], ([id], signal) => id ? api.get('/shops/' + id, { signal }) : null)
const pages = computed(() => result.value ? Math.ceil(result.value.total / result.value.size) : 0)
const categoryNames = computed(() => new Map(categories.value.map(({ id, name }) => [String(id), name])))
const categoryName = computed(() => categoryNames.value.get(filters.value.categoryId))

async function loadCategories() {
  categoryError.value = ''
  try {
    categories.value = await api.get('/categories')
  } catch (failure) {
    categoryError.value = failure.message
  }
}

function navigate(values, replace = false) {
  const target = { name: 'categories', query: listQuery(values) }
  return replace ? router.replace(target) : router.push(target)
}

watch([filters, retry], async ([values], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  result.value = null
  error.value = validatePrices(values)
  loading.value = !error.value
  if (error.value) return
  try {
    const page = await api.get('/products', { params: { ...listQuery(values), size: 12 } })
    if (!current) return
    const lastPage = Math.max(1, Math.ceil(page.total / page.size))
    if (values.page > lastPage) {
      await navigate({ ...values, page: lastPage }, true)
      return
    }
    result.value = page
  } catch (failure) {
    if (current) error.value = failure.message
  } finally {
    if (current) loading.value = false
  }
}, { immediate: true })

onMounted(loadCategories)
</script>

<template>
  <section class="market-catalog" aria-labelledby="catalog-title">
    <div class="catalog-heading">
      <h1 id="catalog-title">{{ shop?.shopName || '浏览商品' }}</h1>
      <RouterLink class="button button-outline" :to="{ name: 'sold-products', query: filters.sellerId ? { sellerId: filters.sellerId } : {} }">
        已售商品
      </RouterLink>
    </div>
    <div class="catalog-layout">
      <div class="catalog-sidebar">
        <CatalogFilters :filters="filters" :categories="categories" @apply="navigate" @reset="navigate({})" />
        <p v-if="categoryError" class="notice error" role="alert">
          分类加载失败。<button class="text-button" @click="loadCategories">重试分类</button>
        </p>
      </div>
      <div class="catalog-results">
        <p v-if="shop?.operationStatus === 'SUSPENDED'" class="empty-panel" role="status">店铺暂时关闭，商品不可购买。</p>
        <p v-else-if="loading" class="empty-panel" role="status">正在加载商品…</p>
        <div v-else-if="error" class="empty-panel">
          <p role="alert">{{ error }}</p>
          <button v-if="!validatePrices(filters)" class="button button-outline" @click="retry++">重试</button>
        </div>
        <template v-else-if="result">
          <div class="result-heading">
            <h2>{{ categoryName || '搜索结果' }}</h2>
            <p class="muted" role="status">共 {{ result.total }} 件商品</p>
          </div>
          <div v-if="result.items.length" class="product-grid">
            <ProductCard v-for="product in result.items" :key="product.id" :product="product"
              :category="categoryNames.get(String(product.categoryId))" />
          </div>
          <div v-else class="empty-panel">
            <p>没有符合条件的商品。</p>
            <button class="button button-outline" type="button" @click="navigate({})">清除筛选</button>
          </div>
          <PageNav :page="result.page" :pages="pages" label="商品分页" @change="navigate({ ...filters, page: $event })" />
        </template>
      </div>
    </div>
  </section>
</template>
