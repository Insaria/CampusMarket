<script setup>
defineProps({ state: String, label: String })
</script>

<template>
  <span class="status-tag" :data-state="state">{{ label || state || '—' }}</span>
</template>
