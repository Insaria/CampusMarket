<script setup>
import { ref, watch } from 'vue'
import { api } from '../api.js'
import { imagePositions, imageUrl, money } from '../catalog.js'
import { editAllowed, listingLabels, productPath, reviewLabels } from '../seller.js'
import ProductPhoto from './ProductPhoto.vue'
import SellerStockForm from './SellerStockForm.vue'
import StatusBadge from './StatusBadge.vue'

const props = defineProps({ product: { type: Object, required: true } })
const emit = defineEmits(['updated'])
const cover = ref('')
const pending = ref(false)
const stockBusy = ref(false)
const error = ref('')
const success = ref('')

watch(() => props.product.listingStatus, async (status, _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  cover.value = ''
  if (status !== 'ON_SHELF') return
  try {
    const [first] = await imagePositions(props.product.id)
    if (current && first != null) cover.value = imageUrl(props.product.id, first)
  } catch {
    // The catalog controls public visibility separately.
  }
}, { immediate: true })

async function action(name, message) {
  if (pending.value || stockBusy.value) return
  pending.value = true
  error.value = ''
  success.value = ''
  try {
    emit('updated', await api.post(`${productPath}/${props.product.id}/${name}`))
    success.value = message
  } catch (failure) {
    error.value = failure.message
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <article class="seller-panel seller-product">
    <ProductPhoto v-if="product.listingStatus === 'ON_SHELF'" :src="cover" :alt="product.name" lazy />
    <div v-else class="seller-image-unavailable">未公开图片</div>
    <div class="seller-product-info">
      <h2>{{ product.name }}</h2>
      <p class="management-product-price">
        <strong class="product-price">{{ money(product.discountPrice ?? product.originalPrice) }}</strong>
        <del v-if="product.discountPrice != null && Number(product.discountPrice) < Number(product.originalPrice)" class="muted">{{ money(product.originalPrice) }}</del>
      </p>
      <div class="seller-options">
        <StatusBadge :state="product.reviewStatus" :label="reviewLabels[product.reviewStatus]" />
        <StatusBadge :state="product.listingStatus" :label="listingLabels[product.listingStatus]" />
      </div>
      <dl class="management-stock">
        <div><dt>可售</dt><dd>{{ product.availableQuantity }} 件</dd></div>
        <div><dt>预留</dt><dd>{{ product.reservedQuantity }} 件</dd></div>
      </dl>
    </div>
    <div class="seller-row-actions">
      <div class="actions">
        <RouterLink v-if="editAllowed(product) && !pending && !stockBusy" class="button button-outline"
          :to="{ name: 'seller-product-edit', params: { productId: product.id } }">编辑</RouterLink>
        <button v-if="product.reviewStatus === 'DRAFT' && product.listingStatus === 'OFF_SHELF'"
          class="button" type="button" :disabled="pending || stockBusy" @click="action('submit', '已提交审核。')">提交审核</button>
        <button v-if="product.reviewStatus === 'APPROVED' && product.listingStatus === 'OFF_SHELF'"
          class="button" type="button" :disabled="pending || stockBusy" @click="action('publish', '商品已上架。')">上架</button>
        <button v-if="product.listingStatus === 'ON_SHELF'" class="button button-outline" type="button"
          :disabled="pending || stockBusy" @click="action('unpublish', '商品已下架。')">下架</button>
      </div>
      <details class="stock-disclosure">
        <summary>调整库存</summary>
        <SellerStockForm :product="product" :disabled="pending" @updated="emit('updated', $event)" @busy="stockBusy = $event" />
      </details>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <p v-else-if="success" class="notice success" role="status">{{ success }}</p>
    </div>
  </article>
</template>
