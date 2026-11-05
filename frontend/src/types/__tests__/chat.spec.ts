import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'

import { applyStreamEvent, newLocalMessage, reportIdsOf, type ChatMessageVM } from '../chat'
import type { ChatStreamEvent } from '../sse'

function round() {
  const user = reactive(newLocalMessage('USER', '问题', 'OK')) as ChatMessageVM
  const answer = reactive(newLocalMessage('ASSISTANT', '', 'STREAMING')) as ChatMessageVM
  const apply = (...events: ChatStreamEvent[]) => events.forEach((e) => applyStreamEvent(user, answer, e))
  return { user, answer, apply }
}

describe('applyStreamEvent', () => {
  it('KB_QA：meta → retrieval → delta → citations → done', () => {
    const { user, answer, apply } = round()
    apply(
      { event: 'meta', data: { messageId: 12, userMessageId: 11, traceId: 't-1' } },
      {
        event: 'retrieval',
        data: { candidates: [{ index: 1, documentId: 3, filename: 'a.pdf', page: 2, score: 0.8 }] },
      },
      { event: 'delta', data: { text: '标准是' } },
      { event: 'delta', data: { text: ' 600 元[1]' } },
      {
        event: 'citations',
        data: {
          mode: 'NORMAL',
          citations: [{ index: 1, documentId: 3, filename: 'a.pdf', page: 2, snippet: '…', score: 0.8 }],
          invalidIndices: [],
        },
      },
      {
        event: 'done',
        data: { status: 'OK', usage: { prompt: 10, completion: 5, estimated: false }, latencyMs: 900, firstTokenMs: 300, unverifiedNumbers: null },
      },
    )
    expect(user.id).toBe(11)
    expect(answer.id).toBe(12)
    expect(answer.traceId).toBe('t-1')
    expect(answer.content).toBe('标准是 600 元[1]')
    expect(answer.candidates).toHaveLength(1)
    expect(answer.citationMode).toBe('NORMAL')
    expect(answer.status).toBe('OK')
    expect(answer.latencyMs).toBe(900)
  })

  it('AGENT：工具调用的 START 和 END 按 callId 合并，被拒绝的调用也会显示', () => {
    const { answer, apply } = round()
    apply(
      { event: 'tool', data: { callId: 'c1', name: 'countAnomalies', phase: 'START', args: { region: 'NORTH' } } },
      { event: 'tool', data: { callId: 'c1', name: 'countAnomalies', phase: 'END', decision: 'DENIED', ok: false, latencyMs: 2, summary: null } },
      { event: 'tool', data: { callId: 'c2', name: 'queryOrderStats', phase: 'START', args: { region: 'EAST' } } },
    )
    expect(answer.toolSteps).toHaveLength(2)
    expect(answer.toolSteps[0]).toMatchObject({ callId: 'c1', decision: 'DENIED', running: false, args: { region: 'NORTH' } })
    expect(answer.toolSteps[1]).toMatchObject({ callId: 'c2', decision: null, running: true })

    apply({
      event: 'done',
      data: { status: 'OK', usage: null, latencyMs: 1, firstTokenMs: null, unverifiedNumbers: ['127'] },
    })
    // 结束时还没收到 END 的调用不再显示“进行中”
    expect(answer.toolSteps[1]!.running).toBe(false)
    expect(answer.unverifiedNumbers).toEqual(['127'])
  })

  it('只收到 END（例如参数不合法时没有 START）也会记录这一步', () => {
    const { answer, apply } = round()
    apply({
      event: 'tool',
      data: { callId: 'c3', name: 'queryOrderStats', phase: 'END', decision: 'INVALID', ok: false, latencyMs: 0, summary: '参数不合法' },
    })
    expect(answer.toolSteps).toEqual([
      expect.objectContaining({ callId: 'c3', tool: 'queryOrderStats', decision: 'INVALID', running: false }),
    ])
  })

  it('error 事件把回答标记为失败并保留 traceId', () => {
    const { answer, apply } = round()
    apply(
      { event: 'meta', data: { messageId: 2, userMessageId: 1, traceId: 't-2' } },
      { event: 'delta', data: { text: '部分内容' } },
      { event: 'error', data: { code: 'UPSTREAM_TIMEOUT', message: '模型服务超时，请稍后重试', traceId: 't-2' } },
    )
    expect(answer.status).toBe('FAILED')
    expect(answer.errorCode).toBe('UPSTREAM_TIMEOUT')
    expect(answer.errorMessage).toBe('模型服务超时，请稍后重试')
    expect(answer.content).toBe('部分内容')
  })
})

describe('reportIdsOf', () => {
  it('从成功的报告工具摘要中取出报告编号', () => {
    const { answer, apply } = round()
    apply(
      { event: 'tool', data: { callId: 'c1', name: 'generateReport', phase: 'START', args: {} } },
      { event: 'tool', data: { callId: 'c1', name: 'generateReport', phase: 'END', decision: 'ALLOWED', ok: true, latencyMs: 70, summary: '已生成报告 #12：订单经营报告' } },
      { event: 'tool', data: { callId: 'c2', name: 'generateReport', phase: 'END', decision: 'DENIED', ok: false, latencyMs: 1, summary: '#99' } },
    )
    expect(reportIdsOf(answer)).toEqual([12])
  })
})
