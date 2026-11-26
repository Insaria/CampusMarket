<script setup>
defineProps({ loading: Boolean, error: { type: String, default: '' }, label: { type: String, default: '正在加载…' } })
defineEmits(['retry'])
</script>

<template>
  <p v-if="loading" class="empty-panel" role="status">{{ label }}</p>
  <div v-else-if="error" class="empty-panel">
    <p role="alert">{{ error }}</p>
    <button class="button button-outline" type="button" @click="$emit('retry')">重试</button>
  </div>
  <slot v-else />
</template>
