<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { session } from '../auth.js'
import { imagePositions, imageUrl, money, percent, positiveId, productConditionLabel } from '../catalog.js'
import { useOrderRead } from '../orders.js'
import FavoriteButton from '../components/FavoriteButton.vue'
import AddToCart from '../components/AddToCart.vue'
import PageNav from '../components/PageNav.vue'
import ProductPhoto from '../components/ProductPhoto.vue'
import ContactSeller from '../components/ContactSeller.vue'

const route = useRoute()
const router = useRouter()
const id = computed(() => positiveId(route.params.productId))
const reviewPage = computed(() => {
  const page = route.query.reviewPage
  return typeof page === 'string' && /^[1-9][0-9]{0,9}$/.test(page) && Number(page) <= 2147483647 ? Number(page) : 1
})
const detail = ref(null)
const positions = ref([])
const selected = ref(null)
const productError = ref('')
const shopClosed = ref(false)
const imageError = ref('')
const loading = ref(true)
const retryProduct = ref(0)
const reviews = ref(null)
const reviewLoading = ref(true)
const reviewError = ref('')
const retryReviews = ref(0)
const reviewPages = computed(() => reviews.value ? Math.ceil(reviews.value.summary.reviewCount / reviews.value.size) : 0)
const categories = ref([])
const category = computed(() => categories.value.find(({ id }) => id === detail.value?.categoryId))
const savings = computed(() => detail.value ? Number(detail.value.originalPrice) - Number(detail.value.price) : 0)
const selectedIndex = computed(() => positions.value.indexOf(selected.value))
const viewerId = computed(() => session.user?.roleCode === 'USER' ? session.user.userId : null)
const sellerRetry = ref(0)
const { data: ownSeller, loading: sellerLoading, error: sellerError } = useOrderRead([viewerId, sellerRetry],
  async ([userId], signal) => {
    if (userId == null) return null
    try {
      return await api.get('/sellers/me', { signal })
    } catch (failure) {
      if (failure.code === 'SELLER_NOT_FOUND') return null
      throw failure
    }
  })
// The catalog's sellerId identifies a seller profile, not a user account.
const ownsProduct = computed(() => ownSeller.value?.sellerId != null
  && String(ownSeller.value.sellerId) === String(detail.value?.sellerId))

onMounted(async () => {
  try { categories.value = await api.get('/categories') } catch { /* The product still renders without category names. */ }
})

function moveImage(delta) {
  if (positions.value.length < 2) return
  const count = positions.value.length
  selected.value = positions.value[(selectedIndex.value + delta + count) % count]
}

watch([id, retryProduct], async ([productId], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  detail.value = null
  positions.value = []
  selected.value = null
  productError.value = ''
  shopClosed.value = false
  imageError.value = ''
  loading.value = Boolean(productId)
  if (!productId) {
    productError.value = '商品不存在或已下架。'
    return
  }
  try {
    const product = await api.get(`/products/${productId}`)
    if (!current) return
    detail.value = product
  } catch (failure) {
    if (current) {
      shopClosed.value = failure.code === 'SHOP_SUSPENDED'
      productError.value = failure.code === 'PRODUCT_NOT_FOUND' ? '商品不存在或已下架。' : failure.message
    }
  } finally {
    if (current) loading.value = false
  }
  if (!current || !detail.value) return
  try {
    const images = await imagePositions(productId)
    if (current) {
      positions.value = images
      selected.value = images[0] ?? null
    }
  } catch {
    if (current) imageError.value = '图片暂时无法加载。'
  }
}, { immediate: true })

function selectReviewPage(page, replace = false) {
  const query = { ...route.query, reviewPage: page === 1 ? undefined : page }
  return replace ? router.replace({ query }) : router.push({ query })
}

watch([detail, reviewPage, retryReviews], async ([product, page], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  reviews.value = null
  reviewError.value = ''
  reviewLoading.value = Boolean(product)
  if (!product) return
  try {
    const result = await api.get(`/products/${product.id}/reviews`, { params: { page, size: 8 } })
    if (!current) return
    const lastPage = Math.max(1, Math.ceil(result.summary.reviewCount / result.size))
    if (page > lastPage) {
      await selectReviewPage(lastPage, true)
      return
    }
    reviews.value = result
  } catch (failure) {
    if (current) reviewError.value = failure.message
  } finally {
    if (current) reviewLoading.value = false
  }
}, { immediate: true })
</script>

