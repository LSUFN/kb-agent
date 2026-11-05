import { ElMessage } from 'element-plus'

import { ApiError, SESSION_EXPIRED, toApiError } from '@/api/http'

/** 统一的错误提示。会话失效时已经由 main.ts 跳转登录页，这里不再重复提示。 */
export function notifyError(error: unknown, fallback = '操作失败') {
  const e = toApiError(error)
  if (e.code === SESSION_EXPIRED) {
    return
  }
  let message = e.message || fallback
  if (e.code === 'VALIDATION_FAILED' && e.violations.length > 0) {
    message = e.violations.map((v) => v.message).join('；')
  } else if (e.code === 'RATE_LIMITED' && e.retryAfter) {
    message = `${message}（${e.retryAfter} 秒后可重试）`
  }
  if (e.traceId) {
    console.warn(`[${e.code}] ${message} traceId=${e.traceId}`)
  }
  ElMessage.error({ message, grouping: true })
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code)
}
