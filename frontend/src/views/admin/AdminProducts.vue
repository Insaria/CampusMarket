<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminMoney, adminPageSize, adminProducts, useAdminRead } from '../../admin.js'
import { api } from '../../api.js'
import { listingLabels, reviewLabels } from '../../seller.js'
import AdminFeedback from '../../components/AdminFeedback.vue'
import ProductPhoto from '../../components/ProductPhoto.vue'
import StatusBadge from '../../components/StatusBadge.vue'
import WorkspaceTable from '../../components/WorkspaceTable.vue'
import { useWorkspaceConfirm } from '../../workspace.js'

const { data: items, loading, error, run } = useAdminRead()
const categories = useAdminRead()
const detail = useAdminRead()
const categoryNames = computed(() => new Map(categories.data.value?.map(({ id, name }) => [id, name])))
const page = ref(1)
const selected = ref(null)
const busy = ref(false)
const actionError = ref('')
const message = ref('')
const reason = ref('')
const confirm = useWorkspaceConfirm()

function loadCategories() {
  categories.run(signal => api.get('/categories', { signal }))
}

function load(number = page.value) {
  page.value = number
  return run(signal => api.get(`${adminProducts}/pending`, { params: { page: number, size: adminPageSize }, signal }))
}

function show(product) {
  selected.value = product
  actionError.value = ''
  message.value = ''
  reason.value = ''
  loadDetail(product.id)
}

function loadDetail(id) {
  detail.run(signal => api.get(`${adminProducts}/${id}`, { signal }))
}

async function review(decision) {
  const product = selected.value
  const label = decision === 'APPROVED' ? '批准' : '拒绝'
  if (busy.value || product?.reviewStatus !== 'PENDING') return
  if (decision === 'REJECTED' && !reason.value) {
    actionError.value = '拒绝商品时请填写审核意见。'
    return
  }
  if (!(await confirm(`确认${label}商品「${product.name}」？`, { label: label + '商品' }))) return
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    selected.value = await api.patch(`${adminProducts}/${product.id}/review`, { decision, reviewReason: reason.value || null })
    message.value = `已${label}商品。`
    loadDetail(product.id)
    await load()
    if (page.value > 1 && items.value?.length === 0) await load(page.value - 1)
  } catch (failure) {
    actionError.value = failure.message
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  load()
  loadCategories()
})
</script>

<template>
  <header class="management-page-header seller-heading">
    <h1>商品审核</h1>
    <button class="button button-outline" type="button" :disabled="busy || loading" @click="load()">刷新队列</button>
  </header>
  <p v-if="categories.error.value" class="notice seller-note" role="status">
    分类名称暂不可用，当前显示编号。<button class="text-button" type="button" @click="loadCategories">重试分类</button>
  </p>
  <AdminFeedback :loading="loading" :error="error" @retry="load()">
    <WorkspaceTable v-if="items?.length" label="待审核商品列表">
      <table class="admin-table">
        <thead><tr><th>商品</th><th>分类</th><th>售价</th><th>审核</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in items" :key="item.id">
          <th scope="row">{{ item.name }}</th>
          <td>{{ categoryNames.get(item.categoryId) ?? '#' + item.categoryId }}</td>
          <td>{{ adminMoney(item.discountPrice ?? item.originalPrice) }}</td>
          <td><StatusBadge :state="item.reviewStatus" :label="reviewLabels[item.reviewStatus]" /></td>
          <td><button class="button button-outline" type="button" :disabled="busy" @click="show(item)">查看商品</button></td>
        </tr></tbody>
      </table>
    </WorkspaceTable>
    <p v-else class="empty-panel">{{ page === 1 ? '暂无待审核商品。' : '本页暂无待审核商品。' }}</p>
  </AdminFeedback>
  <nav v-if="page > 1 || items?.length === adminPageSize" class="page-nav" aria-label="商品审核分页">
    <button class="button button-outline" type="button" :disabled="busy || loading || page <= 1" @click="load(page - 1)">上一页</button>
    <span>第 {{ page }} 页</span>
    <button class="button button-outline" type="button" :disabled="busy || loading || items?.length !== adminPageSize"
      @click="load(page + 1)">下一页</button>
  </nav>
  <section v-if="selected" class="seller-panel admin-detail" aria-labelledby="admin-product-detail">
    <div class="seller-heading">
      <h2 id="admin-product-detail">{{ selected.name }}</h2>
      <button class="button button-outline" type="button" :disabled="busy" @click="selected = null; detail.reset()">关闭</button>
    </div>
    <dl class="seller-metrics">
      <div><dt>商品编号</dt><dd>{{ selected.id }}</dd></div>
      <div><dt>分类</dt><dd>{{ categoryNames.get(selected.categoryId) ?? '#' + selected.categoryId }}</dd></div>
      <div><dt>原价</dt><dd>{{ adminMoney(selected.originalPrice) }}</dd></div>
      <div><dt>折扣价</dt><dd>{{ adminMoney(selected.discountPrice) }}</dd></div>
      <div><dt>库存</dt><dd>可售 {{ selected.availableQuantity }} 件 · 预留 {{ selected.reservedQuantity }} 件</dd></div>
      <div><dt>审核 / 上架</dt><dd>{{ reviewLabels[selected.reviewStatus] }} / {{ listingLabels[selected.listingStatus] }}</dd></div>
      <div><dt>成色</dt><dd>{{ selected.conditionCode }}</dd></div>
      <div><dt>议价</dt><dd>{{ selected.negotiable === 1 ? '可议价' : '不议价' }}</dd></div>
    </dl>
    <p v-if="selected.sizeDescription" class="product-description">尺寸：{{ selected.sizeDescription }}</p>
    <p v-if="selected.usageInstructions" class="product-description">{{ selected.usageInstructions }}</p>
    <AdminFeedback :loading="detail.loading.value" :error="detail.error.value" @retry="loadDetail(selected.id)">
      <template v-if="detail.data.value">
        <p v-if="detail.data.value.seller"><strong>卖家：</strong>{{ detail.data.value.seller.shopName }}
          · #{{ detail.data.value.seller.sellerId }} · {{ detail.data.value.seller.sellerType === 'PERSONAL' ? '个人卖家' : '正式商家' }}</p>
        <div v-if="detail.data.value.imagePositions.length" class="admin-review-images">
          <ProductPhoto v-for="position in detail.data.value.imagePositions" :key="position"
            :src="`/api/admin/products/${selected.id}/images/${position}`" :alt="selected.name + '商品图片'" />
        </div>
        <p v-else class="muted">没有可预览的商品图片。</p>
        <p v-if="selected.reviewStatus !== 'PENDING' && detail.data.value.reviewReason" class="product-description">
          审核意见：{{ detail.data.value.reviewReason }}
        </p>
      </template>
    </AdminFeedback>
    <template v-if="selected.reviewStatus === 'PENDING'">
      <label class="admin-top-gap">审核意见（拒绝时必填）
        <textarea v-model.trim="reason" maxlength="500" rows="3" :disabled="busy"></textarea>
      </label>
      <div class="actions admin-top-gap">
        <button class="button" type="button" :disabled="busy" @click="review('APPROVED')">批准商品</button>
        <button class="button button-outline" type="button" :disabled="busy" @click="review('REJECTED')">拒绝商品</button>
      </div>
    </template>
    <p v-if="actionError" class="notice error admin-top-gap" role="alert">{{ actionError }}</p>
    <p v-if="message" class="notice success admin-top-gap" role="status">{{ message }}</p>
  </section>
</template>
