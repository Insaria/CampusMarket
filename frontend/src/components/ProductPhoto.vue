<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  lazy: Boolean,
})
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
</script>

<template>
  <div class="product-photo">
    <img v-if="src && !failed" :src="src" :alt="alt" :loading="lazy ? 'lazy' : 'eager'" @error="failed = true">
    <span v-else>暂无图片</span>
  </div>
</template>
