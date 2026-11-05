import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'

import type { ApiResponse, FieldViolation, TokenResponse } from '@/types/api'
import { tokenStore } from '@/utils/tokenStore'

export const API_BASE = '/api/v1'

/** 后端统一响应体中的错误，或网络错误（status 为 0）。 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId?: string
  readonly details?: unknown
  /** 429 时的 Retry-After（秒） */
  readonly retryAfter?: number

  constructor(
    status: number,
    code: string,
    message: string,
    options: { traceId?: string; details?: unknown; retryAfter?: number } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = options.traceId
    this.details = options.details
    this.retryAfter = options.retryAfter
  }

  /** 字段级校验错误（VALIDATION_FAILED） */
  get violations(): FieldViolation[] {
    return Array.isArray(this.details) ? (this.details as FieldViolation[]) : []
  }
}

export const SESSION_EXPIRED = 'REFRESH_TOKEN_INVALID'

/** 把 Axios 错误或 fetch 响应体转换成 ApiError。 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof AxiosError) {
    const response = error.response as AxiosResponse<ApiResponse<unknown>> | undefined
    if (!response) {
      return new ApiError(0, 'NETWORK_ERROR', '无法连接服务器，请检查网络或后端是否已启动')
    }
    return fromResponseBody(response.status, response.data, response.headers['retry-after'])
  }
  return new ApiError(0, 'UNKNOWN', error instanceof Error ? error.message : String(error))
}

export function fromResponseBody(status: number, body: unknown, retryAfter?: string | null): ApiError {
  const data = body as Partial<ApiResponse<unknown>> | undefined
  if (data && typeof data === 'object' && typeof data.code === 'string') {
    return new ApiError(status, data.code, data.message || '请求失败', {
      traceId: data.traceId,
      details: data.data,
      retryAfter: retryAfter ? Number(retryAfter) : undefined,
    })
  }
  return new ApiError(status, `HTTP_${status}`, status >= 500 ? '服务器暂时不可用，请稍后重试' : '请求失败')
}

// ---------------------------------------------------------------------------
// 会话失效通知：由 main.ts 注册（跳转登录页），这里不直接依赖路由，避免循环引用。

let sessionExpiredHandler: (() => void) | null = null

export function onSessionExpired(handler: () => void) {
  sessionExpiredHandler = handler
}

function sessionExpired(): ApiError {
  tokenStore.clear()
  sessionExpiredHandler?.()
  return new ApiError(401, SESSION_EXPIRED, '登录已失效，请重新登录')
}

// ---------------------------------------------------------------------------
// 刷新 Access Token（§9）。
// Refresh Token 每次使用都会轮换，旧令牌再次使用会被当作泄露、整个令牌族被撤销（§4.8）。
// 所以刷新必须串行：
//   1. 同一页面内 single-flight：同一时刻只发一个刷新请求，其他 401 的请求等待同一个 Promise；
//   2. 多个标签页之间用 Web Locks 互斥，拿到锁后先看令牌是否已被别的标签页换过；
//   3. 失败请求使用的令牌已经不是当前令牌时（别人刚刷新过），直接用新令牌重放，不再刷新。

let inflight: Promise<string> | null = null

/** @param usedToken 失败请求所携带的 Access Token */
export function refreshAccessToken(usedToken?: string | null): Promise<string> {
  const current = tokenStore.accessToken()
  if (usedToken && current && current !== usedToken) {
    return Promise.resolve(current)
  }
  if (!inflight) {
    inflight = withRefreshLock(() => doRefresh(usedToken)).finally(() => {
      inflight = null
    })
  }
  return inflight
}

function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  return locks?.request ? locks.request('kb-agent-token-refresh', task) : task()
}

async function doRefresh(usedToken?: string | null): Promise<string> {
  const current = tokenStore.accessToken()
  if (usedToken && current && current !== usedToken) {
    return current
  }
  const refreshToken = tokenStore.refreshToken()
  if (!refreshToken) {
    throw sessionExpired()
  }
  try {
    // 不走 http 实例，避免刷新请求本身再触发拦截器
    const response = await axios.post<ApiResponse<TokenResponse>>(`${API_BASE}/auth/refresh`, { refreshToken })
    tokenStore.save(response.data.data!)
    return response.data.data!.accessToken
  } catch (error) {
    if (error instanceof AxiosError && error.response?.status === 401) {
      throw sessionExpired()
    }
    // 网络错误或 5xx：保留令牌，用户可以重试
    throw toApiError(error)
  }
}

// ---------------------------------------------------------------------------

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean
}

export const http = axios.create({ baseURL: API_BASE, timeout: 30_000 })

http.interceptors.request.use((config) => {
  const token = tokenStore.accessToken()
  if (token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    const config = error instanceof AxiosError ? (error.config as RetriableConfig | undefined) : undefined
    const response = error instanceof AxiosError ? error.response : undefined
    if (response?.status === 401 && config && !config.url?.startsWith('/auth/')) {
      // 只有 UNAUTHORIZED（Token 无效或过期）值得刷新，并且只刷新一次
      if ((response.data as ApiResponse<unknown> | undefined)?.code === 'UNAUTHORIZED' && !config._retried) {
        config._retried = true
        const usedToken = String(config.headers.Authorization ?? '').replace(/^Bearer /, '')
        const fresh = await refreshAccessToken(usedToken)
        config.headers.Authorization = `Bearer ${fresh}`
        return http(config)
      }
      // 不可恢复：账号已停用，或刷新后仍然 401
      tokenStore.clear()
      sessionExpiredHandler?.()
    }
    throw toApiError(error)
  },
)

/** 解包统一响应体。 */
export async function unwrap<T>(request: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> {
  const response = await request
  return response.data.data as T
}
