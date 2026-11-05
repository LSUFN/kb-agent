<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ref } from 'vue'

import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase, SearchResult } from '@/types/api'
import { notifyError } from '@/utils/notify'

const props = defineProps<{ kb: KnowledgeBase }>()

const query = ref('')
const topK = ref(5)
const result = ref<SearchResult | null>(null)
const loading = ref(false)
const expanded = ref<Set<string>>(new Set())

async function search() {
  const q = query.value.trim()
  if (!q) {
    return
  }
  loading.value = true
  try {
    result.value = await knowledgeApi.search(props.kb.id, q, topK.value)
    expanded.value = new Set()
  } catch (e) {
    notifyError(e, '检索失败')
  } finally {
    loading.value = false
  }
}

function toggle(key: string) {
  const next = new Set(expanded.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expanded.value = next
}
</script>

<template>
  <div>
    <p class="muted intro">
      只做向量检索、不调用大模型，用于检查文档切分和相似度是否合理。达到阈值的片段才会在知识库问答中交给模型。
    </p>
    <div class="toolbar">
      <el-input
        v-model="query"
        placeholder="输入一个问题，例如：出差住宿标准是多少？"
        maxlength="500"
        clearable
        class="query"
        @keyup.enter="search"
      />
      <span class="muted">Top K</span>
      <el-input-number v-model="topK" :min="1" :max="20" controls-position="right" class="topk" />
      <el-button type="primary" :icon="Search" :loading="loading" @click="search">检索</el-button>
    </div>

    <template v-if="result">
      <el-alert :closable="false" type="info" class="threshold">
        问答使用的相似度阈值：<b>{{ result.minScore.toFixed(2) }}</b>；
        本次 {{ result.hits.length }} 个结果中有 {{ result.hits.filter((h) => h.aboveThreshold).length }} 个达到阈值
      </el-alert>
      <div
        v-for="(hit, i) in result.hits"
        :key="`${hit.documentId}-${hit.chunkIndex}`"
        class="hit"
        :class="{ below: !hit.aboveThreshold }"
      >
        <div class="hit-head">
          <span class="rank">{{ i + 1 }}</span>
          <span class="score mono">{{ hit.score.toFixed(4) }}</span>
          <el-tag size="small" :type="hit.aboveThreshold ? 'success' : 'info'" effect="light">
            {{ hit.aboveThreshold ? '达到阈值' : '低于阈值，问答不使用' }}
          </el-tag>
          <span class="file ellipsis">{{ hit.filename }}</span>
          <span class="muted">第 {{ hit.pageNumber }} 页 · 分片 #{{ hit.chunkIndex }}</span>
        </div>
        <el-progress
          :percentage="Math.max(0, Math.min(100, hit.score * 100))"
          :show-text="false"
          :stroke-width="4"
          :status="hit.aboveThreshold ? 'success' : undefined"
          class="bar"
        />
        <div
          class="content"
          :class="{ clamp: !expanded.has(`${hit.documentId}-${hit.chunkIndex}`) }"
          @click="toggle(`${hit.documentId}-${hit.chunkIndex}`)"
        >
          {{ hit.content }}
        </div>
      </div>
      <el-empty v-if="!result.hits.length" description="没有检索到任何片段（知识库中可能还没有可用文档）" />
    </template>
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 12px;
}

.query {
  flex: 1;
  min-width: 260px;
}

.topk {
  width: 100px;
}

.threshold {
  margin-bottom: 12px;
}

.hit {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.hit.below {
  background: var(--el-fill-color-lighter);
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.rank {
  flex: none;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 11px;
  font-size: 12px;
  background: var(--el-fill-color);
}

.score {
  font-weight: 600;
  font-size: 13px;
}

.file {
  font-size: 13px;
  min-width: 0;
}

.bar {
  margin: 8px 0;
}

.content {
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  cursor: pointer;
}

.content.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
