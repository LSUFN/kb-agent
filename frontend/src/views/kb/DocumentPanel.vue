<script setup lang="ts">
import { Delete, Refresh, UploadFilled, View } from '@element-plus/icons-vue'
import {
  ElMessage,
  ElMessageBox,
  type UploadInstance,
  type UploadProgressEvent,
  type UploadRawFile,
  type UploadRequestOptions,
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { documentApi } from '@/api/knowledge'
import type { DocumentView, KnowledgeBase } from '@/types/api'
import { formatBytes, formatDateTime } from '@/utils/format'
import { DOC_STATUS } from '@/utils/labels'
import { isApiError, notifyError } from '@/utils/notify'

import ChunkDrawer from './ChunkDrawer.vue'

const MAX_FILE_BYTES = 20 * 1024 * 1024
const POLL_INTERVAL = 2000

const props = defineProps<{ kb: KnowledgeBase }>()

const docs = ref<DocumentView[]>([])
const loading = ref(false)
const uploadRef = ref<UploadInstance>()
const chunkDoc = ref<DocumentView | null>(null)
const chunkVisible = ref(false)
const busy = ref<Set<number>>(new Set())

const canEdit = computed(() => props.kb.myAccess === 'EDIT' || props.kb.myAccess === 'MANAGE')
const activeCount = computed(() => docs.value.filter(isActive).length)

function isActive(doc: DocumentView) {
  return doc.status === 'PENDING' || doc.status === 'PROCESSING' || doc.status === 'DELETING'
}

async function load() {
  loading.value = true
  try {
    docs.value = (await documentApi.list(props.kb.id)).items
    schedulePoll()
  } catch (e) {
    notifyError(e, '加载文档失败')
  } finally {
    loading.value = false
  }
}

// ---------- 轮询：有文档在排队、处理或删除时，每 2 秒刷新这些文档的详情（含任务尝试次数） ----------

let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

function schedulePoll() {
  if (pollTimer || disposed || activeCount.value === 0) {
    return
  }
  pollTimer = setTimeout(async () => {
    pollTimer = undefined
    await pollOnce()
    schedulePoll()
  }, POLL_INTERVAL)
}

async function pollOnce() {
  const actives = docs.value.filter(isActive)
  await Promise.all(
    actives.map(async (doc) => {
      try {
        replace(await documentApi.get(doc.id))
      } catch (e) {
        if (isApiError(e) && e.status === 404) {
          // 删除完成：文档记录已被异步任务删除
          docs.value = docs.value.filter((d) => d.id !== doc.id)
        }
      }
    }),
  )
}

function replace(doc: DocumentView) {
  const index = docs.value.findIndex((d) => d.id === doc.id)
  if (index >= 0) {
    docs.value.splice(index, 1, doc)
  } else {
    docs.value.unshift(doc)
  }
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(pollTimer)
})

// ---------- 上传 ----------

function beforeUpload(file: UploadRawFile) {
  if (!/\.pdf$/i.test(file.name)) {
    ElMessage.warning(`${file.name}：只支持 PDF 文件`)
    return false
  }
  if (file.size > MAX_FILE_BYTES) {
    ElMessage.warning(`${file.name}：文件超过 20 MB`)
    return false
  }
  return true
}

async function upload(options: UploadRequestOptions) {
  const { document, created } = await documentApi.upload(props.kb.id, options.file, (percent) =>
    options.onProgress({ percent } as UploadProgressEvent),
  )
  replace(document)
  if (created) {
    ElMessage.success(`${options.file.name} 已上传，正在后台解析和向量化`)
  } else {
    ElMessage.info(`知识库中已有内容相同的文档：${document.filename}`)
  }
  schedulePoll()
  return document
}

function onUploadSuccess(_response: unknown, file: { uid: number }) {
  setTimeout(() => uploadRef.value?.handleRemove(file as never), 1500)
}

function onUploadError(error: Error) {
  notifyError(error, '上传失败')
}

// ---------- 操作 ----------

async function withBusy(doc: DocumentView, action: () => Promise<void>) {
  busy.value = new Set(busy.value).add(doc.id)
  try {
    await action()
  } finally {
    const next = new Set(busy.value)
    next.delete(doc.id)
    busy.value = next
  }
}

function reprocess(doc: DocumentView) {
  return withBusy(doc, async () => {
    try {
      replace(await documentApi.reprocess(doc.id))
      ElMessage.success('已重新提交处理')
      schedulePoll()
    } catch (e) {
      notifyError(e, '重试失败')
    }
  })
}

