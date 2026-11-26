<script setup>
import { reactive, ref, watch } from 'vue'
import { catalogFilters, positiveId, sorts, validatePrices } from '../catalog.js'
import ProductSearch from './ProductSearch.vue'

const props = defineProps({
  filters: { type: Object, required: true },
  categories: { type: Array, required: true },
})
const emit = defineEmits(['apply', 'reset'])
const draft = reactive({})
const error = ref('')
watch(() => props.filters, (filters) => {
  Object.assign(draft, filters)
  error.value = ''
}, { immediate: true })

function submit() {
  const sellerId = draft.sellerId.trim()
  error.value = sellerId && !positiveId(sellerId) ? '请输入有效的卖家编号。' : validatePrices(draft)
  if (error.value) return
  emit('apply', { ...draft, keyword: draft.keyword.trim(), sellerId, page: 1 })
}

function reset() {
  Object.assign(draft, catalogFilters({}))
  error.value = ''
  emit('reset')
}
</script>

<template>
  <form class="catalog-filters" aria-label="搜索与筛选" @submit.prevent="submit">
    <h2>搜索与筛选</h2>
    <ProductSearch v-model="draft.keyword" input-id="catalog-keyword" />
    <div class="filter-fields">
      <label>商品分类
        <select v-model="draft.categoryId">
          <option value="">全部分类</option>
          <option v-if="draft.categoryId && !categories.some(({ id }) => String(id) === draft.categoryId)"
            :value="draft.categoryId">分类 #{{ draft.categoryId }}</option>
          <option v-for="category in categories" :key="category.id" :value="String(category.id)">{{ category.name }}</option>
        </select>
      </label>
      <label>卖家编号<input v-model="draft.sellerId" inputmode="numeric" maxlength="19" placeholder="不限"></label>
      <fieldset class="filter-price">
        <legend>价格区间（元）</legend>
        <div class="price-inputs">
          <input v-model.trim="draft.minPrice" aria-label="最低价（元）" inputmode="decimal" maxlength="15" placeholder="最低价">
          <span aria-hidden="true">—</span>
          <input v-model.trim="draft.maxPrice" aria-label="最高价（元）" inputmode="decimal" maxlength="15" placeholder="最高价">
        </div>
      </fieldset>
      <label>排序
        <select v-model="draft.sort">
          <option v-for="(label, code) in sorts" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
    </div>
    <div class="filter-actions">
      <button class="button" type="submit">应用筛选</button>
      <button class="button button-outline" type="button" @click="reset">重置</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </form>
</template>
