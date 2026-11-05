<script setup lang="ts">
import { Download } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'

import { reportApi } from '@/api/reports'
import MarkdownView from '@/components/MarkdownView.vue'
import type { ReportDetail } from '@/types/api'
import { saveBlob } from '@/utils/download'
import { formatDateTime } from '@/utils/format'
import { REPORT_TYPE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const props = defineProps<{ reportId: number | null }>()
const visible = defineModel<boolean>({ required: true })

const detail = ref<ReportDetail | null>(null)
const loading = ref(false)
const downloading = ref(false)
const noCitations: ReadonlySet<number> = new Set()

const title = computed(() => detail.value?.report.title ?? (props.reportId ? `报告 #${props.reportId}` : '报告'))

watch(
  () => [visible.value, props.reportId] as const,
  async ([open, id]) => {
    if (!open || id === null) {
      return
    }
    if (detail.value?.report.id === id) {
      return
    }
    loading.value = true
    detail.value = null
    try {
      detail.value = await reportApi.get(id)
    } catch (e) {
      notifyError(e, '加载报告失败')
      visible.value = false
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

async function download() {
  if (props.reportId === null) {
    return
  }
  downloading.value = true
  try {
    const { blob, filename } = await reportApi.download(props.reportId)
    saveBlob(blob, filename)
  } catch (e) {
    notifyError(e, '下载失败')
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" :title="title" size="760px" destroy-on-close>
    <template #header>
      <div class="header">
        <div class="title ellipsis">{{ title }}</div>
        <el-button type="primary" :icon="Download" :loading="downloading" :disabled="!detail" @click="download">
          下载 Markdown
        </el-button>
      </div>
    </template>
    <div v-loading="loading" class="body">
      <template v-if="detail">
        <div class="meta">
          <el-tag size="small">{{ REPORT_TYPE_LABELS[detail.report.type] ?? detail.report.type }}</el-tag>
          <span class="muted">生成于 {{ formatDateTime(detail.report.createdAt) }}</span>
          <span class="muted">报告 #{{ detail.report.id }}</span>
        </div>
        <MarkdownView :source="detail.contentMd" :citations="noCitations" />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.title {
  flex: 1;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.body {
  min-height: 200px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
