<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { adminApi } from '@/api/admin'
import type { LlmCallView, ToolCallView, ToolDecision } from '@/types/api'
import { formatMs, formatTime, prettyJson } from '@/utils/format'
import { DECISION_LABELS, toolLabel } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const props = defineProps<{ traceId: string | null }>()
const visible = defineModel<boolean>({ required: true })

type Entry =
  | { kind: 'llm'; at: string; id: string; data: LlmCallView }
  | { kind: 'tool'; at: string; id: string; data: ToolCallView }

const llmCalls = ref<LlmCallView[]>([])
const toolCalls = ref<ToolCallView[]>([])
const loading = ref(false)

/** 一次请求（traceId）中的模型调用和工具调用，按时间排成一条时间线 */
const entries = computed<Entry[]>(() =>
  [
    ...llmCalls.value.map((d): Entry => ({ kind: 'llm', at: d.createdAt, id: `l${d.id}`, data: d })),
    ...toolCalls.value.map((d): Entry => ({ kind: 'tool', at: d.createdAt, id: `t${d.id}`, data: d })),
  ].sort((a, b) => a.at.localeCompare(b.at) || a.id.localeCompare(b.id)),
)

const totals = computed(() => ({
  prompt: llmCalls.value.reduce((s, c) => s + (c.promptTokens ?? 0), 0),
  completion: llmCalls.value.reduce((s, c) => s + (c.completionTokens ?? 0), 0),
  denied: toolCalls.value.filter((c) => c.decision !== 'ALLOWED').length,
}))

watch(
  () => [visible.value, props.traceId] as const,
  async ([open, id]) => {
    if (!open || !id) {
      return
    }
    loading.value = true
    llmCalls.value = []
    toolCalls.value = []
    try {
      const [llm, tool] = await Promise.all([
        adminApi.llmCalls({ traceId: id }, 1, 100),
        adminApi.toolCalls({ traceId: id }, 1, 100),
      ])
      llmCalls.value = llm.items
      toolCalls.value = tool.items
    } catch (e) {
      notifyError(e, '加载失败')
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function decisionType(decision: ToolDecision) {
  return decision === 'ALLOWED' ? 'success' : decision === 'DENIED' ? 'danger' : 'warning'
}
</script>

<template>
  <el-drawer v-model="visible" title="按 traceId 串联查看" size="620px">
    <div v-loading="loading">
      <div class="summary">
        <div class="mono trace">{{ traceId }}</div>
        <div class="muted">
          模型调用 {{ llmCalls.length }} 次 · 工具调用 {{ toolCalls.length }} 次（被拒绝 {{ totals.denied }} 次） ·
          Token {{ totals.prompt }} / {{ totals.completion }}
        </div>
      </div>
      <el-timeline v-if="entries.length">
        <el-timeline-item
          v-for="e in entries"
          :key="e.id"
          :timestamp="formatTime(e.at)"
          placement="top"
          :type="e.kind === 'llm' ? (e.data.success ? 'primary' : 'danger') : decisionType(e.data.decision)"
        >
          <div v-if="e.kind === 'llm'" class="card">
            <div class="head">
              <el-tag size="small" effect="dark">{{ e.data.callType === 'CHAT' ? '模型对话' : '向量化' }}</el-tag>
              <span class="mono">{{ e.data.model }}</span>
              <span class="muted">耗时 {{ formatMs(e.data.latencyMs) }}</span>
              <span v-if="e.data.firstTokenMs != null" class="muted">首字 {{ formatMs(e.data.firstTokenMs) }}</span>
            </div>
            <div class="muted">
              Token {{ e.data.promptTokens ?? '-' }} / {{ e.data.completionTokens ?? '-' }}
              <template v-if="e.data.usageEstimated">（估算）</template>
              <template v-if="e.data.outputChars != null"> · 输出 {{ e.data.outputChars }} 字</template>
            </div>
            <div v-if="!e.data.success" class="error">{{ e.data.errorCode }} {{ e.data.errorMessage }}</div>
            <pre v-if="e.data.inputPreview" class="pre">{{ e.data.inputPreview }}</pre>
          </div>
          <div v-else class="card">
            <div class="head">
              <el-tag size="small" :type="decisionType(e.data.decision)">
                {{ DECISION_LABELS[e.data.decision] ?? e.data.decision }}
              </el-tag>
              <b>{{ toolLabel(e.data.toolName) }}</b>
              <span class="mono muted">{{ e.data.toolCallId }}</span>
              <span class="muted">耗时 {{ formatMs(e.data.latencyMs) }}</span>
            </div>
            <div class="muted">
              {{ e.data.userRole }} · 生效范围 {{ e.data.effectiveScope.join('、') || '-' }}
            </div>
            <div v-if="e.data.resultSummary">{{ e.data.resultSummary }}</div>
            <pre class="pre">{{ prettyJson(e.data.args) }}</pre>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else-if="!loading" description="没有找到这次请求的调用记录" />
    </div>
  </el-drawer>
</template>

<style scoped>
.summary {
  margin-bottom: 16px;
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
}

.trace {
  margin-bottom: 4px;
  word-break: break-all;
}

.card {
  font-size: 13px;
  line-height: 1.7;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.error {
  color: var(--el-color-danger);
}

.pre {
  margin: 6px 0 0;
  padding: 6px 10px;
  font-size: 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
}
</style>
