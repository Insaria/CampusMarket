<script setup>
import { ref, watch } from 'vue'
import { adminSellers, useAdminRead } from '../admin.js'
import { api } from '../api.js'

const props = defineProps({
  sellerId: { type: Number, required: true },
  kind: { type: String, required: true },
  label: { type: String, required: true },
})
const { data, loading, error, run, reset } = useAdminRead()
const url = ref('')

watch(data, (blob, _, onCleanup) => {
  if (!blob) {
    url.value = ''
    return
  }
  const objectUrl = URL.createObjectURL(blob)
  url.value = objectUrl
  onCleanup(() => URL.revokeObjectURL(objectUrl))
})

function view() {
  run(signal => api.get(`${adminSellers}/${props.sellerId}/documents/${props.kind}`, { responseType: 'blob', signal }))
}
</script>

<template>
  <section class="admin-document">
    <h3>{{ label }}</h3>
    <template v-if="url">
      <img :src="url" :alt="label">
      <button class="button button-outline" type="button" @click="reset">收起{{ label }}</button>
    </template>
    <button v-else class="button button-outline" type="button" :disabled="loading" @click="view">
      {{ loading ? '正在读取…' : '查看' + label }}
    </button>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </section>
</template>
