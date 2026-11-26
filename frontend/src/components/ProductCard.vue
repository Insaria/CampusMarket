<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { imagePositions, imageUrl, money } from '../catalog.js'
import ProductPhoto from './ProductPhoto.vue'

const props = defineProps({
  product: { type: Object, required: true },
  category: { type: String, default: '' },
})
const card = ref(null)
const cover = ref('')
let observer

async function loadCover() {
  try {
    if (props.product.operationStatus === 'SUSPENDED') return
    const [first] = await imagePositions(props.product.id)
    if (first != null) cover.value = imageUrl(props.product.id, first)
  } catch {
    // Keep the card usable when its image metadata is unavailable.
  }
}

onMounted(() => {
  if (!('IntersectionObserver' in window)) {
    loadCover()
    return
  }
  observer = new IntersectionObserver(([entry]) => {
    if (!entry.isIntersecting) return
    observer.disconnect()
    loadCover()
  }, { rootMargin: '240px' })
  observer.observe(card.value)
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <article ref="card" class="product-card">
    <RouterLink class="product-card-link" :to="{ name: 'product', params: { productId: product.id } }">
      <ProductPhoto :src="cover" :alt="product.name" lazy />
      <div class="product-card-body">
        <p v-if="category" class="product-category">{{ category }}</p>
        <h3>{{ product.name }}</h3>
        <p class="product-meta">{{ product.shopName }}</p>
        <div class="card-bottom">
          <strong class="product-price">{{ money(product.price) }}</strong>
          <del v-if="Number(product.originalPrice) > Number(product.price)" class="product-original-price">
            {{ money(product.originalPrice) }}
          </del>
        </div>
        <div class="card-stock">
          <span v-if="product.operationStatus === 'SUSPENDED'" class="stock-chip sold-out">店铺暂时关闭 · 不可购买</span>
          <span v-else-if="product.availableQuantity === 0" class="stock-chip sold-out">已售罄</span>
          <template v-else>
            <span class="product-meta">库存 {{ product.availableQuantity }}</span>
            <span v-if="product.negotiable === 1" class="stock-chip">可议价</span>
          </template>
        </div>
      </div>
    </RouterLink>
  </article>
</template>
