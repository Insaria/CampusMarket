<script setup>
import { computed } from 'vue'

const props = defineProps({
  page: { type: Number, required: true },
  pages: { type: Number, required: true },
  label: { type: String, required: true },
})
defineEmits(['change'])

const visiblePages = computed(() => {
  const start = Math.max(1, Math.min(props.page - 2, props.pages - 4))
  return Array.from({ length: Math.min(5, props.pages) }, (_, index) => start + index)
})
</script>

<template>
  <nav v-if="pages > 1" class="page-nav" :aria-label="label">
    <button class="button button-outline" type="button" :disabled="page <= 1" @click="$emit('change', page - 1)">上一页</button>
    <span class="page-summary" role="status">第 {{ page }} / {{ pages }} 页</span>
    <button v-for="number in visiblePages" :key="number" class="button page-number"
      :class="{ 'button-outline': number !== page }" type="button"
      :aria-current="number === page ? 'page' : undefined" :aria-label="'第 ' + number + ' 页'"
      @click="number !== page && $emit('change', number)">{{ number }}</button>
    <button class="button button-outline" type="button" :disabled="page >= pages" @click="$emit('change', page + 1)">下一页</button>
  </nav>
</template>