async function remove(doc: DocumentView) {
  try {
    await ElMessageBox.confirm(`删除文档“${doc.filename}”？删除后检索不到它的内容。`, '删除文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await withBusy(doc, async () => {
    try {
      replace(await documentApi.remove(doc.id))
      schedulePoll()
    } catch (e) {
      notifyError(e, '删除失败')
    }
  })
}

function openChunks(doc: DocumentView) {
  chunkDoc.value = doc
  chunkVisible.value = true
}

function progressText(doc: DocumentView): string {
  const task = doc.task
  if (doc.status === 'PROCESSING') {
    return task && task.attempt > 1 ? `第 ${task.attempt}/${task.maxAttempts} 次尝试` : '解析、切分、向量化中'
  }
  if (doc.status === 'PENDING') {
    if (task && task.attempt > 0) {
      return `第 ${task.attempt} 次失败，${formatDateTime(task.nextRunAt).slice(11)} 自动重试`
    }
    return '等待执行'
  }
  if (doc.status === 'DELETING') {
    return '正在删除文件'
  }
  return ''
}
</script>

<template>
  <div>
    <el-upload
      v-if="canEdit"
      ref="uploadRef"
      drag
      multiple
      accept=".pdf,application/pdf"
      :http-request="upload"
      :before-upload="beforeUpload"
      :on-success="onUploadSuccess"
      :on-error="onUploadError"
      class="uploader"
    >
      <el-icon class="upload-icon"><UploadFilled /></el-icon>
      <div class="el-upload__text">把 PDF 拖到这里，或 <em>点击选择文件</em></div>
      <template #tip>
        <div class="muted">单个文件不超过 20 MB；上传后在后台解析、切分和向量化，完成后状态变为“可用”</div>
      </template>
    </el-upload>

    <div class="toolbar">
      <span class="muted">共 {{ docs.length }} 个文档<template v-if="activeCount">，{{ activeCount }} 个正在处理（自动刷新中）</template></span>
      <span class="spacer" />
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="docs" row-key="id" empty-text="还没有文档">
      <el-table-column prop="filename" label="文件名" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" min-width="200">
        <template #default="{ row }">
          <div class="status-cell">
            <el-tag size="small" :type="DOC_STATUS[row.status as DocumentView['status']].type">
              {{ DOC_STATUS[row.status as DocumentView['status']].label }}
            </el-tag>
            <span v-if="progressText(row)" class="muted">{{ progressText(row) }}</span>
            <el-tooltip v-if="row.status === 'FAILED'" :content="row.errorMessage ?? ''" placement="top">
              <span class="error-code mono">{{ row.errorCode }}</span>
            </el-tooltip>
          </div>
          <div v-if="row.status === 'FAILED' && row.errorMessage" class="error-msg">{{ row.errorMessage }}</div>
        </template>
      </el-table-column>
      <el-table-column label="页数" width="70" align="right">
        <template #default="{ row }">{{ row.pageCount ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="分片" width="70" align="right">
        <template #default="{ row }">{{ row.status === 'READY' ? row.chunkCount : '-' }}</template>
      </el-table-column>
      <el-table-column label="大小" width="90" align="right">
        <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column label="上传时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" :disabled="row.status !== 'READY'" @click="openChunks(row)">
            分片
          </el-button>
          <template v-if="canEdit">
            <el-button
              v-if="row.status === 'FAILED' || row.status === 'READY'"
              link
              :type="row.status === 'FAILED' ? 'warning' : 'primary'"
              :icon="Refresh"
              :loading="busy.has(row.id)"
              @click="reprocess(row)"
            >
              {{ row.status === 'FAILED' ? '重试' : '重新处理' }}
            </el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :disabled="row.status === 'DELETING'"
              :loading="busy.has(row.id)"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <ChunkDrawer v-model="chunkVisible" :doc="chunkDoc" />
  </div>
</template>

<style scoped>
.uploader {
  margin-bottom: 16px;
}

.uploader :deep(.el-upload-dragger) {
  padding: 24px 10px;
}

.upload-icon {
  font-size: 42px;
  color: var(--el-color-primary-light-3);
  margin-bottom: 6px;
}

.status-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.error-code {
  color: var(--el-color-danger);
  cursor: help;
}

.error-msg {
  font-size: 12px;
  color: var(--el-color-danger);
  margin-top: 2px;
  line-height: 1.5;
}
</style>
