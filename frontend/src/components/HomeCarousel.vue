<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { imagePositions, imageUrl, money } from '../catalog.js'

const props = defineProps({ products: { type: Array, required: true }, categories: { type: Array, required: true } })
const campusImage = '/images/stage71-campus-hero.webp'
const campusSlide = {
  key: 'study', title: '校园闲置，\n继续发光。', description: '翻开下一本书，从二手教材和学习用品开始。',
  action: '浏览教材与学习用品',
}

const slides = computed(() => {
  const category = props.categories.find(item => /教材|学习/.test(item.name))
  const query = category ? { categoryId: category.id } : { keyword: '教材' }
  const products = [...new Map(props.products.map(product => [String(product.id), product])).values()]
  const sellers = new Set()
  const recommendations = [true, false].flatMap(inStock => {
    const preferred = []
    const remaining = []
    for (const product of products.filter(item => (item.availableQuantity > 0) === inStock)) {
      const seller = String(product.sellerId)
      const group = sellers.has(seller) ? remaining : preferred
      group.push(product)
      sellers.add(seller)
    }
    return [...preferred, ...remaining]
  }).slice(0, 4).map(product => ({
      key: 'product-' + product.id, title: product.name, description: product.shopName,
      product, action: '查看商品', to: { name: 'product', params: { productId: product.id } },
  }))
  return [{ ...campusSlide, to: { name: 'categories', query } }, ...recommendations]
})

const activeKey = ref('study')
const activeIndex = computed(() => Math.max(0, slides.value.findIndex(item => item.key === activeKey.value)))
const slide = computed(() => slides.value[activeIndex.value])
const covers = ref({})
const hovered = ref(false)
const focused = ref(false)
const reduceMotion = ref(false)
const pageHidden = ref(false)
const mounted = ref(false)
const rotating = computed(() => mounted.value && slides.value.length > 1 && !hovered.value && !focused.value
  && !reduceMotion.value && !pageHidden.value)
const cover = computed(() => slide.value.product ? covers.value[slide.value.product.id] : campusImage)
const failedCover = ref('')
const showCover = computed(() => cover.value && failedCover.value !== slide.value.key)
let motionQuery
let rotationTimer
let touch
let suppressClickUntil = 0

function select(index) {
  activeKey.value = slides.value[index].key
  restartRotation()
}

function move(direction) {
  const count = slides.value.length
  select((activeIndex.value + direction + count) % count)
}

function restartRotation() {
  window.clearTimeout(rotationTimer)
  if (rotating.value) rotationTimer = window.setTimeout(() => move(1), 5000)
}

function updateMotion() {
  reduceMotion.value = motionQuery.matches
}

function updateVisibility() {
  pageHidden.value = document.hidden
}

function leaveFocus(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) focused.value = false
}

function startTouch(event) {
  const point = event.touches[0]
  touch = event.touches.length === 1 && !event.target.closest('form, button')
    ? { x: point.clientX, y: point.clientY } : null
}

function finishTouch(event) {
  if (!touch) return
  const point = event.changedTouches[0]
  const dx = point.clientX - touch.x
  const dy = point.clientY - touch.y
  touch = null
  if (Math.abs(dx) < 45 || Math.abs(dx) <= Math.abs(dy)) return
  move(dx < 0 ? 1 : -1)
  suppressClickUntil = Date.now() + 350
}

function guardSwipeClick(event) {
  if (event.detail && Date.now() < suppressClickUntil && event.target.closest('a')) {
    event.preventDefault()
    event.stopPropagation()
  }
}

watch(() => slide.value.product?.id, async (id, _, onCleanup) => {
  if (id == null || Object.hasOwn(covers.value, id)) return
  let active = true
  onCleanup(() => { active = false })
  try {
    const [position] = await imagePositions(id)
    if (active) covers.value[id] = position == null ? '' : imageUrl(id, position)
  } catch {
    if (active) covers.value[id] = ''
  }
})

watch(rotating, restartRotation)

onMounted(() => {
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  updateMotion()
  updateVisibility()
  motionQuery.addEventListener('change', updateMotion)
  document.addEventListener('visibilitychange', updateVisibility)
  mounted.value = true
})

onUnmounted(() => {
  window.clearTimeout(rotationTimer)
  motionQuery.removeEventListener('change', updateMotion)
  document.removeEventListener('visibilitychange', updateVisibility)
})
</script>

<template>
  <section class="catalog-hero" aria-label="校园商品推荐" aria-roledescription="轮播图"
    @pointerenter="hovered = $event.pointerType === 'mouse'" @pointerleave="hovered = false"
    @pointerdown="focused = false" @focusin="focused = $event.target.matches(':focus-visible')"
    @focusout="leaveFocus" @keydown="focused = true" @touchstart.passive="startTouch"
    @touchend.passive="finishTouch" @touchcancel="touch = null" @click.capture="guardSwipeClick">
    <div class="hero-content">
      <div :aria-live="rotating ? 'off' : 'polite'">
        <h1 id="market-title">{{ slide.title }}</h1>
        <p>{{ slide.description }}</p>
        <div v-if="slide.product" class="hero-product-info">
          <strong class="product-price">{{ money(slide.product.price) }}</strong>
          <span class="stock-chip">{{ slide.product.availableQuantity > 0
            ? '可售 ' + slide.product.availableQuantity + ' 件' : '已售罄' }}</span>
        </div>
        <RouterLink :to="slide.to" class="hero-link">{{ slide.action }} <span aria-hidden="true">→</span></RouterLink>
      </div>
      <slot />
    </div>
    <RouterLink :to="slide.to" class="hero-media" :aria-label="slide.action + '：' + slide.title.replace('\n', '')">
      <img v-if="showCover" :key="slide.key" class="hero-image" :class="'hero-image--' + (slide.product ? 'product' : slide.key)"
        :src="cover" alt="" :fetchpriority="slide.key === 'study' ? 'high' : 'auto'" decoding="async"
        @error="failedCover = slide.key">
      <span v-else class="hero-image-fallback">{{ slide.product ? '暂无商品图片' : slide.action }}</span>
    </RouterLink>
    <div v-if="slides.length > 1" class="hero-controls" aria-label="轮播控制" role="group"
      @keydown.left.prevent="move(-1)" @keydown.right.prevent="move(1)">
      <button type="button" aria-label="上一张" @click="move(-1)">‹</button>
      <button v-for="(item, index) in slides" :key="item.key" type="button" class="hero-dot"
        :aria-label="'第 ' + (index + 1) + ' 张：' + item.title.replace('\n', '')"
        :aria-current="activeIndex === index ? 'true' : undefined" @click="select(index)"><span aria-hidden="true" /></button>
      <button type="button" aria-label="下一张" @click="move(1)">›</button>
    </div>
  </section>
</template>
