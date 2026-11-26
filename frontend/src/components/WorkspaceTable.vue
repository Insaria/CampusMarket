<script setup>
defineProps({ label: { type: String, required: true } })
</script>

<template>
  <div class="admin-table-wrap workspace-table" tabindex="0" role="region" :aria-label="label">
    <slot />
  </div>
</template>
