import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { TokenResponse } from '@/types/api'
import { tokenStore } from '@/utils/tokenStore'

import { ApiError, http, onSessionExpired } from '../http'

function tokens(access: string, refresh: string): TokenResponse {
  return {
    tokenType: 'Bearer',
    accessToken: access,
    expiresIn: 900,
    refreshToken: refresh,
    refreshExpiresIn: 86400,
    user: { id: 1, username: 'u', displayName: 'U', role: 'USER', regionScope: [] },
  }
}

function reject(config: InternalAxiosRequestConfig, status: number, code: string): Promise<never> {
  const response = { status, statusText: '', headers: {}, config, data: { code, message: code } } as AxiosResponse
  return Promise.reject(new AxiosError(code, AxiosError.ERR_BAD_REQUEST, config, null, response))
}

/** 只认 Bearer new 的假后端 */
function fakeBackend(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  if (config.headers.Authorization !== 'Bearer new') {
    return reject(config, 401, 'UNAUTHORIZED')
  }
  return Promise.resolve({ status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', data: config.url } })
}

describe('http 拦截器：401 自动刷新', () => {
  const expired = vi.fn()

  beforeEach(() => {
    localStorage.clear()
    tokenStore.save(tokens('old', 'r1'))
    http.defaults.adapter = fakeBackend
    onSessionExpired(expired)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    expired.mockReset()
  })

  it('并发的多个 401 只触发一次刷新，然后全部用新令牌重放', async () => {
    const refresh = vi.spyOn(axios, 'post').mockImplementation(async () => {
      await new Promise((r) => setTimeout(r, 20))
      return { data: { code: 'OK', data: tokens('new', 'r2') } }
    })

    const results = await Promise.all([http.get('/a'), http.get('/b'), http.get('/c')])

    expect(results.map((r) => r.data.data)).toEqual(['/a', '/b', '/c'])
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(refresh).toHaveBeenCalledWith('/api/v1/auth/refresh', { refreshToken: 'r1' })
    expect(tokenStore.accessToken()).toBe('new')
    expect(tokenStore.refreshToken()).toBe('r2')
  })

  it('请求发出后令牌已被别处刷新时，直接用新令牌重放，不再刷新', async () => {
    const refresh = vi.spyOn(axios, 'post')
    http.defaults.adapter = (config) => {
      // 模拟另一个标签页在这个请求进行期间刷新了令牌
      if (config.headers.Authorization === 'Bearer old') {
        tokenStore.save(tokens('new', 'r2'))
      }
      return fakeBackend(config)
    }

    const response = await http.get('/a')

    expect(response.data.data).toBe('/a')
    expect(refresh).not.toHaveBeenCalled()
  })

  it('刷新失败时清空令牌、通知会话失效，原请求以 REFRESH_TOKEN_INVALID 失败', async () => {
    vi.spyOn(axios, 'post').mockImplementation((url, _body, config) =>
      reject({ ...(config ?? {}), url, headers: {} } as InternalAxiosRequestConfig, 401, 'REFRESH_TOKEN_INVALID'),
    )

    const error = await http.get('/a').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('REFRESH_TOKEN_INVALID')
    expect(tokenStore.get()).toBeNull()
    expect(expired).toHaveBeenCalled()
  })

  it('账号停用等不可恢复的 401 不刷新，直接登出', async () => {
    const refresh = vi.spyOn(axios, 'post')
    http.defaults.adapter = (config) => reject(config, 401, 'ACCOUNT_DISABLED')

    const error = await http.get('/a').catch((e: unknown) => e)

    expect((error as ApiError).code).toBe('ACCOUNT_DISABLED')
    expect(refresh).not.toHaveBeenCalled()
    expect(tokenStore.get()).toBeNull()
    expect(expired).toHaveBeenCalled()
  })

  it('登录接口的 401（口令错误）原样返回，不触发刷新', async () => {
    const refresh = vi.spyOn(axios, 'post')
    http.defaults.adapter = (config) => reject(config, 401, 'BAD_CREDENTIALS')

    const error = await http.post('/auth/login', {}).catch((e: unknown) => e)

    expect((error as ApiError).code).toBe('BAD_CREDENTIALS')
    expect(refresh).not.toHaveBeenCalled()
    expect(tokenStore.accessToken()).toBe('old')
  })
})
