import { reactive, readonly } from 'vue'
import { api, clearCsrfToken, getCsrfToken } from './api.js'
import { clearCartViews } from './cart.js'

const state = reactive({ user: null, access: null, ready: false, error: '' })
export const session = readonly(state)
export const sellerHistoryAllowed = () => ['MERCHANT', 'SUSPENDED_SELLER'].includes(state.access)
let restoration

export function clearUser() {
  state.user = null
  state.access = null
  clearCsrfToken()
  clearCartViews()
}

function lostSession(error) {
  return error.status === 401 || error.code === 'ACCOUNT_UNAVAILABLE'
}

async function refreshIdentity() {
  const user = await api.get('/auth/me')
  state.user = user
  state.access = user.roleCode
  if (user.roleCode === 'USER') {
    try {
      await refreshSellerAccess()
    } catch (error) {
      if (lostSession(error)) throw error
      state.error = '暂时无法确认卖家经营权限。'
    }
  }
}

export async function refreshSellerAccess() {
  const user = state.user
  if (user?.roleCode !== 'USER') return false
  try {
    // The seller product endpoint checks the full server-side MERCHANT eligibility.
    await api.get('/sellers/me/products', { params: { size: 1 } })
    if (state.user !== user) return false
    state.access = 'MERCHANT'
    return true
  } catch (error) {
    if (state.user !== user) return false
    state.access = 'USER'
    if (lostSession(error)) {
      clearUser()
      throw error
    }
    if (error.code !== 'ACCESS_DENIED' && error.code !== 'SELLER_NOT_ACTIVE') throw error
    try {
      const profile = await api.get('/sellers/me')
      if (state.user === user && profile.reviewStatus === 'APPROVED' && profile.operationStatus === 'SUSPENDED') {
        state.access = 'SUSPENDED_SELLER'
      }
    } catch (failure) {
      if (state.user !== user) return false
      if (lostSession(failure)) clearUser()
      if (failure.code !== 'SELLER_NOT_FOUND') throw failure
    }
    return false
  }
}

export async function restoreSession() {
  if (state.ready) return
  if (!restoration) {
    restoration = refreshIdentity().catch((error) => {
      clearUser()
      if (error.status !== 401) state.error = error.message
    }).finally(() => {
      state.ready = true
    })
  }
  await restoration
}

export async function login(input) {
  const identity = await api.post('/auth/login', input)
  clearCsrfToken()
  state.error = ''
  state.user = identity
  state.access = identity.roleCode
  try {
    await refreshIdentity()
  } catch (error) {
    if (lostSession(error)) {
      clearUser()
      throw error
    }
    state.error = error.message
  }
  try {
    await getCsrfToken()
  } catch (error) {
    state.error = error.message
  }
  state.ready = true
}

export async function logout() {
  await api.post('/auth/logout')
  clearUser()
  state.error = ''
}
