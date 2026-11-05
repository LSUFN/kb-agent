import { EventStreamContentType, fetchEventSource } from '@microsoft/fetch-event-source'

import { parseStreamEvent, type ChatStreamEvent } from '@/types/sse'
import { tokenStore } from '@/utils/tokenStore'

import { API_BASE, ApiError, fromResponseBody, refreshAccessToken } from './http'

/**
 * 发起一轮流式问答（POST + Authorization 头，原生 EventSource 都不支持，所以用 fetch-event-source）。
 *
 * - 服务端正常结束流时 resolve；调用方 abort 时也 resolve（停止生成）。
 * - 开始输出之前被拒绝（限流、无权限、参数错误）时，服务端返回普通 JSON 错误，这里 reject 一个 ApiError。
 *   其中 401 UNAUTHORIZED 会先刷新一次 Token 再重新发起；流开始之后服务端不再鉴权（§6.4）。
 * - 绝不自动重连：fetch-event-source 默认会在出错时重试、在页面隐藏时断开并在可见时重连，
 *   对这个接口来说都意味着把问题再提交一遍。
 */
export async function streamChat(
  conversationId: number,
  content: string,
  signal: AbortSignal,
  onEvent: (event: ChatStreamEvent) => void,
  fetchImpl?: typeof fetch,
): Promise<void> {
  for (let attempt = 0; ; attempt++) {
    const token = tokenStore.accessToken()
    try {
      await open(conversationId, content, token, signal, onEvent, fetchImpl)
      return
    } catch (error) {
      if (attempt === 0 && error instanceof ApiError && error.status === 401 && error.code === 'UNAUTHORIZED') {
        await refreshAccessToken(token)
        continue
      }
      throw error
    }
  }
}

function open(
  conversationId: number,
  content: string,
  token: string | null,
  signal: AbortSignal,
  onEvent: (event: ChatStreamEvent) => void,
  fetchImpl?: typeof fetch,
): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return fetchEventSource(`${API_BASE}/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ content }),
    signal,
    openWhenHidden: true,
    fetch: fetchImpl,
    async onopen(response) {
      const type = response.headers.get('content-type') ?? ''
      if (response.ok && type.startsWith(EventStreamContentType)) {
        return
      }
      let body: unknown = null
      try {
        body = await response.json()
      } catch {
        // 非 JSON 响应（例如代理返回的 HTML 错误页）
      }
      throw fromResponseBody(response.status, body, response.headers.get('retry-after'))
    },
    onmessage(message) {
      const event = parseStreamEvent(message.event, message.data)
      if (event) {
        onEvent(event)
      }
    },
    onerror(error) {
      throw error instanceof ApiError
        ? error
        : new ApiError(0, 'NETWORK_ERROR', '连接中断，请检查网络后重试')
    },
  })
}
