import { api } from './api.js'

export const sorts = {
  NEWEST: '最新发布',
  PRICE_ASC: '价格从低到高',
  PRICE_DESC: '价格从高到低',
  SALES_DESC: '历史销量优先',
  POSITIVE_RATE_DESC: '好评率优先',
}

// The API accepts uppercase codes. Keep the older, ungraded values usable.
export const productConditions = {
  NEW: '全新',
  USED_99: '99新',
  USED_90: '9成新',
  USED_80: '8成新',
  USED_70: '7成新',
  LIKE_NEW: '近全新',
  GOOD: '成色良好',
  USED: '二手（未分级）',
}

export function productConditionLabel(code) {
  return Object.hasOwn(productConditions, code) ? productConditions[code] : code || '未提供'
}

export function catalogFilters(query) {
  const read = (key) => typeof query[key] === 'string' ? query[key].trim() : ''
  return {
    keyword: read('keyword').slice(0, 120),
    categoryId: positiveId(read('categoryId')),
    sellerId: positiveId(read('sellerId')),
    minPrice: read('minPrice'),
    maxPrice: read('maxPrice'),
    sort: Object.hasOwn(sorts, read('sort')) ? read('sort') : 'NEWEST',
    page: /^[1-9][0-9]{0,9}$/.test(read('page')) && Number(read('page')) <= 2147483647
      ? Number(read('page')) : 1,
  }
}

export function positiveId(value) {
  return /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n ? value : ''
}

export function validatePrices({ minPrice, maxPrice }) {
  const valid = (price) => !price || /^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(price)
  if (!valid(minPrice) || !valid(maxPrice)) return '请输入非负价格，整数最多 12 位，小数最多 2 位。'
  if (minPrice && maxPrice && Number(minPrice) > Number(maxPrice)) return '最低价不能高于最高价。'
  return ''
}

export function listQuery(filters) {
  return Object.fromEntries(Object.entries(filters).filter(([key, value]) =>
    value && !(key === 'sort' && value === 'NEWEST') && !(key === 'page' && value === 1)))
}

const pendingImages = new Map()

export function imagePositions(id) {
  if (!pendingImages.has(id)) {
    const request = api.get(`/products/${id}/images`).finally(() => pendingImages.delete(id))
    pendingImages.set(id, request)
  }
  return pendingImages.get(id)
}

export function imageUrl(id, position) {
  return `/api/products/${id}/images/${position}`
}

export function money(amount) {
  return '¥' + Number(amount).toFixed(2)
}

export function percent(rate) {
  return new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 2 }).format(rate)
}
