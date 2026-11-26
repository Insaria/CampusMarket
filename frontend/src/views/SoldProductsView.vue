<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api.js'
import { positiveId } from '../catalog.js'
import PageNav from '../components/PageNav.vue'

const route = useRoute()
const sellerId = computed(() => positiveId(route.query.sellerId))
const page = ref(1)
const retry = ref(0)
const result = ref(null)
const loading = ref(false)
const error = ref('')

watch(sellerId, () => { page.value = 1 })
watch([sellerId, page, retry], async ([seller, currentPage], _, onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  loading.value = true
  error.value = ''
  try {
    const data = await api.get('/products/sold', {
      params: { sellerId: seller || undefined, page: currentPage }, signal: controller.signal,
    })
    if (!controller.signal.aborted) result.value = data
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure.message
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <section class="seller-workspace">
    <RouterLink class="catalog-back" :to="{ name: 'categories', query: sellerId ? { sellerId } : {} }">
      ← 返回商品目录
    </RouterLink>
    <h1>已售商品</h1>
    <p v-if="loading" class="empty-panel" role="status">正在查询成交记录…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p>
      <button class="button button-outline" type="button" @click="retry++">重试</button>
    </div>
    <template v-else-if="result">
      <div v-if="result.items.length" class="admin-table-wrap seller-panel">
        <table class="admin-table">
          <thead><tr><th>商品</th><th>卖家</th><th>有效销量</th></tr></thead>
          <tbody>
            <tr v-for="item in result.items" :key="item.id">
              <th scope="row">{{ item.name }}</th>
              <td><RouterLink :to="{ name: 'categories', query: { sellerId: item.sellerId } }">{{ item.shopName }}</RouterLink></td>
              <td>{{ item.unitsSold }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-panel">暂无有效成交商品。</p>
      <PageNav :page="result.page" :pages="Math.ceil(result.total / result.size)" label="已售商品分页"
        @change="page = $event" />
    </template>
  </section>
</template>
