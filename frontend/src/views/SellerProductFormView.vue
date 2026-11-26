<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute } from 'vue-router'
import { api } from '../api.js'
import { positiveId, productConditions, validatePrices } from '../catalog.js'
import { adjustStock, checkImage, editAllowed, imageAccept, ownedProduct, productPath, reviewLabels } from '../seller.js'
import ProductPhoto from '../components/ProductPhoto.vue'

const route = useRoute()
const isEdit = computed(() => route.name === 'seller-product-edit')
const form = reactive({})
const categories = ref([])
const product = ref(null)
const legacyCondition = computed(() => {
  const code = product.value?.conditionCode
  return code && !Object.hasOwn(productConditions, code) ? code : ''
})
const uploading = ref([])
const uploaded = ref([])
const loading = ref(true)
const busy = ref(false)
const loadError = ref('')
const error = ref('')
const message = ref('')
const retry = ref(0)
const baseSaved = ref(false)
const uncertainCreate = ref(false)
const initialStock = ref('')
let stockKey
let stockSaved = false
let active = true

function clearImages() {
  for (const photo of [...uploading.value, ...uploaded.value]) URL.revokeObjectURL(photo.preview)
  uploading.value = []
  uploaded.value = []
}

onBeforeUnmount(() => {
  active = false
  clearImages()
})
onBeforeRouteUpdate(() => !busy.value)

watch([() => route.name, () => route.params.productId, retry], async ([name, param], _, onCleanup) => {
  if (!['seller-product-new', 'seller-product-edit'].includes(name)) return
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  clearImages()
  product.value = null
  loading.value = true
  loadError.value = ''
  error.value = ''
  message.value = ''
  baseSaved.value = false
  uncertainCreate.value = false
  stockSaved = false
  stockKey = undefined
  initialStock.value = '0'
  const { signal } = controller
  try {
    const id = param ? positiveId(param) : ''
    const [availableCategories, item] = await Promise.all([
      api.get('/categories', { signal }),
      param && id ? ownedProduct(id, signal) : Promise.resolve(null),
    ])
    if (signal.aborted) return
    categories.value = availableCategories
    if (name === 'seller-product-edit' && (!item || !editAllowed(item))) {
      loadError.value = item ? '此商品当前状态不允许编辑。' : '未找到可编辑的商品。'
      return
    }
    product.value = item
    Object.assign(form, {
      categoryId: item?.categoryId == null ? '' : String(item.categoryId), name: item?.name ?? '',
      originalPrice: item?.originalPrice == null ? '' : String(item.originalPrice),
      discountPrice: item?.discountPrice == null ? '' : String(item.discountPrice),
      sizeDescription: item?.sizeDescription ?? '', usageInstructions: item?.usageInstructions ?? '',
      negotiable: item?.negotiable === 1, conditionCode: item?.conditionCode ?? '',
    })
  } catch (failure) {
    if (!signal.aborted) loadError.value = failure.message
  } finally {
    if (!signal.aborted) loading.value = false
  }
}, { immediate: true })

function addImages(event) {
  const files = Array.from(event.target.files)
  event.target.value = ''
  error.value = ''
  message.value = ''
  for (const file of files) {
    const problem = checkImage(file)
    if (problem) {
      error.value = problem
      continue
    }
    if (uploading.value.length + uploaded.value.length >= 10) {
      error.value = '每件商品最多十张图片，已有图片也占用名额。'
      break
    }
    uploading.value.push({ file, preview: URL.createObjectURL(file), uncertain: false })
  }
}

function removePreview(photo) {
  uploading.value = uploading.value.filter(item => item !== photo)
  URL.revokeObjectURL(photo.preview)
}

function validate() {
  if (!form.name.trim()) return '请填写商品名称。'
  if (!categories.value.some(({ id }) => String(id) === form.categoryId)) return '请选择启用的商品分类。'
  if (!form.originalPrice) return '请填写商品原价。'
  const priceError = validatePrices({ minPrice: form.discountPrice, maxPrice: form.originalPrice })
  if (priceError) return priceError.replace('最低价不能高于最高价', '折扣价不能高于原价')
  if (!/^[A-Z][A-Z0-9_]{0,15}$/.test(form.conditionCode)) return '请选择有效的商品成色。'
  if (!isEdit.value && (!/^[0-9]{1,10}$/.test(initialStock.value) || Number(initialStock.value) > 2147483647)) {
    return '初始库存须为 0 到 2147483647 之间的整数。'
  }
  return ''
}

async function persistDetails() {
  const request = {
    ...form, name: form.name.trim(), originalPrice: Number(form.originalPrice),
    discountPrice: form.discountPrice ? Number(form.discountPrice) : null,
  }
  try {
    product.value = isEdit.value ? await api.put(`${productPath}/${product.value.id}`, request)
      : await api.post(productPath, request)
    baseSaved.value = true
  } catch (failure) {
    if (!isEdit.value && (!failure.status || failure.status >= 500)) {
      uncertainCreate.value = true
      error.value = '创建结果无法确认。请先在商品管理中查看是否已有该草稿。'
    }
    throw failure
  }
}

