<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'
import { checkImage, imageAccept, reviewLabels, sellerLabels } from '../seller.js'

const router = useRouter()
const kind = ref('PERSONAL')
const form = reactive({ shopName: '', shopDescription: '' })
const files = { businessLicense: null, identityDocument: null }
const loading = ref(true)
const ready = ref(false)
const saving = ref(false)
const error = ref('')
const created = ref(null)

watch(kind, () => {
  files.businessLicense = null
  files.identityDocument = null
  error.value = ''
})

async function checkApplicant() {
  loading.value = true
  error.value = ''
  try {
    await api.get('/sellers/me')
    await router.replace({ name: 'seller' })
  } catch (failure) {
    if (failure.code === 'SELLER_NOT_FOUND') ready.value = true
    else error.value = failure.message
  } finally {
    loading.value = false
  }
}

function selectFile(key, event) {
  const input = event.target
  const file = input.files[0]
  error.value = file ? checkImage(file) : ''
  files[key] = error.value ? null : file
  if (error.value) input.value = ''
}

async function submit() {
  if (!ready.value || saving.value) return
  const body = { shopName: form.shopName.trim(), shopDescription: form.shopDescription }
  error.value = ''
  if (!body.shopName) {
    error.value = '请填写店铺名称。'
    return
  }
  if (kind.value === 'BUSINESS' && (!files.businessLicense || !files.identityDocument)) {
    error.value = '请选择营业执照和身份证图片。'
    return
  }
  saving.value = true
  try {
    if (kind.value === 'PERSONAL') {
      created.value = await api.post('/sellers/personal', body)
    } else {
      const formData = new FormData()
      formData.append('application', new Blob([JSON.stringify(body)], { type: 'application/json' }))
      formData.append('businessLicense', files.businessLicense)
      formData.append('identityDocument', files.identityDocument)
      created.value = await api.post('/sellers/business', formData)
    }
    files.businessLicense = null
    files.identityDocument = null
  } catch (failure) {
    error.value = failure.message
  } finally {
    saving.value = false
  }
}

onMounted(checkApplicant)
</script>

<template>
  <section class="seller-workspace seller-narrow">
    <RouterLink class="catalog-back" :to="{ name: 'seller' }">← 卖家中心</RouterLink>
    <header class="stage72-auth-header"><h1>申请成为卖家</h1></header>
    <p v-if="loading" class="empty-panel" role="status">正在检查申请资格…</p>
    <div v-else-if="created" class="seller-panel">
      <p class="notice success" role="status">
        {{ sellerLabels[created.sellerType] }}档案已创建，审核状态：{{ reviewLabels[created.reviewStatus] }}。
      </p>
      <RouterLink class="button" :to="{ name: 'seller' }">查看卖家档案</RouterLink>
    </div>
    <div v-else-if="!ready" class="empty-panel">
      <p role="alert">{{ error }}</p>
      <button class="button button-outline" type="button" @click="checkApplicant">重试</button>
    </div>
    <form v-else class="seller-panel stage72-form" @submit.prevent="submit">
      <fieldset :disabled="saving">
        <legend>卖家类型</legend>
        <div class="seller-options seller-application-options">
          <label><input v-model="kind" type="radio" value="PERSONAL"> 个人卖家</label>
          <label><input v-model="kind" type="radio" value="BUSINESS"> 正式商家</label>
        </div>
        <p class="muted">{{ kind === 'PERSONAL' ? '无需经营资质，无商家等级及平台手续费。' : '需提交两份资质图片。审核通过、启用并获得有效等级后可以经营。' }}</p>
        <div class="stage72-form">
          <label>店铺名称（必填）<input v-model="form.shopName" required maxlength="100"></label>
          <label>店铺简介（选填）<textarea v-model="form.shopDescription" maxlength="500" rows="3"></textarea></label>
        </div>
        <template v-if="kind === 'BUSINESS'">
          <p class="muted">姓名、手机号、性别和银行资料由后端校验现有用户资料。资质仅用于审核，支持 PNG、JPEG，每份最多 5 MB。</p>
          <div class="fields">
            <label class="seller-document-field">营业执照图片（必填）<input required type="file" :accept="imageAccept" @change="selectFile('businessLicense', $event)"></label>
            <label class="seller-document-field">身份证图片（必填）<input required type="file" :accept="imageAccept" @change="selectFile('identityDocument', $event)"></label>
          </div>
        </template>
      </fieldset>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <button class="button" type="submit" :disabled="saving">{{ saving ? '正在提交…' : '提交申请' }}</button>
    </form>
  </section>
</template>
