<script setup lang="ts">
import { Document, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { computed } from 'vue'

import MarkdownView from '@/components/MarkdownView.vue'
import type { ConversationMode } from '@/types/api'
import { reportIdsOf, type ChatMessageVM } from '@/types/chat'
import { formatMs } from '@/utils/format'
import { ERROR_MESSAGES } from '@/utils/labels'

import ToolTimeline from './ToolTimeline.vue'

const props = defineProps<{
  message: ChatMessageVM
  mode: ConversationMode
  /** 当前高亮的引用编号（只有被选中的消息会传） */
  activeCite: number | null
  /** 是否显示 traceId 链接（管理员） */
  showTrace: boolean
  /** 正在进行别的流式问答时不能重试 */
  retryDisabled: boolean
}>()

const emit = defineEmits<{
  cite: [index: number]
  retry: []
  openReport: [id: number]
}>()

const streaming = computed(() => props.message.status === 'STREAMING')

/** 流式输出中引用还没确定；结束后只有 NORMAL 模式下的引用编号可以点击 */
const citationSet = computed<ReadonlySet<number> | undefined>(() => {
  if (streaming.value) {
    return undefined
  }
  const m = props.message
  return new Set(m.citationMode === 'NORMAL' ? m.citations.map((c) => c.index) : [])
})

const waitingText = computed(() => {
  const m = props.message
  if (props.mode === 'KB_QA') {
    return m.candidates.length ? `已检索到 ${m.candidates.length} 条资料，正在生成回答…` : '正在检索知识库…'
  }
  return m.toolSteps.some((s) => s.running) ? '正在调用工具…' : '正在分析问题…'
})

const errorText = computed(() => {
  const m = props.message
  return m.errorMessage ?? (m.errorCode ? ERROR_MESSAGES[m.errorCode] : undefined) ?? '回答失败'
})

const reportIds = computed(() => reportIdsOf(props.message))

const unverified = computed(() => props.message.unverifiedNumbers ?? [])

const stats = computed(() => {
  const m = props.message
  const parts: string[] = []
  if (m.latencyMs != null) {
    parts.push(`耗时 ${formatMs(m.latencyMs)}`)
  }
  if (m.firstTokenMs != null) {
    parts.push(`首字 ${formatMs(m.firstTokenMs)}`)
  }
  if (m.usage && (m.usage.prompt != null || m.usage.completion != null)) {
    parts.push(`Token ${m.usage.prompt ?? '-'} / ${m.usage.completion ?? '-'}${m.usage.estimated ? '（估算）' : ''}`)
  }
  return parts.join(' · ')
})
</script>

<template>
  <div v-if="message.role === 'USER'" class="msg user">
    <div class="bubble">{{ message.content }}</div>
  </div>

  <div v-else class="msg assistant">
    <el-avatar :size="30" class="bot-avatar">AI</el-avatar>
    <div class="body">
      <ToolTimeline v-if="message.toolSteps.length" :steps="message.toolSteps" />

      <div v-if="streaming && !message.content" class="waiting">
        <span class="dot-flashing" />
        <span>{{ waitingText }}</span>
      </div>

      <div v-if="message.content" class="answer" :class="{ streaming }">
        <MarkdownView
          :source="message.content"
          :citations="citationSet"
          :active-cite="activeCite"
          @cite="(n) => emit('cite', n)"
        />
      </div>

      <div v-if="!streaming && message.citationMode === 'NORMAL' && message.citations.length" class="cite-chips">
        <span class="muted">引用：</span>
        <el-tag
          v-for="c in message.citations"
          :key="c.index"
          size="small"
          :effect="activeCite === c.index ? 'dark' : 'plain'"
          class="chip"
          @click="emit('cite', c.index)"
        >
          [{{ c.index }}] {{ c.filename }} · 第 {{ c.page }} 页
        </el-tag>
      </div>

      <div v-if="reportIds.length" class="reports">
        <el-button
          v-for="id in reportIds"
          :key="id"
          size="small"
          type="primary"
          plain
          :icon="Document"
          @click="emit('openReport', id)"
        >
          查看报告 #{{ id }}
        </el-button>
      </div>

      <el-alert
        v-if="message.status === 'FAILED'"
        type="error"
        :closable="false"
        show-icon
        class="status-alert"
        :title="errorText"
      />
      <div v-else-if="message.status === 'INTERRUPTED'" class="interrupted">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ message.errorMessage ?? '回答已中断，已生成的部分已保存' }}</span>
      </div>

      <div class="footer">
        <el-tooltip v-if="unverified.length" placement="top">
          <template #content>以下数字在本轮工具结果中找不到出处：{{ unverified.join('、') }}</template>
          <el-tag type="warning" size="small" effect="light">数字核对：{{ unverified.length }} 个数字未找到出处</el-tag>
        </el-tooltip>
        <el-tag
          v-else-if="message.unverifiedNumbers && message.toolSteps.length && message.status === 'OK'"
          type="success"
          size="small"
          effect="plain"
        >
          数字核对通过
        </el-tag>
        <span v-if="mode === 'KB_QA' && message.citationMode === 'NONE'" class="muted">知识库中没有相关资料，未调用模型</span>
        <span v-if="stats" class="muted">{{ stats }}</span>
        <RouterLink
          v-if="showTrace && message.traceId && !streaming"
          class="muted trace"
          :to="{ name: 'admin-logs', query: { traceId: message.traceId } }"
          title="在调用日志中查看本轮的模型调用和工具调用"
        >
          traceId {{ message.traceId.slice(0, 8) }}…
        </RouterLink>
        <el-button
          v-if="message.status === 'FAILED' || message.status === 'INTERRUPTED'"
          size="small"
          text
          type="primary"
          :icon="Refresh"
          :disabled="retryDisabled"
          @click="emit('retry')"
        >
          重新提问
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.msg {
  display: flex;
  margin: 18px 0;
}

