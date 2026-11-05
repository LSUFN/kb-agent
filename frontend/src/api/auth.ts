import type { ApiResponse, CurrentUser, TokenResponse } from '@/types/api'

import { http, unwrap } from './http'

export const authApi = {
  login: (username: string, password: string) =>
    unwrap(http.post<ApiResponse<TokenResponse>>('/auth/login', { username, password })),

  /** 当前 Access Token 进入黑名单，同时撤销 Refresh Token 所在的令牌族。 */
  logout: (refreshToken: string | null) =>
    unwrap(http.post<ApiResponse<void>>('/auth/logout', refreshToken ? { refreshToken } : undefined)),

  me: () => unwrap(http.get<ApiResponse<CurrentUser>>('/users/me')),
}
