import { defineStore } from 'pinia'
import { computed, reactive, shallowRef } from 'vue'

import { streamChat } from '@/api/chatStream'
import { toApiError } from '@/api/http'
import {
  applyStreamEvent,
  finishRunningSteps,
  newLocalMessage,
  type ChatMessageVM,
} from '@/types/chat'
import type { ChatStreamEvent } from '@/types/sse'

import { useConversationStore } from './conversation'

export interface ActiveStream {
  conversationId: number
  user: ChatMessageVM
  answer: ChatMessageVM
  controller: AbortController
}

/**
 * 正在进行的一轮流式问答。同一时刻只有一轮：切换到别的会话时它在后台继续，切回来时接着显示。
 * delta 事件用 requestAnimationFrame 合并，每帧最多重新渲染一次 Markdown。
 */
export const useChatStreamStore = defineStore('chatStream', () => {
  const active = shallowRef<ActiveStream | null>(null)
  const streaming = computed(() => active.value !== null)

  async function send(conversationId: number, content: string) {
    if (active.value) {
      return
    }
    const conversations = useConversationStore()
    const user = reactive(newLocalMessage('USER', content, 'OK')) as ChatMessageVM
    const answer = reactive(newLocalMessage('ASSISTANT', '', 'STREAMING')) as ChatMessageVM
    const controller = new AbortController()
    active.value = { conversationId, user, answer, controller }
    if (conversations.currentId === conversationId) {
      conversations.messages.push(user, answer)
    }

    let pending = ''
    let frame = 0
    const flush = () => {
      if (frame) {
        cancelAnimationFrame(frame)
        frame = 0
      }
      if (pending) {
        applyStreamEvent(user, answer, { event: 'delta', data: { text: pending } })
        pending = ''
      }
    }
    const onEvent = (event: ChatStreamEvent) => {
      if (event.event === 'delta') {
        pending += event.data.text
        if (!frame) {
          frame = requestAnimationFrame(() => {
            frame = 0
            flush()
          })
        }
        return
      }
      flush()
      applyStreamEvent(user, answer, event)
    }

    try {
      await streamChat(conversationId, content, controller.signal, onEvent)
      flush()
      if (answer.status === 'STREAMING') {
        // 没有收到 done 或 error：用户点了停止，或者连接被意外关闭。服务端会保存已生成的部分并标记为 INTERRUPTED
        answer.status = 'INTERRUPTED'
        answer.errorCode = 'INTERRUPTED'
        answer.errorMessage = controller.signal.aborted ? '已停止生成' : '连接已断开，回答可能不完整'
        finishRunningSteps(answer)
      }
    } catch (error) {
      flush()
      const e = toApiError(error)
      answer.status = answer.id === null ? 'FAILED' : 'INTERRUPTED'
      answer.errorCode = e.code
      answer.errorMessage = e.retryAfter ? `${e.message}（${e.retryAfter} 秒后可重试）` : e.message
      answer.traceId = e.traceId ?? answer.traceId
      finishRunningSteps(answer)
    } finally {
      active.value = null
      void conversations.refreshOne(conversationId)
    }
  }

  /** 停止生成：断开连接即可，服务端感知断开后取消上游请求（§6.3） */
  function stop() {
    active.value?.controller.abort()
  }

  return { active, streaming, send, stop }
})