<template>
  <RouterLink class="catalog-back" :to="{ name: 'categories' }">← 浏览商品</RouterLink>
  <p v-if="loading" class="empty-panel" role="status">正在加载商品…</p>
  <div v-else-if="productError" class="empty-panel">
    <p role="alert">{{ productError }}</p>
    <FavoriteButton v-if="shopClosed && session.user?.roleCode === 'USER'" :product-id="id" />
    <button v-if="id && productError !== '商品不存在或已下架。'" class="button button-outline"
      @click="retryProduct++">重试</button>
    <RouterLink v-else class="button button-outline" :to="{ name: 'categories' }">查看其他商品</RouterLink>
  </div>
  <template v-else-if="detail">
    <div class="detail-grid">
      <section class="detail-gallery" aria-label="商品图片">
        <div class="detail-image-stage" @keydown.left.prevent="moveImage(-1)" @keydown.right.prevent="moveImage(1)">
          <ProductPhoto :src="selected === null ? '' : imageUrl(detail.id, selected)" :alt="detail.name" />
          <template v-if="positions.length > 1">
            <button class="button button-outline gallery-previous" type="button" aria-label="上一张商品图" @click="moveImage(-1)">‹</button>
            <button class="button button-outline gallery-next" type="button" aria-label="下一张商品图" @click="moveImage(1)">›</button>
            <span class="gallery-count" role="status">{{ selectedIndex + 1 }} / {{ positions.length }}</span>
          </template>
        </div>
        <p v-if="imageError" class="muted">{{ imageError }}</p>
        <div v-if="positions.length > 1" class="thumb-strip">
          <button v-for="(position, index) in positions" :key="position" type="button"
            :aria-label="'查看第 ' + (index + 1) + ' 张商品图'" :aria-pressed="selected === position"
            @click="selected = position">
            <ProductPhoto :src="imageUrl(detail.id, position)" lazy />
          </button>
        </div>
      </section>
      <section class="detail-info">
        <RouterLink v-if="category" class="detail-category"
          :to="{ name: 'categories', query: { categoryId: category.id } }">{{ category.name }}</RouterLink>
        <h1>{{ detail.name }}</h1>
        <div class="detail-price-box">
          <p class="detail-price">
            <strong class="product-price">{{ money(detail.price) }}</strong>
            <span v-if="savings > 0">原价 <del>{{ money(detail.originalPrice) }}</del></span>
            <span v-if="savings > 0" class="stock-chip">省 {{ money(savings) }}</span>
          </p>
          <p class="detail-availability">
            <span v-if="detail.availableQuantity > 0" class="status-tag" data-state="ACTIVE">可售 {{ detail.availableQuantity }} 件</span>
            <span v-else class="stock-chip sold-out">已售罄</span>
            <span v-if="detail.negotiable === 1" class="stock-chip">可议价</span>
          </p>
        </div>
        <dl class="detail-facts">
          <div><dt>商品成色</dt><dd>{{ productConditionLabel(detail.conditionCode) }}</dd></div>
          <div><dt>议价</dt><dd>{{ detail.negotiable === 1 ? '支持咨询议价' : '不议价' }}</dd></div>
          <div v-if="detail.sizeDescription"><dt>尺寸 / 规格</dt><dd>{{ detail.sizeDescription }}</dd></div>
        </dl>
        <div class="detail-actions">
          <AddToCart :key="'cart-' + id" :product="detail" />
          <RouterLink v-if="ownsProduct && session.access === 'MERCHANT'" class="button button-outline"
            :to="{ name: 'seller-products' }">管理商品</RouterLink>
          <ContactSeller v-else-if="!session.user || (viewerId != null && !sellerLoading && !sellerError && !ownsProduct)"
            :key="'contact-' + id" :product-id="detail.id" :negotiable="detail.negotiable === 1" />
          <p v-else-if="viewerId != null && sellerError" class="notice error" role="alert">
            暂时无法确认能否联系卖家。<button type="button" class="text-button" @click="sellerRetry++">重试</button>
          </p>
          <FavoriteButton :key="id" :product-id="id" />
        </div>
        <div class="detail-seller">
          <div><span class="muted">卖家店铺</span><strong>{{ detail.shopName }}</strong></div>
          <RouterLink class="button button-outline" :to="{ name: 'categories', query: { sellerId: detail.sellerId } }">
            查看店铺商品
          </RouterLink>
        </div>
        <section class="detail-description" aria-labelledby="detail-notes">
          <h2 id="detail-notes">商品说明</h2>
          <p class="product-description">{{ detail.usageInstructions || '卖家暂无补充说明。' }}</p>
        </section>
      </section>
    </div>

    <section class="catalog-section" aria-labelledby="reviews-heading">
      <h2 id="reviews-heading">商品评价</h2>
      <p v-if="reviewLoading" class="empty-panel" role="status">正在加载评价…</p>
      <div v-else-if="reviewError" class="empty-panel">
        <p role="alert">{{ reviewError }}</p>
        <button class="button button-outline" @click="retryReviews++">重试评价</button>
      </div>
      <template v-else-if="reviews">
        <p class="review-summary">
          <strong>{{ reviews.summary.averageRating == null ? '暂无星级评分' : Number(reviews.summary.averageRating).toFixed(1) + ' / 5 分' }}</strong>
          <span>好评率 {{ reviews.summary.positiveRate == null ? '—' : percent(reviews.summary.positiveRate) }}</span>
          <span>{{ reviews.summary.reviewCount }} 条评价 · {{ reviews.summary.ratedCount }} 条含星级</span>
        </p>
        <ul v-if="reviews.items.length" class="review-list">
          <li v-for="review in reviews.items" :key="review.id">
            <div class="review-meta">
              <strong v-if="review.rating != null" class="review-stars">
                <span aria-hidden="true">{{ '★'.repeat(review.rating) }}{{ '☆'.repeat(5 - review.rating) }}</span>
                <span class="visually-hidden">{{ review.rating }} / 5 星</span>
              </strong>
              <span v-else class="muted">文字评价</span>
              <time :datetime="review.createdAt">{{ review.createdAt?.slice(0, 10) }}</time>
            </div>
            <p v-if="review.content" class="product-description">{{ review.content }}</p>
          </li>
        </ul>
        <p v-else class="empty-panel">暂无商品评价。</p>
        <PageNav :page="reviews.page" :pages="reviewPages" label="评价分页" @change="selectReviewPage" />
      </template>
    </section>
  </template>
</template>
