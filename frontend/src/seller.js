import { api } from './api.js'

export const productPath = '/sellers/me/products'
export const reviewLabels = { DRAFT: '草稿', PENDING: '待审核', APPROVED: '审核通过', REJECTED: '审核未通过' }
export const listingLabels = { OFF_SHELF: '未上架', ON_SHELF: '已上架' }
export const operationLabels = { INACTIVE: '未启用', ACTIVE: '正常经营', SUSPENDED: '暂时关闭' }
export const sellerLabels = { PERSONAL: '个人卖家', BUSINESS: '正式商家' }
export const imageAccept = 'image/png,image/jpeg,.png,.jpg,.jpeg'

export function editAllowed(product) {
  return ['DRAFT', 'REJECTED'].includes(product.reviewStatus) && product.listingStatus === 'OFF_SHELF'
}

export function checkImage(file) {
  if (!file?.size || file.size > 5 * 1024 * 1024) return '请选择大小不超过 5 MB 的图片。'
  if (file.type ? !['image/png', 'image/jpeg'].includes(file.type) : !/\.(png|jpe?g)$/i.test(file.name)) {
    return '仅支持 PNG 或 JPEG 图片。'
  }
  return ''
}

export function sellerProducts(page = 1, size = 20) {
  return api.get(productPath, { params: { page, size } })
}

export async function ownedProduct(id, signal) {
  // The seller API exposes a paginated list ordered by descending product ID.
  const size = 100
  for (let page = 1; !signal.aborted; page++) {
    const result = await api.get(productPath, { params: { page, size }, signal })
    const item = result.items.find((product) => String(product.id) === id)
    if (item) return item
    if (!result.items.length || page * result.size >= result.total
        || BigInt(result.items.at(-1).id) < BigInt(id)) break
  }
  return null
}

export function adjustStock(id, delta, operationKey) {
  return api.patch(`${productPath}/${id}/stock`, { delta, operationKey })
}
