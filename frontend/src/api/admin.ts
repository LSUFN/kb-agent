import type {
  AdminTaskView,
  ApiResponse,
  CreateUserCommand,
  LlmCallView,
  MetricsOverview,
  PageResponse,
  TaskStatus,
  TaskType,
  ToolCallView,
  ToolDecision,
  UpdateUserCommand,
  UserView,
} from '@/types/api'

import { http, unwrap } from './http'

export interface LlmCallFilter {
  traceId?: string
  userId?: number
  success?: boolean
  from?: string
  to?: string
}

export interface ToolCallFilter {
  traceId?: string
  userId?: number
  toolName?: string
  decision?: ToolDecision
  from?: string
  to?: string
}

/** 去掉空字符串和 undefined，避免把空过滤条件传给后端 */
function clean<T extends object>(params: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ''),
  ) as Partial<T>
}

export const adminApi = {
  users: (keyword: string | undefined, page: number, size = 20) =>
    unwrap(
      http.get<ApiResponse<PageResponse<UserView>>>('/admin/users', { params: clean({ keyword, page, size }) }),
    ),

  createUser: (command: CreateUserCommand) => unwrap(http.post<ApiResponse<UserView>>('/admin/users', command)),

  updateUser: (id: number, command: UpdateUserCommand) =>
    unwrap(http.patch<ApiResponse<UserView>>(`/admin/users/${id}`, command)),

  llmCalls: (filter: LlmCallFilter, page: number, size = 20) =>
    unwrap(
      http.get<ApiResponse<PageResponse<LlmCallView>>>('/admin/logs/llm-calls', {
        params: clean({ ...filter, page, size }),
      }),
    ),

  toolCalls: (filter: ToolCallFilter, page: number, size = 20) =>
    unwrap(
      http.get<ApiResponse<PageResponse<ToolCallView>>>('/admin/logs/tool-calls', {
        params: clean({ ...filter, page, size }),
      }),
    ),

  tasks: (params: { status?: TaskStatus; type?: TaskType; page: number; size?: number }) =>
    unwrap(http.get<ApiResponse<PageResponse<AdminTaskView>>>('/admin/tasks', { params: clean(params) })),

  /** 只能重试 DEAD 的任务；同一业务对象已有活跃任务时返回 409 */
  retryTask: (id: number) => unwrap(http.post<ApiResponse<AdminTaskView>>(`/admin/tasks/${id}/retry`)),

  /** from 包含、to 不包含，格式 yyyy-MM-dd（按业务时区） */
  metrics: (from: string, to: string) =>
    unwrap(http.get<ApiResponse<MetricsOverview>>('/admin/metrics/overview', { params: { from, to } })),
}
