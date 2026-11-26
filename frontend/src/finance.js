import { computed, ref, shallowRef, watch } from 'vue'
import { api } from './api.js'

export const orderStates = {
  PENDING_PAYMENT: '待支付', PAID: '已支付', CANCELLED: '已取消', DELIVERED: '已交付', COMPLETED: '已完成',
}

export const ledgerKinds = {
  ADMIN_RECHARGE: '管理员充值',
  ORDER_PAYMENT: '订单支付', SELLER_SETTLEMENT: '卖家结算',
  BUYER_REFUND: '买家退款', SELLER_REFUND_RECOVERY: '退款资金冲回',
  ORDER_POINTS_REWARD: '消费奖励', ORDER_POINTS_REDEMPTION: '支付抵扣',
  REFUND_POINTS_RESTORE: '退款恢复', REFUND_POINTS_REVERSE: '奖励冲回',
}

export function useFinancePage(path) {
  const page = ref(1)
  const reload = ref(0)
  const data = shallowRef(null)
  const loading = ref(true)
  const error = ref('')
  const pages = computed(() => data.value ? Math.ceil(data.value.total / data.value.size) : 0)

  watch(path, () => { page.value = 1 })
  watch([path, page, reload], async ([endpoint, number], _, onCleanup) => {
    const request = new AbortController()
    onCleanup(() => request.abort())
    data.value = null
    error.value = ''
    loading.value = true
    try {
      const result = await api.get(endpoint, { params: { page: number, size: 20 }, signal: request.signal })
      if (request.signal.aborted) return
      const last = Math.max(1, Math.ceil(result.total / result.size))
      if (number > last) page.value = last
      else data.value = result
    } catch (failure) {
      if (!request.signal.aborted) error.value = failure.message
    } finally {
      if (!request.signal.aborted) loading.value = false
    }
  }, { immediate: true })

  return { page, pages, data, loading, error, reload }
}

export function notSignedIn(error) {
  return error.status === 401 || error.code === 'ACCOUNT_UNAVAILABLE'
}
