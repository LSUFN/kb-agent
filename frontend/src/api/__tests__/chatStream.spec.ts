import axios from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { TokenResponse } from '@/types/api'
import type { ChatStreamEvent } from '@/types/sse'
import { tokenStore } from '@/utils/tokenStore'

import { streamChat } from '../chatStream'
import { ApiError } from '../http'

function sse(...frames: string[]): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode(frame))
      }
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } })
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

const tokens = (access: string): TokenResponse => ({
  tokenType: 'Bearer',
  accessToken: access,
  expiresIn: 900,
  refreshToken: `refresh-${access}`,
  refreshExpiresIn: 86400,
  user: { id: 1, username: 'u', displayName: 'U', role: 'USER', regionScope: [] },
})

describe('streamChat', () => {
  beforeEach(() => {
    localStorage.clear()
    tokenStore.save(tokens('old'))
  })

  afterEach(() => vi.restoreAllMocks())

  it('解析事件并忽略心跳注释；请求带 Authorization 头', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      sse(
        'event: meta\ndata: {"messageId":2,"userMessageId":1,"traceId":"t"}\n\n',
        ': ping\n\n',
        'event: delta\ndata: {"text":"你好"}\n\n',
        'event: unknown\ndata: {}\n\n',
        'event: done\ndata: {"status":"OK","usage":null,"latencyMs":5,"firstTokenMs":null,"unverifiedNumbers":null}\n\n',
      ),
    )
    const events: ChatStreamEvent[] = []

    await streamChat(7, '问题', new AbortController().signal, (e) => events.push(e), fetchImpl)

    expect(events.map((e) => e.event)).toEqual(['meta', 'delta', 'done'])
    expect(events[1]).toEqual({ event: 'delta', data: { text: '你好' } })
    const [url, init] = fetchImpl.mock.calls[0]!
    expect(url).toBe('/api/v1/conversations/7/messages/stream')
    expect(init?.method).toBe('POST')
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer old')
    expect(init?.body).toBe(JSON.stringify({ content: '问题' }))
  })

  it('开始输出之前被拒绝（限流）时返回 ApiError，且不会自动重试', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json(429, { code: 'RATE_LIMITED', message: '请求过于频繁' }, { 'Retry-After': '30' }))

    const error = await streamChat(7, '问题', new AbortController().signal, () => {}, fetchImpl).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 429, code: 'RATE_LIMITED', retryAfter: 30 })
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })

  it('Token 过期时刷新一次后重新发起', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { code: 'OK', data: tokens('new') } })
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHORIZED', message: '未登录或登录已过期' }))
      .mockResolvedValueOnce(sse('event: done\ndata: {"status":"OK","usage":null,"latencyMs":1,"firstTokenMs":null,"unverifiedNumbers":null}\n\n'))
    const events: string[] = []

    await streamChat(7, '问题', new AbortController().signal, (e) => events.push(e.event), fetchImpl)

    expect(events).toEqual(['done'])
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect((fetchImpl.mock.calls[1]![1]?.headers as Record<string, string>).Authorization).toBe('Bearer new')
  })

  it('连接中途断开时以网络错误结束，不会重新提交问题', async () => {
    const encoder = new TextEncoder()
    // 第一次读到一个事件，第二次读时连接断开（error() 会丢弃还没读走的数据，所以放在下一次 pull 里）
    let pulls = 0
    const broken = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (pulls++ === 0) {
          controller.enqueue(encoder.encode('event: delta\ndata: {"text":"部分"}\n\n'))
        } else {
          controller.error(new TypeError('network error'))
        }
      },
    })
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(broken, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    const events: string[] = []

    const error = await streamChat(7, '问题', new AbortController().signal, (e) => events.push(e.event), fetchImpl).catch(
      (e) => e,
    )

    expect(events).toEqual(['delta'])
    expect(error).toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })
})
