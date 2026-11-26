import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import { api } from './api.js'

export const adminUsers = '/admin/users'
export const adminSellers = '/admin/sellers'
export const adminProducts = '/admin/products'
export const adminSettlements = '/admin/settlements'
export const accountLabels = { ENABLED: '已启用', DISABLED: '已停用', DELETED: '已删除' }
export const adminPageSize = 20

export const adminNavigation = [
  { name: 'admin', label: '工作台' },
  { name: 'admin-users', label: '用户管理' },
  { name: 'admin-sellers', label: '商家审核' },
  { name: 'admin-products', label: '商品审核' },
  { name: 'admin-seller-levels', label: '商家等级' },
  { name: 'admin-settlements', label: '平台结算' },
]

export function paramsOf(values) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== '' && value != null))
}

export function utcLabel(value) {
  return value ? value.replace('T', ' ') + ' UTC' : '—'
}

// Preserve cents from the reported decimal fields without doing any fee calculations.
export function adminMoney(value) {
  if (value == null) return '—'
  const [whole, cents = ''] = String(value).split('.')
  return '¥' + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + cents.padEnd(2, '0')
}

export function useAdminRead() {
  const data = shallowRef(null)
  const loading = ref(false)
  const error = ref('')
  let request

  function reset() {
    request?.abort()
    data.value = null
    loading.value = false
    error.value = ''
  }

  async function run(read) {
    reset()
    const controller = new AbortController()
    request = controller
    loading.value = true
    try {
      const result = await read(controller.signal)
      if (!controller.signal.aborted) data.value = result
    } catch (failure) {
      if (!controller.signal.aborted) error.value = failure.message
    } finally {
      if (!controller.signal.aborted) loading.value = false
    }
  }

  onScopeDispose(() => request?.abort())
  return { data, loading, error, run, reset }
}

export function useAdminPage(path) {
  const state = useAdminRead()
  const page = ref(1)
  const pages = computed(() => state.data.value ? Math.ceil(state.data.value.total / state.data.value.size) : 0)
  let filters = {}

  async function go(number = page.value) {
    page.value = number
    await state.run(async (signal) => {
      const query = { ...filters, page: number, size: adminPageSize }
      const result = await api.get(path, { params: query, signal })
      const last = Math.max(1, Math.ceil(result.total / result.size))
      if (number <= last) return result
      const adjusted = await api.get(path, { params: { ...query, page: last }, signal })
      if (!signal.aborted) page.value = last
      return adjusted
    })
  }

  function search(values) {
    filters = paramsOf(values)
    return go(1)
  }

  return { ...state, page, pages, go, search }
}
