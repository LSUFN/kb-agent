<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

import type { ConversationMode } from '@/types/api'
import type { ChatMessageVM } from '@/types/chat'
import { CITATION_MODE_LABELS } from '@/utils/labels'

const props = defineProps<{
  message: ChatMessageVM | null
  mode: ConversationMode
  activeIndex: number | null
}>()

const emit = defineEmits<{ select: [index: number] }>()

interface PanelItem {
  index: number
  filename: string
  page: number
  score: number
  snippet: string | null
}

/** 引用确定之前（KB_QA 流式输出中）先展示检索到的资料 */
const pendingCandidates = computed(
  () => !!props.message && props.message.citationMode === null && props.message.candidates.length > 0,
)

const items = computed<PanelItem[]>(() => {
  const m = props.message
  if (!m) {
    return []
  }
  if (m.citationMode === null) {
    return m.candidates.map((c) => ({ index: c.index, filename: c.filename, page: c.page, score: c.score, snippet: null }))
  }
  return m.citations.map((c) => ({ index: c.index, filename: c.filename, page: c.page, score: c.score, snippet: c.snippet }))
})

const title = computed(() => {
  const m = props.message
  if (!m) {
    return '引用来源'
  }
  if (pendingCandidates.value) {
    return `检索到 ${m.candidates.length} 条资料`
  }
  // AGENT 模式没有调用检索工具时也是 NONE，但并不是“拒答”
  if (!m.citationMode || (props.mode === 'AGENT' && m.citationMode === 'NONE')) {
    return '引用来源'
  }
  return CITATION_MODE_LABELS[m.citationMode]
})

const emptyText = computed(() => {
  const m = props.message
  if (m?.citationMode === 'NONE' && m.status !== 'STREAMING') {
    return props.mode === 'KB_QA'
      ? '知识库中没有找到达到相似度阈值的资料，本轮直接拒答，没有调用模型'
      : '本轮没有检索知识库，所以没有引用'
  }
  return '点击回答中的角标查看引用来源'
})

const list = ref<HTMLElement>()

watch(
  () => props.activeIndex,
  async (index) => {
    if (index === null) {
      return
    }
    await nextTick()
    list.value?.querySelector(`[data-index="${index}"]`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  },
)
</script>

<template>
  <div class="citation-panel">
    <div class="panel-title">
      <span>{{ title }}</span>
      <span v-if="items.length" class="muted">{{ items.length }} 条</span>
    </div>
    <div ref="list" class="panel-body">
      <template v-if="items.length">
        <p v-if="message?.citationMode === 'FALLBACK'" class="note">
          模型没有在回答中标注引用编号，以下是本轮检索到的资料，供核对。
        </p>
        <div
          v-for="item in items"
          :key="item.index"
          class="cite-card"
          :class="{ active: item.index === activeIndex }"
          :data-index="item.index"
          @click="emit('select', item.index)"
        >
          <div class="cite-head">
            <span class="cite-no">{{ item.index }}</span>
            <span class="cite-file ellipsis" :title="item.filename">{{ item.filename }}</span>
          </div>
          <div class="cite-meta">
            <span>第 {{ item.page }} 页</span>
            <span>相似度 {{ item.score.toFixed(3) }}</span>
          </div>
          <div v-if="item.snippet" class="cite-snippet">{{ item.snippet }}</div>
        </div>
      </template>
      <el-empty v-else :image-size="64" :description="emptyText" />
    </div>
  </div>
</template>

<style scoped>
.citation-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  font-weight: 600;
  font-size: 14px;
  border-bottom: 1px solid var(--app-border);
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.note {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
  padding: 8px 10px;
  border-radius: 6px;
}

.cite-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fff;
  cursor: pointer;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.cite-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.cite-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.cite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cite-no {
  flex: none;
  width: 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  font-size: 12px;
  border-radius: 10px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.cite-card.active .cite-no {
  color: #fff;
  background: var(--el-color-primary);
}

.cite-file {
  font-size: 13px;
  font-weight: 500;
}

.cite-meta {
  display: flex;
  gap: 12px;
  margin: 6px 0 0 28px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cite-snippet {
  margin: 8px 0 0 28px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cite-card.active .cite-snippet {
  -webkit-line-clamp: unset;
  line-clamp: unset;
}
</style>
