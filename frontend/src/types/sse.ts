// SSE 事件协议，与后端 ChatEvent 一一对应（ARCHITECTURE.md §6.4）。
// 正常顺序：KB_QA 为 meta → retrieval → delta… → citations → done；
// AGENT 为 meta → (tool START → tool END)… → delta… → citations → done，工具调用和文本可以交替出现。
// 失败时以 error 结束。心跳是注释行（": ping"），解析器会忽略。

import type { Citation, CitationMode, MessageStatus, ToolDecision } from './api'

export interface MetaEvent {
  messageId: number
  userMessageId: number
  traceId: string
}

export interface RetrievedCandidate {
  index: number
  documentId: number
  filename: string
  page: number
  score: number
}

/** 达到阈值的检索结果；为空表示将直接拒答（KB_QA）。 */
export interface RetrievalEvent {
  candidates: RetrievedCandidate[]
}

export interface ToolStartEvent {
  callId: string
  name: string
  phase: 'START'
  /** 已脱敏的参数 */
  args: Record<string, unknown>
}

export interface ToolEndEvent {
  callId: string
  name: string
  phase: 'END'
  decision: ToolDecision
  ok: boolean
  latencyMs: number
  summary: string | null
}

export type ToolEvent = ToolStartEvent | ToolEndEvent

export interface DeltaEvent {
  text: string
}

export interface CitationsEvent {
  mode: CitationMode
  citations: Citation[]
  /** 回答中出现、但不对应任何资料的编号，按普通文本显示 */
  invalidIndices: number[]
}

export interface Usage {
  prompt: number | null
  completion: number | null
  /** 模型没有返回用量时按字数估算 */
  estimated: boolean
}

export interface DoneEvent {
  status: MessageStatus
  /** 没有调用模型（拒答）时为 null */
  usage: Usage | null
  latencyMs: number
  firstTokenMs: number | null
  /** 数字核对找不到出处的数字；KB_QA 模式为 null */
  unverifiedNumbers: string[] | null
}

/** message 是面向用户的描述，不含上游原始报错。 */
export interface ErrorEvent {
  code: string
  message: string
  traceId: string
}

export type ChatStreamEvent =
  | { event: 'meta'; data: MetaEvent }
  | { event: 'retrieval'; data: RetrievalEvent }
  | { event: 'tool'; data: ToolEvent }
  | { event: 'delta'; data: DeltaEvent }
  | { event: 'citations'; data: CitationsEvent }
  | { event: 'done'; data: DoneEvent }
  | { event: 'error'; data: ErrorEvent }

export type ChatStreamEventName = ChatStreamEvent['event']

const EVENT_NAMES: ReadonlySet<string> = new Set<ChatStreamEventName>([
  'meta',
  'retrieval',
  'tool',
  'delta',
  'citations',
  'done',
  'error',
])

/** 把 SSE 原始消息解析成事件；未知事件名返回 null（向前兼容：后端新增事件时旧前端忽略它）。 */
export function parseStreamEvent(event: string, data: string): ChatStreamEvent | null {
  if (!EVENT_NAMES.has(event)) {
    return null
  }
  return { event, data: JSON.parse(data) } as ChatStreamEvent
}
