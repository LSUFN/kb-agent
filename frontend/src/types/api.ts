// 与后端 REST 接口一一对应的类型（ARCHITECTURE.md §6）。时间字段是 ISO-8601 字符串。

/** 统一响应体，成功时 code 为 "OK"。 */
export interface ApiResponse<T> {
  code: string
  message: string
  data?: T
  traceId?: string
}

/** 分页结果，page 从 1 开始。 */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** 游标分页（消息）：nextBeforeId 为 null 表示没有更早的数据。 */
export interface CursorPage<T> {
  items: T[]
  nextBeforeId: number | null
}

export interface FieldViolation {
  field: string
  message: string
}

// ---------- 用户与认证 ----------

export type Role = 'USER' | 'ANALYST' | 'ADMIN'
export type Region = 'EAST' | 'NORTH' | 'SOUTH' | 'WEST' | 'CENTRAL'

export interface CurrentUser {
  id: number
  username: string
  displayName: string
  role: Role
  regionScope: Region[]
}

export interface TokenResponse {
  tokenType: string
  accessToken: string
  /** 秒 */
  expiresIn: number
  refreshToken: string
  /** 秒 */
  refreshExpiresIn: number
  user: CurrentUser
}

export interface UserView {
  id: number
  username: string
  displayName: string
  role: Role
  regionScope: Region[]
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateUserCommand {
  username: string
  password: string
  displayName: string
  role: Role
  regionScope: Region[]
}

/** 部分更新：字段不传表示不修改；清空区域范围传空数组。 */
export interface UpdateUserCommand {
  displayName?: string
  role?: Role
  regionScope?: Region[]
  enabled?: boolean
}

// ---------- 知识库与文档 ----------

export type Visibility = 'PRIVATE' | 'PUBLIC'
export type KbAccess = 'NONE' | 'READ' | 'EDIT' | 'MANAGE'
export type KbPermission = 'READER' | 'EDITOR'

export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  visibility: Visibility
  ownerId: number
  myAccess: KbAccess
  createdAt: string
}

export interface KbMember {
  userId: number
  username: string
  displayName: string
  permission: KbPermission
  createdAt: string
}

export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED' | 'DELETING'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'DEAD' | 'CANCELLED'
export type TaskType = 'INGEST_DOCUMENT' | 'DELETE_FILE'

export interface DocumentTask {
  status: TaskStatus
  attempt: number
  maxAttempts: number
  nextRunAt: string
}

export interface DocumentView {
  id: number
  kbId: number
  filename: string
  status: DocumentStatus
  sizeBytes: number
  pageCount: number | null
  chunkCount: number
  errorCode: string | null
  errorMessage: string | null
  embeddingModel: string | null
  uploadedBy: number
  createdAt: string
  updatedAt: string
  /** 只在文档详情中返回 */
  task: DocumentTask | null
}

export interface ChunkView {
  chunkIndex: number
  pageNumber: number
  charCount: number
  content: string
}

export interface SearchHit {
  documentId: number
  filename: string
  pageNumber: number
  chunkIndex: number
  score: number
  aboveThreshold: boolean
  content: string
}

export interface SearchResult {
  minScore: number
  hits: SearchHit[]
}

// ---------- 会话与消息 ----------

export type ConversationMode = 'KB_QA' | 'AGENT'
export type MessageRole = 'USER' | 'ASSISTANT'
export type MessageStatus = 'STREAMING' | 'OK' | 'FAILED' | 'INTERRUPTED'
export type CitationMode = 'NORMAL' | 'FALLBACK' | 'NONE'
export type ToolDecision = 'ALLOWED' | 'DENIED' | 'INVALID' | 'BUDGET_EXCEEDED'

export interface Conversation {
  id: number
  title: string
  mode: ConversationMode
  kbId: number | null
  createdAt: string
  updatedAt: string
}

export interface Citation {
  /** 回答中使用的编号 [n] */
  index: number
  documentId: number
  filename: string
  page: number
  snippet: string
  score: number
}

/** 历史消息中保存的一次工具调用，参数已脱敏。 */
export interface ToolStep {
  callId: string
  tool: string
  args: Record<string, unknown>
  decision: ToolDecision
  ok: boolean
  latencyMs: number
  summary: string | null
}

export interface MessageView {
  id: number
  role: MessageRole
  content: string
  status: MessageStatus
  citationMode: CitationMode | null
  citations: Citation[]
  toolSteps: ToolStep[]
  /** 只有 AGENT 回答有（数字核对，§4.11） */
  unverifiedNumbers: string[] | null
  errorCode: string | null
  traceId: string | null
  createdAt: string
}

// ---------- 报告 ----------

export type ReportType = 'ORDER_SUMMARY' | 'ANOMALY_SUMMARY'

export interface ReportView {
  id: number
  userId: number
  conversationId: number | null
  type: ReportType
  title: string
  /** JSON 字符串：{from, to, regions, ...} */
  params: string
  createdAt: string
}

export interface ReportDetail {
  report: ReportView
  contentMd: string
}

// ---------- 管理 ----------

export interface LlmCallView {
  id: number
  traceId: string
  userId: number | null
  conversationId: number | null
  callType: string
  chatMode: string | null
  model: string
  streamed: boolean
  promptTokens: number | null
  completionTokens: number | null
  usageEstimated: boolean
  inputPreview: string | null
  outputChars: number | null
  firstTokenMs: number | null
  latencyMs: number
  success: boolean
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
}

export interface ToolCallView {
  id: number
  traceId: string
  toolCallId: string
  userId: number
  userRole: string
  conversationId: number | null
  toolName: string
  /** 写入时已脱敏的参数（JSON 字符串） */
  args: string
  effectiveScope: string[]
  decision: ToolDecision
  success: boolean
  resultSummary: string | null
  errorCode: string | null
  latencyMs: number
  createdAt: string
}

export interface AdminTaskView {
  id: number
  type: TaskType
  bizId: number
  status: TaskStatus
  attempt: number
  maxAttempts: number
  /** 已脱敏 */
  lastError: string | null
  nextRunAt: string
  createdAt: string
  updatedAt: string
}

/** 延迟和首字延迟只统计对话调用（CHAT），不含向量化调用 */
export interface MetricsDay {
  /** 合计行为 null */
  day: string | null
  llmCalls: number
  llmFailures: number
  embeddingCalls: number
  p50LatencyMs: number | null
  p95LatencyMs: number | null
  p50FirstTokenMs: number | null
  promptTokens: number
  completionTokens: number
  toolCalls: number
  toolDenied: number
}

export interface MetricsOverview {
  from: string
  to: string
  zone: string
  total: MetricsDay
  days: MetricsDay[]
}
