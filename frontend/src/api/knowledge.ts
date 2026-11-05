import type { AxiosProgressEvent } from 'axios'

import type {
  ApiResponse,
  ChunkView,
  DocumentStatus,
  DocumentView,
  KbMember,
  KbPermission,
  KnowledgeBase,
  PageResponse,
  SearchResult,
  Visibility,
} from '@/types/api'

import { http, unwrap } from './http'

export interface KnowledgeBaseForm {
  name: string
  description?: string
  visibility: Visibility
}

export const knowledgeApi = {
  list: (page = 1, size = 100) =>
    unwrap(http.get<ApiResponse<PageResponse<KnowledgeBase>>>('/knowledge-bases', { params: { page, size } })),

  get: (kbId: number) => unwrap(http.get<ApiResponse<KnowledgeBase>>(`/knowledge-bases/${kbId}`)),

  create: (form: KnowledgeBaseForm) => unwrap(http.post<ApiResponse<KnowledgeBase>>('/knowledge-bases', form)),

  update: (kbId: number, form: Partial<KnowledgeBaseForm>) =>
    unwrap(http.patch<ApiResponse<KnowledgeBase>>(`/knowledge-bases/${kbId}`, form)),

  /** 还有文档时返回 409 */
  remove: (kbId: number) => unwrap(http.delete<ApiResponse<void>>(`/knowledge-bases/${kbId}`)),

  members: (kbId: number) => unwrap(http.get<ApiResponse<KbMember[]>>(`/knowledge-bases/${kbId}/members`)),

  putMember: (kbId: number, userId: number, permission: KbPermission) =>
    unwrap(http.put<ApiResponse<void>>(`/knowledge-bases/${kbId}/members/${userId}`, { permission })),

  removeMember: (kbId: number, userId: number) =>
    unwrap(http.delete<ApiResponse<void>>(`/knowledge-bases/${kbId}/members/${userId}`)),

  /** 只检索、不生成回答，返回每个片段的相似度和是否达到问答使用的阈值 */
  search: (kbId: number, query: string, topK: number) =>
    unwrap(http.post<ApiResponse<SearchResult>>(`/knowledge-bases/${kbId}/search`, { query, topK })),
}

export const documentApi = {
  list: (kbId: number, params: { status?: DocumentStatus; page?: number; size?: number } = {}) =>
    unwrap(
      http.get<ApiResponse<PageResponse<DocumentView>>>(`/knowledge-bases/${kbId}/documents`, {
        params: { page: 1, size: 100, ...params },
      }),
    ),

  /** 详情带最近一次入库任务（尝试次数、下次重试时间），供轮询使用 */
  get: (docId: number) => unwrap(http.get<ApiResponse<DocumentView>>(`/documents/${docId}`)),

  /**
   * 新文档返回 202；知识库中已有内容相同的文档时返回 200 和已有文档（created 为 false）。
   * 上传可能较慢，不使用默认的 30 秒超时。
   */
  upload: async (kbId: number, file: File, onProgress?: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    const response = await http.post<ApiResponse<DocumentView>>(`/knowledge-bases/${kbId}/documents`, form, {
      timeout: 0,
      onUploadProgress: (event: AxiosProgressEvent) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
    return { document: response.data.data!, created: response.status === 202 }
  },

  /** 已有活跃任务时返回 409 */
  reprocess: (docId: number) => unwrap(http.post<ApiResponse<DocumentView>>(`/documents/${docId}/reprocess`)),

  remove: (docId: number) => unwrap(http.delete<ApiResponse<DocumentView>>(`/documents/${docId}`)),

  chunks: (docId: number, page: number, size = 20) =>
    unwrap(
      http.get<ApiResponse<PageResponse<ChunkView>>>(`/documents/${docId}/chunks`, { params: { page, size } }),
    ),
}
