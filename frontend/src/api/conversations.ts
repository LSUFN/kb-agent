import type {
  ApiResponse,
  Conversation,
  ConversationMode,
  CursorPage,
  MessageView,
  PageResponse,
} from '@/types/api'

import { http, unwrap } from './http'

export const conversationApi = {
  /** 按更新时间倒序 */
  list: (params: { keyword?: string; page?: number; size?: number } = {}) =>
    unwrap(
      http.get<ApiResponse<PageResponse<Conversation>>>('/conversations', {
        params: { page: 1, size: 50, ...params, keyword: params.keyword || undefined },
      }),
    ),

  get: (id: number) => unwrap(http.get<ApiResponse<Conversation>>(`/conversations/${id}`)),

  /** kbId 为 null 时在当前用户有权访问的全部知识库中检索 */
  create: (mode: ConversationMode, kbId: number | null, title?: string) =>
    unwrap(http.post<ApiResponse<Conversation>>('/conversations', { mode, kbId, title: title || undefined })),

  rename: (id: number, title: string) =>
    unwrap(http.patch<ApiResponse<Conversation>>(`/conversations/${id}`, { title })),

  remove: (id: number) => unwrap(http.delete<ApiResponse<void>>(`/conversations/${id}`)),

  /** 游标分页：不传 beforeId 时返回最新的一页；items 按时间正序 */
  messages: (id: number, beforeId?: number | null, size = 50) =>
    unwrap(
      http.get<ApiResponse<CursorPage<MessageView>>>(`/conversations/${id}/messages`, {
        params: { beforeId: beforeId ?? undefined, size },
      }),
    ),
}
