import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { authApi } from '@/api/auth'
import type { CurrentUser, Role } from '@/types/api'
import { tokenStore } from '@/utils/tokenStore'

/**
 * 登录状态。用户信息只用于控制菜单和按钮的显示，真正的权限由后端判断。
 * 令牌本身不放进响应式状态：请求层直接读 tokenStore，多个标签页之间也能看到最新令牌。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(tokenStore.get()?.user ?? null)

  const loggedIn = computed(() => user.value !== null)
  const role = computed<Role | null>(() => user.value?.role ?? null)
  const isAdmin = computed(() => role.value === 'ADMIN')
  /** 业务分析工具（订单、异常、报告）只对 ANALYST 和 ADMIN 开放 */
  const canAnalyze = computed(() => role.value === 'ANALYST' || role.value === 'ADMIN')

  async function login(username: string, password: string) {
    const tokens = await authApi.login(username, password)
    tokenStore.save(tokens)
    user.value = tokens.user
  }

  /** 页面加载时用 /users/me 校准角色和区域范围（管理员可能刚改过） */
  async function reloadUser() {
    if (!tokenStore.get()) {
      user.value = null
      return
    }
    const me = await authApi.me()
    tokenStore.updateUser(me)
    user.value = me
  }

  async function logout() {
    const refreshToken = tokenStore.refreshToken()
    try {
      if (tokenStore.accessToken()) {
        await authApi.logout(refreshToken)
      }
    } catch {
      // 令牌已经失效也没关系，本地照样清掉
    } finally {
      tokenStore.clear()
      user.value = null
    }
  }

  /** 会话失效（刷新失败、账号停用）或其他标签页登出时调用 */
  function clearLocal() {
    tokenStore.clear()
    user.value = null
  }

  /** 其他标签页登录、登出、刷新令牌后，同步用户信息 */
  function syncFromStorage() {
    user.value = tokenStore.get()?.user ?? null
  }

  return { user, loggedIn, role, isAdmin, canAnalyze, login, reloadUser, logout, clearLocal, syncFromStorage }
})
