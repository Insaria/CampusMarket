<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api'

const status = ref('正在连接后端…')

onMounted(async () => {
  try {
    const { data } = await api.get('/health')
    status.value = data?.status === 'UP' ? '后端已连接（UP）' : '后端返回了意外状态'
  } catch {
    status.value = '无法连接后端，请确认后端已启动。'
  }
})
</script>

<template>
  <main>
    <h1>校园二手商品交易平台</h1>
    <p role="status">{{ status }}</p>
  </main>
</template>

<style scoped>
main {
  max-width: 42rem;
  margin: 4rem auto;
  padding: 0 1rem;
  font-family: system-ui, sans-serif;
}
</style>
