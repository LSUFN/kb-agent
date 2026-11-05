import type { CurrentUser, TokenResponse } from '@/types/api'

// 令牌保存在 localStorage：刷新页面后保持登录，多个标签页共享同一组令牌（刷新时用 Web Locks 串行，见 api/http.ts）。
// 取舍：localStorage 能被页面脚本读到，所以前端渲染模型输出时必须关闭 HTML 并经过 DOMPurify（utils/markdown.ts）。

const KEY = 'kb-agent.auth'

export interface StoredAuth {
  accessToken: string
  refreshToken: string
  /** 毫秒时间戳 */
  refreshExpiresAt: number
  user: CurrentUser
}

function read(): StoredAuth | null {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as StoredAuth) : null
  } catch {
    return null
  }
}

function write(auth: StoredAuth | null) {
  try {
    if (auth) {
      localStorage.setItem(KEY, JSON.stringify(auth))
    } else {
      localStorage.removeItem(KEY)
    }
  } catch {
    // 隐私模式或存储被禁用：只影响刷新页面后的登录状态
  }
}

export const tokenStore = {
  key: KEY,

  get(): StoredAuth | null {
    const auth = read()
    if (auth && auth.refreshExpiresAt <= Date.now()) {
      write(null)
      return null
    }
    return auth
  },

  accessToken(): string | null {
    return this.get()?.accessToken ?? null
  },

  refreshToken(): string | null {
    return this.get()?.refreshToken ?? null
  },

  save(tokens: TokenResponse) {
    write({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      refreshExpiresAt: Date.now() + tokens.refreshExpiresIn * 1000,
      user: tokens.user,
    })
  },

  updateUser(user: CurrentUser) {
    const auth = read()
    if (auth) {
      write({ ...auth, user })
    }
  },

  clear() {
    write(null)
  },
}
