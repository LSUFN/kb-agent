import type { ApiResponse, PageResponse, ReportDetail, ReportView } from '@/types/api'

import { http, unwrap } from './http'

export const reportApi = {
  list: (page = 1, size = 20) =>
    unwrap(http.get<ApiResponse<PageResponse<ReportView>>>('/reports', { params: { page, size } })),

  get: (id: number) => unwrap(http.get<ApiResponse<ReportDetail>>(`/reports/${id}`)),

  /** 下载 Markdown 原文。走 http 实例才能带上 Authorization 头（以及 401 时自动刷新）。 */
  download: async (id: number) => {
    const response = await http.get<Blob>(`/reports/${id}/download`, { responseType: 'blob' })
    const filename = filenameFromDisposition(response.headers['content-disposition']) ?? `report-${id}.md`
    return { blob: response.data, filename }
  },
}

/** 解析 Content-Disposition，优先使用 RFC 6266 的 filename*（UTF-8 编码的中文文件名）。 */
export function filenameFromDisposition(header: string | undefined | null): string | null {
  if (!header) {
    return null
  }
  const extended = /filename\*\s*=\s*utf-8''([^;]+)/i.exec(header)
  if (extended) {
    try {
      return decodeURIComponent(extended[1]!.trim())
    } catch {
      // 编码不合法时退回到普通 filename
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(header)
  return plain ? plain[1]!.trim() : null
}
