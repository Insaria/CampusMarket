<script setup>
import { ref } from 'vue'
import { adjustStock } from '../seller.js'

const props = defineProps({ product: { type: Object, required: true }, disabled: Boolean })
const emit = defineEmits(['updated', 'busy'])
const input = ref('')
const loading = ref(false)
const retry = ref(null)
const error = ref('')

async function submit() {
  if (loading.value || props.disabled) return
  if (!retry.value) {
    const delta = Number(input.value)
    if (!/^[+-]?[0-9]{1,10}$/.test(input.value) || !delta || delta < -2147483648 || delta > 2147483647) {
      error.value = '请输入非零整数，正数增加库存，负数减少库存。'
      return
    }
    retry.value = { delta, key: crypto.randomUUID() }
  }
  loading.value = true
  emit('busy', true)
  error.value = ''
  try {
    const { delta, key } = retry.value
    emit('updated', await adjustStock(props.product.id, delta, key))
    input.value = ''
    retry.value = null
  } catch (failure) {
    error.value = failure.message
    if (failure.status >= 400 && failure.status < 500) retry.value = null
    else error.value += ' 请重试原操作以确认结果。'
  } finally {
    loading.value = false
    emit('busy', false)
  }
}
</script>

<template>
  <form class="stock-form" @submit.prevent="submit">
    <label>
      调整可售库存
      <input v-model.trim="input" maxlength="11" placeholder="例如 +3 或 -2" required
        :disabled="loading || Boolean(retry) || disabled">
    </label>
    <button class="button button-outline" type="submit" :disabled="loading || disabled">
      {{ loading ? '保存中…' : retry ? '重试调整' : '确认调整' }}
    </button>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </form>
</template>
