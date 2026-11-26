<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearUser, session } from '../auth.js'
import { cartQuantity, setCartQuantity } from '../cart.js'

const props = defineProps({ product: { type: Object, required: true } })
const route = useRoute()
const router = useRouter()
const quantity = ref(1)
const saving = ref(false)
const error = ref('')
const saved = ref(false)

function login() {
  return router.push({ name: 'login', query: { redirect: route.fullPath } })
}

async function add() {
  if (saving.value || props.product.availableQuantity < 1) return
  if (!session.user) return login()
  const value = cartQuantity(quantity.value)
  saved.value = false
  error.value = ''
  if (!value || value > props.product.availableQuantity) {
    error.value = '请选择当前库存范围内的购买数量。'
    return
  }
  saving.value = true
  try {
    await setCartQuantity(props.product.id, value)
    saved.value = true
  } catch (failure) {
    if (failure.status === 401) {
      clearUser()
      await login()
    } else {
      error.value = failure.message
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="session.user?.roleCode !== 'ADMIN' && product.availableQuantity > 0" class="add-to-cart">
    <form class="cart-inline" @submit.prevent="add">
      <label v-if="session.user">购买数量
        <input v-model.number="quantity" type="number" min="1" :max="product.availableQuantity" step="1"
          required :disabled="saving" @input="saved = false">
      </label>
      <button class="button" type="submit" :disabled="saving">
        {{ saving ? '正在加入…' : session.user ? '加入购物车' : '登录后加入购物车' }}
      </button>
    </form>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="saved" class="notice success" role="status">
      已保存到购物车。<RouterLink :to="{ name: 'cart' }">查看购物车</RouterLink>
    </p>
  </div>
</template>
