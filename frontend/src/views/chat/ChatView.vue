<script setup lang="ts">
import { ChatLineSquare, Cpu, Promotion, Reading, VideoPause } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { knowledgeApi } from '@/api/knowledge'
import ReportPreviewDrawer from '@/components/ReportPreviewDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import { useChatStreamStore } from '@/stores/chatStream'
import { useConversationStore } from '@/stores/conversation'
import type { ConversationMode, KnowledgeBase } from '@/types/api'
import type { ChatMessageVM } from '@/types/chat'
import { MODE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

import CitationPanel from './CitationPanel.vue'
import ConversationSidebar from './ConversationSidebar.vue'
import MessageItem from './MessageItem.vue'
import NewConversationDialog from './NewConversationDialog.vue'

const MAX_QUESTION = 2000

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const conv = useConversationStore()
const stream = useChatStreamStore()

const knowledgeBases = ref<KnowledgeBase[]>([])
const input = ref('')
const dialogVisible = ref(false)
const dialogMode = ref<ConversationMode>('KB_QA')
const reportVisible = ref(false)
const reportId = ref<number | null>(null)
const showPanel = ref(window.innerWidth >= 1280)
const scroller = ref<HTMLElement>()
/** 用户停留在底部附近时，新内容到来自动滚动；往上翻看历史时不打扰 */
const stickToBottom = ref(true)
/** 右侧面板展示哪条消息的引用；为 null 时跟随最新一条回答 */
const selected = ref<{ key: string; index: number | null } | null>(null)

const current = computed(() => conv.current)
const kbName = computed(() => {
  const kbId = current.value?.kbId
  if (kbId == null) {
    return '全部有权限的知识库'
  }
  return knowledgeBases.value.find((kb) => kb.id === kbId)?.name ?? `知识库 #${kbId}`
})

const liveHere = computed(() => stream.active !== null && stream.active.conversationId === conv.currentId)
const busyElsewhere = computed(() => stream.active !== null && !liveHere.value)
const canSend = computed(
  () => !!current.value && !stream.streaming && input.value.trim().length > 0 && input.value.length <= MAX_QUESTION,
)

const lastAnswer = computed<ChatMessageVM | null>(() => {
  for (let i = conv.messages.length - 1; i >= 0; i--) {
    if (conv.messages[i]!.role === 'ASSISTANT') {
      return conv.messages[i]!
    }
  }
  return null
})

const panelMessage = computed<ChatMessageVM | null>(() => {
  const key = selected.value?.key
  return (key && conv.messages.find((m) => m.key === key)) || lastAnswer.value
})

const activeIndex = computed(() =>
  selected.value && panelMessage.value?.key === selected.value.key ? selected.value.index : null,
)

const placeholder = computed(() =>
  current.value?.mode === 'AGENT'
    ? '提出业务分析问题，例如：上个月各区域的订单金额和取消率怎么样？（Enter 发送，Shift+Enter 换行）'
    : '向知识库提问，例如：出差住宿标准是多少？（Enter 发送，Shift+Enter 换行）',
)

const examples: Record<ConversationMode, string[]> = {
  KB_QA: ['出差住宿标准是多少？', '差旅费报销需要提交哪些材料？'],
  AGENT: [
    '上个月各区域的订单金额和取消率怎么样？',
    '最近 30 天华东区域有哪些类型的异常事件？未解决的有多少？',
    '生成上个月华东区域的订单经营报告',
  ],
}

onMounted(async () => {
  conv.loadList().catch((e) => notifyError(e, '加载会话失败'))
  knowledgeApi
    .list()
    .then((page) => (knowledgeBases.value = page.items))
    .catch(() => undefined)
})

// 地址栏是唯一的“当前会话”来源：/chat/12 打开会话 12，/chat 显示欢迎页
watch(
  () => route.params.id,
  async (raw) => {
    const id = typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null
    if (id === conv.currentId) {
      return
    }
    selected.value = null
    stickToBottom.value = true
    try {
      await conv.select(id)
      scrollToBottom()
    } catch (e) {
      notifyError(e, '加载会话失败')
      await conv.select(null)
      await router.replace({ name: 'chat' })
    }
  },
  { immediate: true },
)

// 当前会话被删除后回到欢迎页
watch(
  () => conv.currentId,
  (id) => {
    if (id === null && route.params.id) {
      void router.replace({ name: 'chat' })
    }
  },
)

// 新消息、流式内容、工具步骤变化时自动滚动
watch(
  () => {
    const last = conv.messages[conv.messages.length - 1]
    return [conv.messages.length, last?.content.length, last?.toolSteps.length, last?.status]
  },
  () => {
    if (stickToBottom.value) {
      scrollToBottom()
    }
  },
  { flush: 'post' },
)

function scrollToBottom() {
  void nextTick(() => {
    const el = scroller.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function onScroll() {
  const el = scroller.value
  if (el) {
    stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }
}

async function loadEarlier() {
  const el = scroller.value
  const before = el ? el.scrollHeight - el.scrollTop : 0
  try {
    await conv.loadEarlier()
    await nextTick()
    if (el) {
      el.scrollTop = el.scrollHeight - before
    }
  } catch (e) {
    notifyError(e, '加载消息失败')
  }
}

function open(id: number) {
  void router.push({ name: 'chat', params: { id } })
}

function openDialog(mode: ConversationMode = 'KB_QA') {
  dialogMode.value = mode
  dialogVisible.value = true
}

async function createConversation(mode: ConversationMode, kbId: number | null, title: string) {
  try {
    const c = await conv.create(mode, kbId, title)
    dialogVisible.value = false
    await router.push({ name: 'chat', params: { id: c.id } })
  } catch (e) {
    notifyError(e, '创建会话失败')
  }
}

/** 欢迎页的示例问题：新建会话并直接提问 */
async function quickAsk(mode: ConversationMode, question: string) {
  try {
    const c = await conv.create(mode, null)
    await router.push({ name: 'chat', params: { id: c.id } })
    void ask(question)
  } catch (e) {
    notifyError(e, '创建会话失败')
  }
}

async function ask(content: string) {
  const id = conv.currentId
  if (id === null || stream.streaming) {
    return
  }
  selected.value = null
  stickToBottom.value = true
  await stream.send(id, content)
}

function send() {
  if (!canSend.value) {
    return
  }
  const content = input.value.trim()
  input.value = ''
  void ask(content)
}

function onEnter(event: KeyboardEvent | Event) {
  const e = event as KeyboardEvent
  if (e.isComposing || e.shiftKey) {
    return
  }
  e.preventDefault()
  send()
}

function retry(message: ChatMessageVM) {
  const index = conv.messages.indexOf(message)
  for (let i = index - 1; i >= 0; i--) {
    const m = conv.messages[i]!
    if (m.role === 'USER') {
      void ask(m.content)
      return
    }
  }
}

function onCite(message: ChatMessageVM, index: number) {
  selected.value = { key: message.key, index }
  showPanel.value = true
}

function onPanelSelect(index: number) {
  const m = panelMessage.value
  if (m) {
    selected.value = { key: m.key, index }
  }
}

function openReport(id: number) {
  reportId.value = id
  reportVisible.value = true
}
</script>

<template>
  <div class="chat-page">
    <aside class="conversations">
      <ConversationSidebar @open="open" @create="openDialog()" />
    </aside>

    <section class="main">
      <template v-if="current">
        <header class="chat-header">
          <div class="title-block">
            <div class="title ellipsis" :title="current.title">{{ current.title }}</div>
            <div class="sub">
              <el-tag size="small" :type="current.mode === 'AGENT' ? 'warning' : 'primary'" effect="light">
                {{ MODE_LABELS[current.mode] }}
              </el-tag>
              <span class="muted">
                <el-icon><Reading /></el-icon>
                {{ kbName }}
              </span>
            </div>
          </div>
          <el-button text @click="showPanel = !showPanel">{{ showPanel ? '隐藏引用面板' : '显示引用面板' }}</el-button>
        </header>

        <div ref="scroller" v-loading="conv.loadingMessages && !conv.messages.length" class="messages" @scroll="onScroll">
          <div class="messages-inner">
            <div v-if="conv.nextBeforeId !== null" class="earlier">
              <el-button text size="small" :loading="conv.loadingMessages" @click="loadEarlier">加载更早的消息</el-button>
            </div>
            <div v-if="!conv.loadingMessages && !conv.messages.length" class="empty-conv">
              <p class="muted">还没有消息，试试这些问题：</p>
              <div class="examples">
                <el-button v-for="q in examples[current.mode]" :key="q" round @click="input = q">{{ q }}</el-button>
              </div>
            </div>
            <MessageItem
              v-for="m in conv.messages"
              :key="m.key"
              :message="m"
              :mode="current.mode"
              :active-cite="selected?.key === m.key ? selected.index : null"
              :show-trace="auth.isAdmin"
              :retry-disabled="stream.streaming"
              @cite="(n) => onCite(m, n)"
              @retry="retry(m)"
              @open-report="openReport"
            />
          </div>
        </div>

        <footer class="composer">
          <div class="composer-inner">
            <el-input
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              :maxlength="MAX_QUESTION"
              show-word-limit
              resize="none"
              :placeholder="placeholder"
              @keydown.enter="onEnter"
            />
            <div class="actions">
              <span v-if="busyElsewhere" class="muted">另一个会话正在生成回答，完成后才能提问</span>
              <el-button v-if="liveHere" type="danger" plain :icon="VideoPause" @click="stream.stop()">停止生成</el-button>
              <el-button v-else type="primary" :icon="Promotion" :disabled="!canSend" @click="send">发送</el-button>
            </div>
          </div>
        </footer>
      </template>

      <div v-else class="welcome">
        <h2>你好，{{ auth.user?.displayName }}</h2>
        <p class="muted">选择左侧的会话，或者新建一个会话开始提问。</p>
        <div class="mode-cards">
          <div class="mode-card">
            <div class="mode-head">
              <el-icon class="mode-icon kb"><ChatLineSquare /></el-icon>
              <div>
                <h3>知识库问答</h3>
                <p>先检索知识库再回答，回答中的 [n] 角标可以点击查看原文出处。</p>
              </div>
            </div>
            <div class="examples">
              <el-button v-for="q in examples.KB_QA" :key="q" round size="small" @click="quickAsk('KB_QA', q)">{{ q }}</el-button>
            </div>
            <el-button type="primary" plain @click="openDialog('KB_QA')">新建知识库问答</el-button>
          </div>
          <div class="mode-card">
            <div class="mode-head">
              <el-icon class="mode-icon agent"><Cpu /></el-icon>
              <div>
                <h3>业务分析 Agent</h3>
                <p>
                  模型自动调用订单统计、异常事件统计、报告生成等工具，每次调用都在服务端鉴权和审计。
                  <template v-if="!auth.canAnalyze">当前账号只能使用知识库检索工具。</template>
                </p>
              </div>
            </div>
            <div class="examples">
              <el-button v-for="q in examples.AGENT" :key="q" round size="small" @click="quickAsk('AGENT', q)">{{ q }}</el-button>
            </div>
            <el-button type="warning" plain @click="openDialog('AGENT')">新建 Agent 会话</el-button>
          </div>
        </div>
      </div>
    </section>

    <aside v-if="current && showPanel" class="panel">
      <CitationPanel :message="panelMessage" :mode="current.mode" :active-index="activeIndex" @select="onPanelSelect" />
    </aside>

    <NewConversationDialog v-model="dialogVisible" :initial-mode="dialogMode" @create="createConversation" />
    <ReportPreviewDrawer v-model="reportVisible" :report-id="reportId" />
  </div>
</template>

<style scoped>
.chat-page {
  position: absolute;
  inset: 0;
  display: flex;
  background: var(--app-bg);
}

.conversations {
  width: 260px;
  flex: none;
  background: #fff;
  border-right: 1px solid var(--app-border);
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.panel {
  width: 320px;
  flex: none;
  background: #fafbfc;
  border-left: 1px solid var(--app-border);
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid var(--app-border);
}

.title-block {
  min-width: 0;
}

.title {
  font-size: 15px;
  font-weight: 600;
}

.sub {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.sub .muted {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.messages {
  flex: 1;
  overflow-y: auto;
}

.messages-inner {
  max-width: 920px;
  margin: 0 auto;
  padding: 4px 24px 12px;
}

.earlier {
  text-align: center;
  padding-top: 10px;
}

.empty-conv {
  text-align: center;
  padding: 60px 0 20px;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.examples .el-button + .el-button {
  margin-left: 0;
}

.composer {
  background: #fff;
  border-top: 1px solid var(--app-border);
  padding: 12px 24px;
}

.composer-inner {
  max-width: 920px;
  margin: 0 auto;
}

.actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.welcome {
  max-width: 900px;
  margin: 0 auto;
  padding: 60px 24px;
  width: 100%;
}

.welcome h2 {
  margin: 0 0 8px;
}

.mode-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
  margin-top: 24px;
}

.mode-card {
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: flex-start;
}

.mode-head {
  display: flex;
  gap: 12px;
}

.mode-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.mode-head p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.mode-icon {
  flex: none;
  font-size: 22px;
  width: 40px;
  height: 40px;
  border-radius: 10px;
}

.mode-icon.kb {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.mode-icon.agent {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}

.mode-card .examples {
  justify-content: flex-start;
}
</style>
