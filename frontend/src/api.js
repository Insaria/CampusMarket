import axios from 'axios'

const safeMethods = new Set(['get', 'head', 'options', 'trace'])
const csrfHeader = 'X-CSRF-TOKEN'
let csrfToken
let csrfRequest

export const api = axios.create({
  baseURL: '/api',
  timeout: 8000,
  withCredentials: true,
})

export class ApiError extends Error {
  constructor(code, message, status, data) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.data = data
  }
}

export function clearCsrfToken() {
  csrfToken = undefined
  csrfRequest = undefined
}

export async function getCsrfToken() {
  if (csrfToken) return csrfToken
  if (csrfRequest) return csrfRequest

  const request = api.get('/auth/csrf').then(({ token } = {}) => {
    if (typeof token !== 'string' || !token) {
      throw new ApiError('INVALID_RESPONSE', '无法取得会话校验令牌。')
    }
    if (csrfRequest === request) csrfToken = token
    return token
  })
  csrfRequest = request
  try {
    return await request
  } finally {
    if (csrfRequest === request) csrfRequest = undefined
  }
}

api.interceptors.request.use(async (request) => {
  if (!safeMethods.has(request.method)) {
    request.headers.set(csrfHeader, await getCsrfToken())
  }
  return request
})

api.interceptors.response.use(
  ({ data, config, headers, status }) => {
    if (config.responseType === 'blob' && headers['content-type']?.startsWith('image/')) return data
    if (config.url === '/health' && data?.status) return data
    if (data?.code === 'OK') return data.data
    throw new ApiError(data?.code ?? 'INVALID_RESPONSE', data?.message ?? '后端响应格式不正确。', status)
  },
  async (error) => {
    if (error instanceof ApiError) throw error
    const { response, config } = error
    if (response?.data?.code === 'INVALID_CSRF_TOKEN' && config && !config.csrfRetried
        && !safeMethods.has(config.method)) {
      if (config.headers.get(csrfHeader) === csrfToken) clearCsrfToken()
      return api.request({ ...config, csrfRetried: true })
    }
    if (!response) throw new ApiError('NETWORK_ERROR', '无法连接后端，请稍后重试。')
    let data = response.data
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      try {
        data = JSON.parse(await data.text())
      } catch {
        data = null
      }
    }
    throw new ApiError(data?.code ?? 'HTTP_ERROR', data?.message
      ?? (response.status === 404 ? '接口不可用，请检查后端是否以 db 模式启动。' : '请求失败，请稍后重试。'),
    response.status, data?.data)
  },
)
