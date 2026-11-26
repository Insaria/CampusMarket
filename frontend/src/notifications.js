import { ref } from 'vue'
import { api } from './api.js'
import { session } from './auth.js'
import { positiveId } from './catalog.js'

export const unreadCount = ref(null)
let request

export function productMessageError(failure, missing = '商品留言暂不可用，请稍后重试。') {
  if (failure.status === 401) return '登录已失效，请重新登录后继续。'
  if (failure.code === 'PRODUCT_MESSAGE_NOT_FOUND' || failure.code === 'PRODUCT_NOT_FOUND' || failure.code === 'SELF_MESSAGE') {
    return failure.message
  }
  if (failure.status === 404 || failure.status >= 500 || failure.code === 'INVALID_RESPONSE') return missing
  return failure.message
}

export async function refreshUnread() {
  request?.abort()
  const user = session.user
  if (!user) {
    unreadCount.value = null
    return
  }
  const controller = new AbortController()
  request = controller
  try {
    const result = await api.get('/notifications/unread-count', { signal: controller.signal })
    if (session.user === user && !controller.signal.aborted) unreadCount.value = result.count
  } catch {
    if (session.user === user && !controller.signal.aborted) unreadCount.value = null
  }
}

export function notificationTarget(item) {
  const id = positiveId(String(item.targetId))
  if (!id) return null
  if (item.targetType === 'PRODUCT_MESSAGE' && item.notificationKind === 'PRODUCT_MESSAGE') {
    return { name: 'product-messages', params: { messageId: id } }
  }
  if (item.targetType === 'RETURN_REQUEST' && item.notificationKind === 'RETURN_APPLIED') {
    return { name: 'seller-return', params: { requestId: id } }
  }
  if (item.targetType === 'PRODUCT'
      && ['PRODUCT_APPROVED', 'PRODUCT_REJECTED'].includes(item.notificationKind)) {
    return { name: 'seller-products' }
  }
  if (item.targetType === 'MERCHANT' && ['SHOP_CLOSED', 'SHOP_REOPENED'].includes(item.notificationKind)) {
    return { name: 'seller' }
  }
  if (item.targetType !== 'ORDER') return null
  const params = { orderId: id }
  switch (item.notificationKind) {
    case 'ORDER_CREATED':
    case 'ORDER_PAID':
    case 'SELLER_APPOINTMENT_PROPOSED':
    case 'SELLER_APPOINTMENT_ACCEPTED':
    case 'ORDER_SETTLED':
    case 'RECEIPT_CONFIRMED': return { name: 'seller-order', params }
    case 'BUYER_ORDER_PAID':
    case 'BUYER_APPOINTMENT_PROPOSED':
    case 'BUYER_APPOINTMENT_ACCEPTED':
    case 'BUYER_RECEIPT_CONFIRMED':
    case 'ORDER_DELIVERED': return { name: 'order', params }
    case 'RETURN_APPROVED':
    case 'RETURN_REJECTED':
    case 'REFUND_COMPLETED': return { name: 'apply-return', params }
    default: return null
  }
}
