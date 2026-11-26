<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { clearUser, session } from '../auth.js'

const props = defineProps({ productId: { type: String, required: true } })
const route = useRoute()
const router = useRouter()
const saved = ref(null)
const checking = ref(false)
const writing = ref(false)
const error = ref('')
const retry = ref(0)

watch([() => session.user, retry], async ([user], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  saved.value = null
  error.value = ''
  checking.value = user?.roleCode === 'USER'
  if (!checking.value) return
  try {
    let result
    let page = 1
    do {
      result = await api.get('/favorites', { params: { page, size: 100 } })
      if (!current) return
      if (result.items.some(({ id }) => String(id) === props.productId)) {
        saved.value = true
        return
      }
      page++
    } while (result.page * result.size < result.total)
    saved.value = false
  } catch (failure) {
    if (!current) return
    if (failure.status === 401) clearUser()
    else error.value = failure.message
  } finally {
    if (current) checking.value = false
  }
}, { immediate: true })

function goToLogin() {
  return router.push({ name: 'login', query: { redirect: route.fullPath } })
}

async function toggle() {
  if (!session.user) return goToLogin()
  if (checking.value || writing.value) return
  if (saved.value === null) {
    retry.value++
    return
  }
  writing.value = true
  error.value = ''
  try {
    if (saved.value) await api.delete(`/favorites/${props.productId}`)
    else await api.put(`/favorites/${props.productId}`)
    saved.value = !saved.value
  } catch (failure) {
    if (failure.status === 401) {
      clearUser()
      await goToLogin()
    } else {
      error.value = failure.message
    }
  } finally {
    writing.value = false
  }
}
</script>

<template>
  <div v-if="session.user?.roleCode !== 'ADMIN'">
    <button type="button" class="button button-outline" :disabled="checking || writing" @click="toggle">
      {{ checking ? '正在查询收藏…' : writing ? '正在保存…' : !session.user ? '登录后收藏' :
        saved === null ? '重试查询收藏' : saved ? '取消收藏' : '收藏商品' }}
    </button>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </div>
</template>
