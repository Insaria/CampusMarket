<script setup>
defineProps({
  modelValue: { type: String, default: '' },
  inputId: { type: String, required: true },
  compact: Boolean,
  placeholder: { type: String, default: '搜索商品名称' },
})
defineEmits(['update:modelValue'])
</script>

<template>
  <label class="search-field" :for="inputId">
    <span :class="{ 'visually-hidden': compact }">商品名称</span>
    <span class="search-input">
      <span class="search-icon" aria-hidden="true"></span>
      <input :id="inputId" :value="modelValue" type="search" maxlength="120"
        :placeholder="placeholder" @input="$emit('update:modelValue', $event.target.value)">
    </span>
  </label>
</template>
