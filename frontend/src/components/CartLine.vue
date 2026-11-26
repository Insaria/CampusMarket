<script setup>
import { onMounted, ref } from 'vue'
import { imagePositions, imageUrl, money } from '../catalog.js'
import { cartQuantity, formatCents, lineAmount } from '../cart.js'
import ProductPhoto from './ProductPhoto.vue'

const props = defineProps({
  line: { type: Object, required: true }, checked: Boolean, busy: Boolean, limitReached: Boolean,
})
const emit = defineEmits(['update', 'remove', 'select'])
const quantity = ref(props.line.quantity)
const cover = ref('')
const error = ref('')

onMounted(async () => {
  if (!props.line.visible) return
  try {
    const [position] = await imagePositions(props.line.productId)
    if (position != null) cover.value = imageUrl(props.line.productId, position)
  } catch {
    // The row remains usable without its cover.
  }
})

function save() {
  const count = cartQuantity(quantity.value)
  error.value = ''
  if (!count || count > props.line.availableQuantity) {
    error.value = '请输入库存范围内的购买数量。'
    return
  }
  emit('update', props.line.productId, count)
}
</script>

<template>
  <article class="cart-line">
    <input class="cart-checkbox" type="checkbox" :checked="checked" :disabled="busy || !!line.issue || (limitReached && !checked)"
      :aria-label="'选择 ' + line.name" @change="$emit('select', line.productId, $event.target.checked)">
    <ProductPhoto :src="cover" :alt="line.name" lazy />
    <div class="cart-line-info">
      <h2>
        <RouterLink v-if="line.visible" :to="{ name: 'product', params: { productId: line.productId } }">{{ line.name }}</RouterLink>
        <template v-else>{{ line.name }}</template>
      </h2>
      <p v-if="line.visible" class="cart-unit-price">{{ money(line.price) }} <small class="muted">/ 件</small></p>
      <span v-if="line.visible" class="stock-chip">{{ line.availableQuantity ? '可售 ' + line.availableQuantity + ' 件' : '已售罄' }}</span>
      <p v-if="line.issue" class="notice seller-note">{{ line.issue }}</p>
    </div>
    <div class="cart-line-actions">
      <form v-if="line.visible && line.availableQuantity > 0" class="cart-inline" @submit.prevent="save">
        <label>购买数量
          <input v-model.number="quantity" type="number" min="1" :max="line.availableQuantity" step="1"
            required :disabled="busy" @input="error = ''">
        </label>
        <button class="button button-outline" type="submit" :disabled="busy || quantity === line.quantity">更新数量</button>
      </form>
      <p v-else class="muted">购物车数量：{{ line.quantity }} 件</p>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <div class="cart-line-footer">
        <strong v-if="line.visible">{{ formatCents(lineAmount(line)) }}</strong>
        <button class="text-button" type="button" :disabled="busy" @click="$emit('remove', line.productId)">移除</button>
      </div>
    </div>
  </article>
</template>
