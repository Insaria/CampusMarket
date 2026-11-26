<script setup>
import { ref } from 'vue'
import { api } from '../api.js'
import { useWorkspaceConfirm } from '../workspace.js'

const props = defineProps({
  endpoint: { type: String, required: true },
  title: { type: String, required: true },
})
const emit = defineEmits(['refresh'])
const confirm = useWorkspaceConfirm()
const rating = ref(0)
const content = ref('')
const busy = ref(false)
const receipt = ref(null)
const duplicate = ref(false)
const error = ref('')

async function submit() {
  if (busy.value || receipt.value || duplicate.value) return
  error.value = ''
  const text = content.value.trim()
  if (!Number.isInteger(rating.value) || rating.value < 1 || rating.value > 5 || !text || text.length > 2000) {
    error.value = '请选择 1 至 5 星并填写评价，文字最多 2000 字符。'
    return
  }
  busy.value = true
  try {
    if (!await confirm('确认提交评价？', { label: '提交评价', detail: '每个评价场景只能提交一次。' })) return
    const result = await api.post(props.endpoint, { rating: rating.value, content: text })
    if (result?.reviewId == null) throw new Error('未取得评价回执。')
    receipt.value = result.reviewId
    emit('refresh')
  } catch (failure) {
    if (failure.code === 'ALREADY_REVIEWED') duplicate.value = true
    error.value = !failure.status || failure.status >= 500 ? '评价结果尚未确认，正在查询服务器记录。' : failure.message
    if (duplicate.value || !failure.status || failure.status >= 500) emit('refresh')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="review-form" @submit.prevent="submit">
    <h3>{{ title }}</h3>
    <p v-if="receipt" class="notice success" role="status">评价已提交 · #{{ receipt }}</p>
    <template v-else-if="!duplicate">
      <fieldset :disabled="busy">
        <legend>星级（1–5 星）</legend>
        <div class="review-grade">
          <label v-for="star in 5" :key="star">
            <input v-model="rating" type="radio" :value="star" :name="'rating-' + endpoint">
            <span aria-hidden="true">★</span> {{ star }} 星
          </label>
        </div>
        <label>评价内容<textarea v-model="content" class="order-textarea" maxlength="2000" rows="3" required></textarea></label>
        <p class="muted trade-character-count">{{ content.length }} / 2000</p>
      </fieldset>
      <button class="button" type="submit" :disabled="busy">{{ busy ? '正在提交…' : '提交评价' }}</button>
    </template>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </form>
</template>
