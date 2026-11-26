<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { utcLabel } from '../admin.js'
import { api } from '../api.js'
import { positiveId } from '../catalog.js'
import { clearUser } from '../auth.js'
import { productMessageError } from '../notifications.js'

const route = useRoute()
const router = useRouter()
const id = computed(() => positiveId(route.params.messageId))
const content = ref('')
const data = ref(null)
const loading = ref(false)
const sending = ref(false)
const error = ref('')
const expired = ref(false)
const timeline = computed(() => data.value ? [...data.value.items].reverse() : [])
let activeRequest
onBeforeUnmount(() => activeRequest?.abort())

watch(id, () => {
  activeRequest?.abort()
  data.value = null
  content.value = ''
  expired.value = false
  load()
}, { immediate: true })

function displayError(failure) {
  expired.value = failure.status === 401
  if (expired.value) data.value = null
  error.value = productMessageError(failure)
}

function signInAgain() {
  clearUser()
  return router.push({ name: 'login', query: { redirect: route.fullPath } })
}

async function load(before) {
  if (!id.value) {
    error.value = '留言不存在。'
    return
  }
  const request = new AbortController()
  activeRequest?.abort()
  activeRequest = request
  loading.value = true
  error.value = ''
  try {
    const page = await api.get(`/product-messages/${id.value}`, { params: { before }, signal: request.signal })
    if (!request.signal.aborted) {
      data.value = before ? { ...page, items: [...data.value.items, ...page.items] } : page
    }
  } catch (failure) {
    if (!request.signal.aborted) displayError(failure)
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

async function reply() {
  if (sending.value || !data.value || !content.value.trim()) return
  const thread = id.value
  sending.value = true
  error.value = ''
  try {
    await api.post(`/product-messages/${thread}/replies`, { content: content.value.trim() })
    if (thread === id.value) {
      content.value = ''
      await load()
    }
  } catch (failure) {
    if (thread === id.value) displayError(failure)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <section class="message-workspace">
    <RouterLink class="catalog-back" :to="{ name: 'notifications', query: { kind: 'messages' } }">← 商品留言列表</RouterLink>
    <header class="stage72-page-header seller-heading">
      <h1>商品留言</h1>
      <RouterLink v-if="data" class="button button-outline" :to="{ name: 'product', params: { productId: data.productId } }">查看相关商品</RouterLink>
    </header>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted" role="status">正在读取留言…</p>
    <button v-if="expired" type="button" class="button button-outline" @click="signInAgain">重新登录</button>
    <button v-else-if="error && !data && id" type="button" class="button button-outline" @click="load()">重试</button>
    <template v-if="data">
      <div class="seller-panel message-thread">
        <button v-if="data.nextBefore" class="button button-outline thread-history" type="button" :disabled="loading"
          @click="load(data.nextBefore)">查看较早留言</button>
        <ul v-if="timeline.length" class="message-timeline" aria-label="留言记录">
          <li v-for="item in timeline" :key="item.id" :class="{ 'message-mine': item.mine }">
            <div class="message-meta"><strong>{{ item.mine ? '我' : '对方' }}</strong> <time>{{ utcLabel(item.createdAt) }}</time></div>
            <p class="message-bubble product-description">{{ item.content }}</p>
          </li>
        </ul>
        <p v-else class="muted">这里还没有留言。</p>
        <form class="message-compose stage72-form" @submit.prevent="reply">
          <label>回复<textarea v-model="content" maxlength="1000" required rows="3" :disabled="sending"
            placeholder="写下回复" /></label>
          <button class="button" type="submit" :disabled="sending || !content.trim()">{{ sending ? '发送中…' : '发送回复' }}</button>
        </form>
      </div>
    </template>
  </section>
</template>
