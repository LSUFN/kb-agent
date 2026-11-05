import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { conversationApi } from '@/api/conversations'
import type { Conversation, ConversationMode } from '@/types/api'
import { fromMessageView, type ChatMessageVM } from '@/types/chat'

import { useChatStreamStore } from './chatStream'

const PAGE_SIZE = 50

/** 会话列表和当前会话的消息 */
export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<Conversation[]>([])
  const total = ref(0)
  const page = ref(1)
  const keyword = ref('')
  const loadingList = ref(false)

  const currentId = ref<number | null>(null)
  /** 当前会话不在已加载的列表里时（例如直接打开链接），单独保存它的详情 */
  const currentDetail = ref<Conversation | null>(null)
  const messages = ref<ChatMessageVM[]>([])
  const nextBeforeId = ref<number | null>(null)
  const loadingMessages = ref(false)

  const current = computed<Conversation | null>(
    () => conversations.value.find((c) => c.id === currentId.value) ?? currentDetail.value,
  )
  const hasMore = computed(() => conversations.value.length < total.value)

  let listSeq = 0
  let selectSeq = 0

  async function loadList(reset = true) {
    const seq = ++listSeq
    loadingList.value = true
    try {
      const nextPage = reset ? 1 : page.value + 1
      const result = await conversationApi.list({ keyword: keyword.value.trim(), page: nextPage, size: PAGE_SIZE })
      if (seq !== listSeq) {
        return
      }
      conversations.value = reset ? result.items : [...conversations.value, ...result.items]
      total.value = result.total
      page.value = nextPage
    } finally {
      if (seq === listSeq) {
        loadingList.value = false
      }
    }
  }

  /** 切换会话并加载最新一页消息；正在流式输出的那一轮用内存里的实时对象替换服务端的占位消息 */
  async function select(id: number | null) {
    const seq = ++selectSeq
    currentId.value = id
    messages.value = []
    nextBeforeId.value = null
    currentDetail.value = null
    if (id === null) {
      return
    }
    loadingMessages.value = true
    try {
      const [page, detail] = await Promise.all([
        conversationApi.messages(id, null, PAGE_SIZE),
        conversations.value.some((c) => c.id === id) ? Promise.resolve(null) : conversationApi.get(id),
      ])
      if (seq !== selectSeq) {
        return
      }
      currentDetail.value = detail
      let items = page.items.map(fromMessageView)
      const live = useChatStreamStore().active
      if (live && live.conversationId === id) {
        const liveIds = new Set([live.user.id, live.answer.id].filter((x): x is number => x !== null))
        items = items.filter((m) => m.id === null || !liveIds.has(m.id))
        items.push(live.user, live.answer)
      }
      messages.value = items
      nextBeforeId.value = page.nextBeforeId
    } finally {
      if (seq === selectSeq) {
        loadingMessages.value = false
      }
    }
  }

  async function loadEarlier() {
    const id = currentId.value
    if (id === null || nextBeforeId.value === null) {
      return
    }
    const seq = selectSeq
    loadingMessages.value = true
    try {
      const page = await conversationApi.messages(id, nextBeforeId.value, PAGE_SIZE)
      if (seq !== selectSeq) {
        return
      }
      messages.value = [...page.items.map(fromMessageView), ...messages.value]
      nextBeforeId.value = page.nextBeforeId
    } finally {
      if (seq === selectSeq) {
        loadingMessages.value = false
      }
    }
  }

  async function create(mode: ConversationMode, kbId: number | null, title?: string) {
    const conversation = await conversationApi.create(mode, kbId, title)
    conversations.value.unshift(conversation)
    total.value += 1
    await select(conversation.id)
    return conversation
  }

  async function rename(id: number, title: string) {
    upsert(await conversationApi.rename(id, title), false)
  }

  async function remove(id: number) {
    await conversationApi.remove(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    total.value = Math.max(0, total.value - 1)
    if (currentId.value === id) {
      await select(null)
    }
  }

  /** 一轮问答结束后刷新会话（标题可能已自动生成，更新时间变化后移到最前） */
  async function refreshOne(id: number) {
    try {
      upsert(await conversationApi.get(id), true)
    } catch {
      // 会话可能已被删除，忽略
    }
  }

  function upsert(conversation: Conversation, moveToTop: boolean) {
    const index = conversations.value.findIndex((c) => c.id === conversation.id)
    if (index >= 0) {
      conversations.value.splice(index, 1)
    } else {
      total.value += 1
    }
    if (moveToTop || index < 0) {
      conversations.value.unshift(conversation)
    } else {
      conversations.value.splice(index, 0, conversation)
    }
    if (currentDetail.value?.id === conversation.id) {
      currentDetail.value = conversation
    }
  }

  function reset() {
    conversations.value = []
    total.value = 0
    page.value = 1
    keyword.value = ''
    currentId.value = null
    currentDetail.value = null
    messages.value = []
    nextBeforeId.value = null
  }

  return {
    conversations,
    total,
    keyword,
    loadingList,
    hasMore,
    currentId,
    current,
    messages,
    nextBeforeId,
    loadingMessages,
    loadList,
    select,
    loadEarlier,
    create,
    rename,
    remove,
    refreshOne,
    reset,
  }
})
