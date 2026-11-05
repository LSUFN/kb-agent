import type { Citation, CitationMode, MessageRole, MessageStatus, MessageView, ToolStep } from './api'
import type { ChatStreamEvent, RetrievedCandidate, Usage } from './sse'

/** 工具调用时间线中的一步；running 表示收到了 START 还没收到 END */
export interface ToolStepVM extends Omit<ToolStep, 'decision'> {
  decision: ToolStep['decision'] | null
  running: boolean
}

/** 页面上的一条消息：历史消息和正在流式输出的消息用同一个结构 */
export interface ChatMessageVM {
  /** v-for 的 key：服务端 id 或本地临时 id */
  key: string
  id: number | null
  role: MessageRole
  content: string
  status: MessageStatus
  citationMode: CitationMode | null
  citations: Citation[]
  /** KB_QA 检索到的资料（只有实时流里有），引用确定之前先在右侧面板展示 */
  candidates: RetrievedCandidate[]
  toolSteps: ToolStepVM[]
  unverifiedNumbers: string[] | null
  errorCode: string | null
  errorMessage: string | null
  traceId: string | null
  createdAt: string
  latencyMs: number | null
  firstTokenMs: number | null
  usage: Usage | null
}

let tempSeq = 0

export function tempKey(prefix: string): string {
  tempSeq += 1
  return `${prefix}-${Date.now()}-${tempSeq}`
}

export function fromMessageView(m: MessageView): ChatMessageVM {
  return {
    key: `m-${m.id}`,
    id: m.id,
    role: m.role,
    content: m.content ?? '',
    status: m.status,
    citationMode: m.citationMode,
    citations: m.citations ?? [],
    candidates: [],
    toolSteps: (m.toolSteps ?? []).map((s) => ({ ...s, running: false })),
    unverifiedNumbers: m.unverifiedNumbers,
    errorCode: m.errorCode,
    errorMessage: null,
    traceId: m.traceId,
    createdAt: m.createdAt,
    latencyMs: null,
    firstTokenMs: null,
    usage: null,
  }
}

export function newLocalMessage(role: MessageRole, content: string, status: MessageStatus): ChatMessageVM {
  return {
    key: tempKey(role === 'USER' ? 'u' : 'a'),
    id: null,
    role,
    content,
    status,
    citationMode: null,
    citations: [],
    candidates: [],
    toolSteps: [],
    unverifiedNumbers: null,
    errorCode: null,
    errorMessage: null,
    traceId: null,
    createdAt: new Date().toISOString(),
    latencyMs: null,
    firstTokenMs: null,
    usage: null,
  }
}

/**
 * 把一个 SSE 事件应用到这一轮的用户消息和助手消息上（纯函数式地修改传入对象，便于单元测试）。
 * delta 的合并节流由调用方负责（chatStream store 用 requestAnimationFrame 合并后再调用）。
 */
export function applyStreamEvent(user: ChatMessageVM, answer: ChatMessageVM, event: ChatStreamEvent): void {
  switch (event.event) {
    case 'meta':
      answer.id = event.data.messageId
      user.id = event.data.userMessageId
      answer.traceId = event.data.traceId
      break
    case 'retrieval':
      answer.candidates = event.data.candidates
      break
    case 'tool': {
      const data = event.data
      const existing = answer.toolSteps.find((s) => s.callId === data.callId)
      if (data.phase === 'START') {
        if (!existing) {
          answer.toolSteps.push({
            callId: data.callId,
            tool: data.name,
            args: data.args ?? {},
            decision: null,
            ok: false,
            latencyMs: 0,
            summary: null,
            running: true,
          })
        }
      } else {
        let step = existing
        if (!step) {
          answer.toolSteps.push({
            callId: data.callId,
            tool: data.name,
            args: {},
            decision: null,
            ok: false,
            latencyMs: 0,
            summary: null,
            running: false,
          })
          // 从数组里重新取：answer 是响应式对象时，取到的是代理，修改才能触发更新
          step = answer.toolSteps[answer.toolSteps.length - 1]!
        }
        step.decision = data.decision
        step.ok = data.ok
        step.latencyMs = data.latencyMs
        step.summary = data.summary
        step.running = false
      }
      break
    }
    case 'delta':
      answer.content += event.data.text
      break
    case 'citations':
      answer.citationMode = event.data.mode
      answer.citations = event.data.citations
      break
    case 'done':
      answer.status = event.data.status
      answer.latencyMs = event.data.latencyMs
      answer.firstTokenMs = event.data.firstTokenMs
      answer.usage = event.data.usage
      answer.unverifiedNumbers = event.data.unverifiedNumbers
      finishRunningSteps(answer)
      break
    case 'error':
      answer.status = 'FAILED'
      answer.errorCode = event.data.code
      answer.errorMessage = event.data.message
      answer.traceId = event.data.traceId ?? answer.traceId
      finishRunningSteps(answer)
      break
  }
}

/** 本轮结束时还没收到 END 的工具调用不再显示“进行中” */
export function finishRunningSteps(answer: ChatMessageVM) {
  for (const step of answer.toolSteps) {
    step.running = false
  }
}

/** 报告工具的摘要形如“已生成报告 #12：…”，从中取出报告编号 */
export function reportIdsOf(message: ChatMessageVM): number[] {
  const ids: number[] = []
  for (const step of message.toolSteps) {
    if (step.tool === 'generateReport' && step.ok && step.summary) {
      const match = /#(\d+)/.exec(step.summary)
      if (match) {
        ids.push(Number(match[1]))
      }
    }
  }
  return ids
}
