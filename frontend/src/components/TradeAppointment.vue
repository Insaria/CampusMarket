<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api.js'
import { utcLabel } from '../admin.js'
import { useWorkspaceConfirm } from '../workspace.js'

const props = defineProps({ orderId: { type: Number, required: true } })
const emit = defineEmits(['confirmed'])
const confirm = useWorkspaceConfirm()
const pending = ref(null)
const time = ref('')
const location = ref('')
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const message = ref('')
const controller = new AbortController()
const path = `/orders/${props.orderId}/appointment`

async function load() {
  loading.value = true
  error.value = ''
  try {
    pending.value = await api.get(path, { signal: controller.signal })
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure.message
  } finally {
    loading.value = false
  }
}

async function submit(accept = false) {
  if (busy.value || (accept && !pending.value?.canAccept)) return
  error.value = ''
  const scheduled = new Date(time.value)
  if (!accept && (!time.value || Number.isNaN(scheduled.getTime()) || !location.value.trim())) {
    error.value = '请填写预约时间和地点。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    if (accept) {
      if (!await confirm('接受线下交易预约？', {
        label: '接受预约', detail: utcLabel(pending.value.scheduledTradeAt) + ' · ' + pending.value.location,
      }) || controller.signal.aborted) return
      await api.post(`${path}/accept`, { proposalId: pending.value.proposalId })
      emit('confirmed')
    } else {
      pending.value = await api.post(path, { scheduledTradeAt: scheduled.toISOString(), location: location.value })
      message.value = '预约提议已提交，等待对方确认。'
    }
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure.message
  } finally {
    busy.value = false
  }
}

onMounted(load)
onUnmounted(() => controller.abort())
</script>

<template>
  <section class="seller-panel admin-top-gap">
    <h2>协商线下交付</h2>
    <p v-if="loading" role="status">正在读取预约…</p>
    <template v-else>
      <dl v-if="pending" class="seller-metrics trade-metrics">
        <div><dt>预约状态</dt><dd>{{ pending.canAccept ? '对方提议 · 待你确认' : '等待对方确认' }}</dd></div>
        <div><dt>提议时间</dt><dd>{{ utcLabel(pending.scheduledTradeAt) }}</dd></div>
        <div><dt>提议地点</dt><dd>{{ pending.location }}</dd></div>
      </dl>
      <p v-else-if="!error" class="muted">当前没有待确认的预约提议。</p>
      <div class="actions">
        <button v-if="pending?.canAccept" class="button" type="button" :disabled="busy" @click="submit(true)">接受预约</button>
        <button class="button button-outline" type="button" :disabled="busy" @click="load">刷新预约</button>
      </div>
      <form class="admin-filters admin-top-gap trade-appointment-form" @submit.prevent="submit()">
        <label>交易时间（本地时间）<input v-model="time" type="datetime-local" required></label>
        <label>交易地点<input v-model.trim="location" maxlength="255" required></label>
        <button class="button button-outline" type="submit" :disabled="busy">{{ pending ? '提出新时间和地点' : '提出预约' }}</button>
      </form>
    </template>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </section>
</template>