.msg.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 12px 12px 2px 12px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg.assistant {
  gap: 10px;
  align-items: flex-start;
}

.bot-avatar {
  flex: none;
  background: linear-gradient(135deg, #409eff, #36cfc9);
  font-size: 12px;
}

.body {
  flex: 1;
  min-width: 0;
  max-width: calc(100% - 40px);
}

.answer {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 2px 12px 12px 12px;
  padding: 10px 14px;
}

.answer.streaming :deep(.markdown-body > :last-child)::after {
  content: '▍';
  margin-left: 2px;
  color: var(--el-color-primary);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.waiting {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 2px 12px 12px 12px;
}

.dot-flashing {
  position: relative;
  width: 6px;
  height: 6px;
  margin: 0 10px;
  border-radius: 3px;
  background: var(--el-color-primary);
  animation: dot 1s infinite linear alternate;
  animation-delay: 0.5s;
}

.dot-flashing::before,
.dot-flashing::after {
  content: '';
  position: absolute;
  top: 0;
  width: 6px;
  height: 6px;
  border-radius: 3px;
  background: var(--el-color-primary);
  animation: dot 1s infinite alternate;
}

.dot-flashing::before {
  left: -10px;
  animation-delay: 0s;
}

.dot-flashing::after {
  left: 10px;
  animation-delay: 1s;
}

@keyframes dot {
  0% {
    opacity: 1;
  }
  100% {
    opacity: 0.2;
  }
}

.cite-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.chip {
  cursor: pointer;
  max-width: 100%;
}

.reports {
  margin-top: 8px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.status-alert {
  margin-top: 8px;
}

.interrupted {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--el-color-warning-dark-2);
}

.footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 6px;
  min-height: 22px;
}

.trace {
  text-decoration: none;
}

.trace:hover {
  color: var(--el-color-primary);
}
</style>
