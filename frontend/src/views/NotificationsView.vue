<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { utcLabel } from '../admin.js'
import { api } from '../api.js'
import { positiveId } from '../catalog.js'
import { notificationTarget, refreshUnread, unreadCount } from '../notifications.js'
import { useOrderRead } from '../orders.js'
import PageNav from '../components/PageNav.vue'

const route = useRoute()
const id = computed(() => route.params.notificationId)
const page = ref(1)
const unreadOnly = ref(false)
const retry = ref(0)
const busy = ref(false)
const actionError = ref('')
const feedback = ref('')
const { data, loading, error } = useOrderRead([id, unreadOnly, page, retry], async ([messageId, unread, number], signal) => {
  if (messageId) {
    const value = positiveId(messageId)
    if (!value) throw new Error('通知不存在。')
    return api.get(`/notifications/${value}`, { signal })
  }
  const result = await api.get('/notifications', { params: { unreadOnly: unread, page: number, size: 20 }, signal })
  if (!signal.aborted && number > 1 && result.total <= (number - 1) * result.size) {
    page.value = Math.max(1, Math.ceil(result.total / result.size))
  }
  return result
})
const pages = computed(() => !id.value && data.value ? Math.ceil(data.value.total / data.value.size) : 0)
const target = computed(() => id.value && data.value ? notificationTarget(data.value) : null)
const messagesOnly = computed(() => route.query.kind === 'messages')
const visibleItems = computed(() => {
  const items = data.value?.items ?? []
  return messagesOnly.value ? items.filter(item => item.notificationKind === 'PRODUCT_MESSAGE') : items
})
watch(messagesOnly, () => { page.value = 1 })

watch(id, () => {
  feedback.value = ''
  actionError.value = ''
})
onMounted(refreshUnread)

async function mark(all = false) {
  if (busy.value || loading.value) return
  if (all && !window.confirm('将所有未读消息标为已读？')) return
  const selected = id.value
  busy.value = true
  actionError.value = ''
  feedback.value = ''
  try {
    const result = await api.patch(all ? '/notifications/read-all' : `/notifications/${selected}/read`)
    if (selected === id.value) {
      if (all) {
        feedback.value = `已更新 ${result} 条消息。`
        retry.value++
      } else {
        data.value = result
      }
    }
  } catch (failure) {
    if (selected === id.value) actionError.value = failure.message
  } finally {
    busy.value = false
    refreshUnread()
  }
}
</script>

<template>
  <section class="message-workspace">
    <RouterLink v-if="id" class="catalog-back" :to="{ name: 'notifications', query: route.query }">← 返回消息列表</RouterLink>
    <div class="stage72-page-header seller-heading">
      <h1>{{ id ? '消息详情' : '站内消息' }}</h1>
      <button v-if="!id" class="button button-outline" type="button" :disabled="busy || loading || unreadCount === 0"
        @click="mark(true)">全部标为已读</button>
    </div>
    <div v-if="!id" class="message-toolbar">
      <nav aria-label="消息分类">
        <RouterLink class="button button-outline" :aria-current="messagesOnly ? undefined : 'page'"
          :to="{ name: 'notifications' }">全部消息</RouterLink>
        <RouterLink class="button button-outline" :aria-current="messagesOnly ? 'page' : undefined"
          :to="{ name: 'notifications', query: { kind: 'messages' } }">商品留言</RouterLink>
      </nav>
      <label class="seller-check notification-filter">
        <input v-model="unreadOnly" type="checkbox" :disabled="busy" @change="page = 1">只看未读
      </label>
      <span v-if="unreadCount != null" class="muted" role="status">{{ unreadCount }} 条未读消息</span>
    </div>
    <p v-if="feedback" class="notice success" role="status">{{ feedback }}</p>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <p v-if="loading" class="empty-panel" role="status">正在读取站内消息…</p>
    <div v-else-if="error" class="empty-panel">
      <p role="alert">{{ error }}</p><button class="button button-outline" type="button" @click="retry++">重试</button>
    </div>
    <template v-else-if="data">
      <article v-if="id" class="seller-panel">
        <div class="seller-heading"><h2>{{ data.title }}</h2><span class="status-tag">{{ data.readAt ? '已读' : '未读' }}</span></div>
        <p class="muted">{{ utcLabel(data.createdAt) }}</p>
        <p class="product-description">{{ data.content }}</p>
        <div class="actions">
          <button v-if="!data.readAt" class="button button-outline" type="button" :disabled="busy" @click="mark()">标为已读</button>
          <RouterLink v-if="target" class="button" :to="target">
            {{ target.name === 'seller-products' ? '前往商品管理'
              : target.name === 'product-messages' ? '查看留言并回复' : '查看关联业务' }}
          </RouterLink>
        </div>
      </article>
      <template v-else>
        <div v-if="visibleItems.length" class="notification-list">
          <RouterLink v-for="item in visibleItems" :key="item.id" class="seller-panel notification-item"
            :class="{ 'notification-unread': !item.readAt }"
            :to="{ name: 'notification', params: { notificationId: item.id }, query: route.query }">
            <div>
              <strong>{{ item.title }}</strong>
              <p class="muted">{{ utcLabel(item.createdAt) }}</p>
            </div>
            <span v-if="!item.readAt" class="status-tag" data-state="PENDING">未读</span>
          </RouterLink>
        </div>
        <p v-else class="empty-panel">{{ messagesOnly && data.total ? '本页没有商品留言，可翻页查看。'
          : unreadOnly ? '没有未读消息。' : '暂无站内消息。' }}</p>
        <PageNav :page="page" :pages="pages" label="站内消息分页" @change="page = $event" />
      </template>
    </template>
  </section>
</template>
