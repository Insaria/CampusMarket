<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { clearUser, session } from '../auth.js'
import { productMessageError } from '../notifications.js'

const props = defineProps({ productId: { type: [Number, String], required: true }, negotiable: Boolean })
const route = useRoute()
const router = useRouter()
const open = ref(false)
const content = ref('')
const busy = ref(false)
const error = ref('')
const expired = ref(false)

function signInAgain() {
  clearUser()
  return router.push({ name: 'login', query: { redirect: route.fullPath } })
}

async function send() {
  if (busy.value || !content.value.trim()) return
  busy.value = true
  error.value = ''
  expired.value = false
  try {
    const result = await api.post(`/products/${props.productId}/messages`, { content: content.value.trim() })
    await router.push({ name: 'product-messages', params: { messageId: result.messageId } })
  } catch (failure) {
    expired.value = failure.status === 401
    error.value = productMessageError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <RouterLink v-if="!session.user" class="button button-outline"
    :to="{ name: 'login', query: { redirect: route.fullPath } }">登录后联系卖家</RouterLink>
  <button v-else-if="session.user.roleCode === 'USER' && !open" class="button button-outline"
    type="button" @click="open = true">{{ negotiable ? '联系卖家 · 咨询议价' : '联系卖家' }}</button>
  <form v-else-if="open && session.user?.roleCode === 'USER'" class="contact-compose" @submit.prevent="send">
    <label>给卖家的留言 <textarea v-model="content" maxlength="1000" required rows="3" :disabled="busy"
      placeholder="说说你想咨询的问题" /></label>
    <p class="muted">{{ negotiable ? '可留言咨询议价。' : '可留言咨询商品。' }}订单价格以结账为准。</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <div class="actions">
      <button class="button" type="submit" :disabled="busy || !content.trim()">{{ busy ? '发送中…' : '发送留言' }}</button>
      <button v-if="expired" class="button button-outline" type="button" @click="signInAgain">重新登录</button>
      <button class="button button-outline" type="button" :disabled="busy" @click="open = false">收起</button>
    </div>
  </form>
</template>
