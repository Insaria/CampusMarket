<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../auth.js'
import { loginDestination } from '../router.js'

const router = useRouter()
const route = useRoute()
const loginName = ref('')
const password = ref('')
const pending = ref(false)
const error = ref('')

async function submit() {
  if (pending.value) return
  const name = loginName.value.replace(/^ +| +$/g, '')
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(name)) {
    error.value = '登录名须为 1–64 位字母、数字、句点、下划线或连字符。'
    return
  }
  if (!password.value.trim() || password.value.length > 256) {
    error.value = '请输入有效密码。'
    return
  }
  pending.value = true
  error.value = ''
  try {
    await login({ loginName: name, password: password.value })
    await router.replace(loginDestination(route.query.redirect))
  } catch (failure) {
    error.value = failure.code === 'REGISTRATION_NOT_APPROVED'
      ? '账号尚未通过审核。若已提交注册，请等待审核结果。'
      : failure.message
  } finally {
    password.value = ''
    pending.value = false
  }
}
</script>

<template>
  <section class="auth-card stage72-auth" aria-labelledby="login-title">
    <header class="stage72-auth-header">
      <h1 id="login-title">登录</h1>
      <p class="muted">使用已通过审核的账号登录。</p>
    </header>
    <form class="stage72-form" @submit.prevent="submit">
      <label>登录名<input v-model="loginName" required autocomplete="username" autocapitalize="none"></label>
      <label>密码<input v-model="password" required type="password" autocomplete="current-password" maxlength="256"></label>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <button class="button" type="submit" :disabled="pending">{{ pending ? '正在登录…' : '登录' }}</button>
    </form>
    <p class="stage72-auth-footer">还没有账号？<RouterLink :to="{ name: 'register' }">注册</RouterLink></p>
  </section>
</template>
