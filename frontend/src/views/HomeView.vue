<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'
import HomeCarousel from '../components/HomeCarousel.vue'
import ProductCard from '../components/ProductCard.vue'
import ProductSearch from '../components/ProductSearch.vue'

const router = useRouter()
const keyword = ref('')
const categories = reactive({ items: [], error: '', loading: true })
const categoryNames = computed(() => new Map(categories.items.map(({ id, name }) => [String(id), name])))
const feeds = reactive([
  { title: '最新发布', sort: 'NEWEST', items: [], error: '', loading: true },
  { title: '热门商品', sort: 'SALES_DESC', items: [], error: '', loading: true },
])
const carouselProducts = computed(() => feeds.flatMap(feed => feed.items))

async function loadCategories() {
  categories.loading = true
  categories.error = ''
  try {
    categories.items = await api.get('/categories')
  } catch (error) {
    categories.error = error.message
  } finally {
    categories.loading = false
  }
}

async function loadFeed(feed) {
  feed.loading = true
  feed.error = ''
  try {
    const { items } = await api.get('/products', { params: { sort: feed.sort, size: 4 } })
    feed.items = items
  } catch (error) {
    feed.error = error.message
  } finally {
    feed.loading = false
  }
}

function search() {
  const value = keyword.value.trim()
  router.push({ name: 'categories', query: value ? { keyword: value } : {} })
}

function categoryIcon(name) {
  if (/教材|学习/.test(name)) return '📚'
  if (/数码|电子/.test(name)) return '🎧'
  if (/宿舍|生活/.test(name)) return '🪴'
  if (/服饰|鞋包/.test(name)) return '🎒'
  if (/运动|户外/.test(name)) return '🏸'
  return '📦'
}

onMounted(() => {
  loadCategories()
  feeds.forEach(loadFeed)
})
</script>

<template>
  <HomeCarousel :products="carouselProducts" :categories="categories.items">
    <form class="hero-search" role="search" aria-label="搜索商城商品" @submit.prevent="search">
      <ProductSearch v-model="keyword" input-id="home-keyword" compact placeholder="搜索校园好物" />
      <button class="button" type="submit">搜索</button>
    </form>
  </HomeCarousel>

  <section class="catalog-section" aria-labelledby="home-categories">
    <div class="section-heading">
      <h2 id="home-categories">商品分类</h2>
      <RouterLink :to="{ name: 'categories' }">全部商品 <span aria-hidden="true">→</span></RouterLink>
    </div>
    <p v-if="categories.loading" role="status">正在加载分类…</p>
    <div v-else-if="categories.error" class="empty-panel">
      <p role="alert">{{ categories.error }}</p>
      <button class="button button-outline" @click="loadCategories">重试</button>
    </div>
    <div v-else-if="categories.items.length" class="category-links">
      <RouterLink v-for="category in categories.items" :key="category.id"
        :to="{ name: 'categories', query: { categoryId: category.id } }">
        <span class="category-icon" aria-hidden="true">{{ categoryIcon(category.name) }}</span>
        <span>{{ category.name }}</span>
      </RouterLink>
    </div>
    <p v-else class="empty-panel">暂无可用分类。</p>
  </section>

  <section v-for="feed in feeds" :key="feed.sort" class="catalog-section" :aria-labelledby="'feed-' + feed.sort">
    <div class="section-heading">
      <h2 :id="'feed-' + feed.sort">{{ feed.title }}</h2>
      <RouterLink :to="{ name: 'categories', query: { sort: feed.sort } }">查看全部 <span aria-hidden="true">→</span></RouterLink>
    </div>
    <p v-if="feed.loading" role="status">正在加载商品…</p>
    <div v-else-if="feed.error" class="empty-panel">
      <p role="alert">{{ feed.error }}</p>
      <button class="button button-outline" @click="loadFeed(feed)">重试</button>
    </div>
    <div v-else-if="feed.items.length" class="product-grid">
      <ProductCard v-for="product in feed.items" :key="product.id" :product="product"
        :category="categoryNames.get(String(product.categoryId))" />
    </div>
    <p v-else class="empty-panel">暂无在售商品。</p>
  </section>
</template>
