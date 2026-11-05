<script setup lang="ts">
import { ref, watch } from 'vue'

import { documentApi } from '@/api/knowledge'
import type { ChunkView, DocumentView } from '@/types/api'
import { notifyError } from '@/utils/notify'

const props = defineProps<{ doc: DocumentView | null }>()
const visible = defineModel<boolean>({ required: true })

const chunks = ref<ChunkView[]>([])
const page = ref(1)
const size = 10
const total = ref(0)
const loading = ref(false)

async function load() {
  if (!props.doc) {
    return
  }
  loading.value = true
  try {
    const result = await documentApi.chunks(props.doc.id, page.value, size)
    chunks.value = result.items
    total.value = result.total
  } catch (e) {
    notifyError(e, '加载分片失败')
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    page.value = 1
    chunks.value = []
    void load()
  }
})
</script>

<template>
  <el-drawer v-model="visible" :title="`分片预览：${doc?.filename ?? ''}`" size="640px">
    <div v-loading="loading">
      <p class="muted">
        共 {{ total }} 个分片 · {{ doc?.pageCount ?? '-' }} 页 · 向量模型 {{ doc?.embeddingModel ?? '-' }}。
        分片按句子边界切分，不跨页，相邻分片有少量重叠。
      </p>
      <div v-for="chunk in chunks" :key="chunk.chunkIndex" class="chunk">
        <div class="chunk-head">
          <el-tag size="small" effect="plain">#{{ chunk.chunkIndex }}</el-tag>
          <span class="muted">第 {{ chunk.pageNumber }} 页 · {{ chunk.charCount }} 字</span>
        </div>
        <div class="chunk-content">{{ chunk.content }}</div>
      </div>
      <el-empty v-if="!loading && !chunks.length" description="没有分片" />
      <div v-if="total > size" class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          small
          @current-change="load"
        />
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.chunk {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.chunk-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.chunk-content {
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