async function save() {
  if (busy.value || uncertainCreate.value) return
  message.value = ''
  error.value = baseSaved.value ? '' : validate()
  if (error.value) return
  if (uploading.value.some(photo => photo.uncertain)) {
    error.value = '有图片的上传结果未确认。请先从待上传队列移除该文件，避免重复上传。'
    return
  }
  busy.value = true
  try {
    if (!baseSaved.value) await persistDetails()
    if (!active) return
    if (!isEdit.value && Number(initialStock.value) > 0 && !stockSaved) {
      stockKey ??= crypto.randomUUID()
      product.value = await adjustStock(product.value.id, Number(initialStock.value), stockKey)
      stockSaved = true
    }
    while (active && uploading.value.length) {
      const photo = uploading.value[0]
      const data = new FormData()
      data.append('image', photo.file)
      try {
        const receipt = await api.post(`${productPath}/${product.value.id}/images`, data)
        if (!active) return
        uploading.value.shift()
        uploaded.value.push({ preview: photo.preview, receipt })
      } catch (failure) {
        photo.uncertain = !failure.status
        throw failure
      }
    }
    message.value = '草稿已保存。可返回商品管理提交审核。'
  } catch (failure) {
    error.value ||= baseSaved.value ? `草稿资料已保存，后续操作未全部完成。 ${failure.message}` : failure.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="seller-workspace seller-narrow">
    <RouterLink class="catalog-back" :to="{ name: 'seller-products' }">← 商品管理</RouterLink>
    <header class="management-page-header"><h1>{{ isEdit ? '编辑商品' : '新建商品' }}</h1></header>
    <p v-if="loading" class="empty-panel" role="status">正在读取商品资料…</p>
    <div v-else-if="loadError" class="empty-panel">
      <p role="alert">{{ loadError }}</p>
      <button class="button button-outline" type="button" @click="retry++">重试</button>
    </div>
    <form v-else class="seller-product-form" @submit.prevent="save">
      <fieldset class="seller-panel" :disabled="busy || baseSaved || uncertainCreate">
        <legend>基本资料</legend>
        <div class="fields">
          <label>商品名称<input v-model="form.name" required maxlength="120"></label>
          <label>商品分类
            <select v-model="form.categoryId" required>
              <option disabled value="">请选择</option>
              <option v-if="form.categoryId && !categories.some(({id}) => String(id) === form.categoryId)"
                disabled :value="form.categoryId">原分类已停用</option>
              <option v-for="category in categories" :key="category.id" :value="String(category.id)">{{ category.name }}</option>
            </select>
          </label>
          <label>尺寸 / 规格（选填）
            <input v-model="form.sizeDescription" maxlength="100" placeholder="例如：M码、42码、14英寸、长30×宽20×高15cm">
          </label>
          <label>商品成色
            <select v-model="form.conditionCode" required>
              <option disabled value="">请选择商品成色</option>
              <option v-if="legacyCondition" :value="legacyCondition">原有成色（{{ legacyCondition }}）</option>
              <option v-for="(label, code) in productConditions" :key="code" :value="code">{{ label }}</option>
            </select>
          </label>
        </div>
        <label>商品描述和使用说明<textarea v-model="form.usageInstructions" maxlength="16000" rows="5"></textarea></label>
        <label class="seller-check"><input v-model="form.negotiable" type="checkbox">接受议价</label>
      </fieldset>
      <fieldset class="seller-panel" :disabled="busy || baseSaved || uncertainCreate">
        <legend>价格和库存</legend>
        <div class="fields">
          <label>原价（元）<input v-model.trim="form.originalPrice" required inputmode="decimal" maxlength="15"></label>
          <label>折扣价（元）<input v-model.trim="form.discountPrice" inputmode="decimal" maxlength="15" placeholder="无折扣可留空"></label>
          <label v-if="!isEdit">初始可售库存<input v-model.trim="initialStock" required inputmode="numeric" maxlength="10"></label>
        </div>
        <p v-if="isEdit && product">当前可售 {{ product.availableQuantity }} 件，预留 {{ product.reservedQuantity }} 件。库存变更在商品管理中操作。</p>
      </fieldset>
      <fieldset class="seller-panel" :disabled="busy || uncertainCreate">
        <legend>商品图片</legend>
        <label class="management-upload">选择商品图片
          <span class="muted">可多选 PNG 或 JPEG，每张最多 5 MB。首张上传图片为主图。</span>
          <input type="file" :accept="imageAccept" multiple @change="addImages">
        </label>
        <p v-if="isEdit" class="muted">可追加图片。未上架的原图无法在此预览，保存时会保留。</p>
        <ul v-if="uploading.length || uploaded.length" class="upload-gallery">
          <li v-for="photo in uploading" :key="photo.preview">
            <ProductPhoto :src="photo.preview" :alt="photo.file.name" />
            <span>{{ photo.uncertain ? '上传结果待确认' : '待上传' }}</span>
            <button type="button" class="text-button" @click="removePreview(photo)">移除</button>
          </li>
          <li v-for="photo in uploaded" :key="photo.preview">
            <ProductPhoto :src="photo.preview" alt="本次已上传图片的预览" />
            <span>{{ photo.receipt.primary ? '已上传 · 主图' : '已上传' }}</span>
          </li>
        </ul>
      </fieldset>
      <p v-if="!categories.length" class="notice seller-note">当前没有可用的商品分类。</p>
      <p v-if="baseSaved" class="muted">商品编号 {{ product.id }} · {{ reviewLabels[product.reviewStatus] }}</p>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <p v-else-if="message" class="notice success" role="status">{{ message }}</p>
      <div class="actions management-form-actions">
        <button v-if="!uncertainCreate" type="submit" class="button" :disabled="busy || !categories.length || !!message">
          {{ busy ? '正在保存…' : baseSaved ? '继续保存' : '保存草稿' }}
        </button>
        <RouterLink class="button button-outline" :to="{ name: 'seller-products' }">返回商品管理</RouterLink>
      </div>
    </form>
  </section>
</template>
