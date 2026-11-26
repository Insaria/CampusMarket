<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, getCsrfToken } from '../api.js'

const fields = reactive({
  loginName: '', password: '', realName: '', phone: '', email: '', city: '',
  genderCode: '', bankAccount: '', captcha: '',
})
const pending = ref(false)
const loadingImage = ref(false)
const imageUrl = ref('')
const error = ref('')
const imageError = ref('')
const registered = ref(false)
let mounted = true

function releaseImage() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}

async function loadCaptcha() {
  if (loadingImage.value) return
  loadingImage.value = true
  fields.captcha = ''
  imageError.value = ''
  releaseImage()
  try {
    // Establish the anonymous session before the image request sets its challenge.
    await getCsrfToken()
    const image = await api.get('/auth/captcha', { responseType: 'blob' })
    if (mounted) imageUrl.value = URL.createObjectURL(image)
  } catch (failure) {
    if (mounted) imageError.value = failure.message
  } finally {
    loadingImage.value = false
  }
}

async function submit() {
  if (pending.value || loadingImage.value || !imageUrl.value) return
  const loginName = fields.loginName.replace(/^ +| +$/g, '')
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(loginName)) {
    error.value = '登录名须为 1–64 位字母、数字、句点、下划线或连字符。'
    return
  }
  if (!fields.password.trim() || !fields.realName.trim() || !fields.city.trim()) {
    error.value = '密码、姓名和城市须包含非空白字符。'
    return
  }
  pending.value = true
  error.value = ''
  try {
    await api.post('/auth/register', { ...fields, loginName })
    registered.value = true
    fields.password = ''
    fields.bankAccount = ''
    fields.captcha = ''
    releaseImage()
  } catch (failure) {
    error.value = failure.message
    if (mounted) await loadCaptcha()
  } finally {
    pending.value = false
  }
}

onMounted(loadCaptcha)
onBeforeUnmount(() => {
  mounted = false
  fields.password = ''
  fields.bankAccount = ''
  releaseImage()
})
</script>

<template>
  <section class="auth-card auth-card-wide stage72-auth" aria-labelledby="register-title">
    <header class="stage72-auth-header">
      <h1 id="register-title">注册账号</h1>
      <p v-if="!registered" class="muted">请填写所有字段。管理员审核通过后即可登录。</p>
    </header>
    <template v-if="registered">
      <p class="notice success" role="status">注册成功。账号正在等待管理员审核，审核通过后即可登录。</p>
      <RouterLink class="button" :to="{ name: 'login' }">返回登录</RouterLink>
    </template>
    <template v-else>
      <form class="stage72-form" @submit.prevent="submit">
        <fieldset class="auth-fieldset">
          <legend>登录信息</legend>
          <div class="fields">
          <label>登录名<input v-model="fields.loginName" required autocomplete="username" autocapitalize="none"></label>
          <label>密码<input v-model="fields.password" required type="password" autocomplete="new-password" minlength="8" maxlength="256" placeholder="至少 8 个字符"></label>
          </div>
        </fieldset>
        <fieldset class="auth-fieldset">
          <legend>个人资料</legend>
          <div class="fields">
          <label>姓名<input v-model="fields.realName" required autocomplete="name" maxlength="64"></label>
          <label>手机号<input v-model="fields.phone" required type="tel" autocomplete="tel" pattern="\+[1-9][0-9]{1,14}" placeholder="含国家或地区区号，如 +86"></label>
          <label>邮箱<input v-model="fields.email" type="email" autocomplete="email" maxlength="254" required></label>
          <label>城市<input v-model="fields.city" required autocomplete="address-level2" maxlength="64"></label>
          <label>性别
            <select v-model="fields.genderCode" required>
              <option value="" disabled>请选择</option>
              <option value="MALE">男</option>
              <option value="FEMALE">女</option>
              <option value="OTHER">其他</option>
            </select>
          </label>
          <label>银行账号<input v-model="fields.bankAccount" required type="password" inputmode="numeric" autocomplete="off" pattern="[0-9]{16}" maxlength="16" placeholder="16 位数字"></label>
          </div>
        </fieldset>
        <fieldset class="auth-fieldset">
          <legend>安全验证</legend>
          <div class="auth-captcha">
            <label>图片验证码<input v-model="fields.captcha" required autocomplete="off" pattern="[A-Za-z0-9]{6}" maxlength="6" placeholder="输入图片中的 6 个字符"></label>
            <div class="captcha-row">
              <img v-if="imageUrl" :src="imageUrl" alt="注册验证码图片" width="180" height="56">
              <span v-else-if="loadingImage" role="status">正在加载验证码…</span>
              <button type="button" class="button button-outline" :disabled="loadingImage || pending" @click="loadCaptcha">
                {{ imageUrl ? '刷新验证码' : '获取验证码' }}
              </button>
            </div>
          </div>
        </fieldset>
        <p v-if="imageError" class="notice error" role="alert">{{ imageError }}</p>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <button class="button" type="submit" :disabled="pending || loadingImage || !imageUrl">
          {{ pending ? '正在提交…' : '提交注册' }}
        </button>
      </form>
      <p class="stage72-auth-footer">已有账号？<RouterLink :to="{ name: 'login' }">返回登录</RouterLink></p>
    </template>
  </section>
</template>
