import { computed, ref, watch } from 'vue'
import { api } from './api.js'

export const buyerOrders = '/orders'
export const sellerOrders = '/sellers/me/orders'
export const sellerReturns = '/sellers/me/return-requests'
export const returnLabels = { PENDING: '待审核', APPROVED: '退货获批', REJECTED: '退货被拒绝', RETURNED: '已验收入库' }
export const refundLabels = {
  PENDING_CASH: '现金退款待处理',
  PENDING_POINTS: '等待处理积分',
  CASH_REFUNDED: '现金已退回，退款流程仍在处理',
  COMPLETED: '退款流程已完成',
}
export const fundingLabels = { ESCROW: '原托管资金', SELLER_WALLET: '卖家钱包', POINTS_ONLY: '积分退款' }

export function orderLabel(status) {
  return { PENDING_PAYMENT: '待支付', PAID: '待交付', DELIVERED: '待收货', COMPLETED: '已完成', CANCELLED: '已取消' }[status] ?? status
}

export function useOrderList(endpoint) {
  const status = ref('')
  const page = ref(1)
  const retry = ref(0)
  const { data, loading, error } = useOrderRead([endpoint, status, page, retry], async ([path, filter, number], signal) => {
    const result = await api.get(path, { params: { status: filter || undefined, page: number, size: 20 }, signal })
    if (!signal.aborted && number > 1 && !result.items.length && result.total <= (number - 1) * result.size) {
      page.value = Math.max(1, Math.ceil(result.total / result.size))
    }
    return result
  })
  const pages = computed(() => data.value ? Math.ceil(data.value.total / data.value.size) : 0)
  function selectStatus(value) {
    page.value = 1
    status.value = value
  }
  return { status, page, retry, data, loading, error, pages, selectStatus }
}

export function useOrderRead(sources, read) {
  const data = ref(null)
  const loading = ref(true)
  const error = ref('')
  watch(sources, async (values, _, onCleanup) => {
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    const { signal } = controller
    data.value = null
    loading.value = true
    error.value = ''
    try {
      const result = await read(values, signal)
      if (!signal.aborted) data.value = result
    } catch (failure) {
      if (!signal.aborted) error.value = failure.message
    } finally {
      if (!signal.aborted) loading.value = false
    }
  }, { immediate: true })
  return { data, loading, error }
}
