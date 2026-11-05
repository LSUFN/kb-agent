<script setup lang="ts">
import { Download, Refresh, View } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'

import { reportApi } from '@/api/reports'
import ReportPreviewDrawer from '@/components/ReportPreviewDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import type { Region, ReportView } from '@/types/api'
import { saveBlob } from '@/utils/download'
import { formatDateTime } from '@/utils/format'
import { REGION_LABELS, REPORT_TYPE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const auth = useAuthStore()
const items = ref<ReportView[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const previewId = ref<number | null>(null)
const previewVisible = ref(false)
const downloading = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await reportApi.list(page.value, size)
    items.value = result.items
    total.value = result.total
  } catch (e) {
    notifyError(e, '加载报告失败')
  } finally {
    loading.value = false
  }
}

/** params 是生成报告时的参数（JSON）：时间范围和区域 */
function describeParams(raw: string): string {
  try {
    const p = JSON.parse(raw) as { from?: string; to?: string; regions?: Region[] }
    const parts: string[] = []
    if (p.from && p.to) {
      parts.push(`${p.from} ~ ${p.to}`)
    }
    if (p.regions?.length) {
      parts.push(p.regions.map((r) => REGION_LABELS[r] ?? r).join('、'))
    }
    return parts.join(' · ') || '-'
  } catch {
    return raw
  }
}

function preview(report: ReportView) {
  previewId.value = report.id
  previewVisible.value = true
}

async function download(report: ReportView) {
  downloading.value = report.id
  try {
    const { blob, filename } = await reportApi.download(report.id)
    saveBlob(blob, filename)
  } catch (e) {
    notifyError(e, '下载失败')
  } finally {
    downloading.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-card">
      <div class="toolbar">
        <span class="muted">
          报告只能在“业务分析 Agent”会话中让模型调用报告工具生成，数字全部来自数据库统计。
          <template v-if="auth.isAdmin">管理员可以看到所有人的报告。</template>
        </span>
        <span class="spacer" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="items" row-key="id" empty-text="还没有报告">
        <el-table-column prop="id" label="编号" width="70" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'ORDER_SUMMARY' ? 'primary' : 'warning'">
              {{ REPORT_TYPE_LABELS[row.type as ReportView['type']] ?? row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="范围" min-width="200">
          <template #default="{ row }">{{ describeParams(row.params) }}</template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin" prop="userId" label="生成人 ID" width="100" />
        <el-table-column label="生成时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click="preview(row)">预览</el-button>
            <el-button link type="primary" :icon="Download" :loading="downloading === row.id" @click="download(row)">
              下载
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="total > size" class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
    <ReportPreviewDrawer v-model="previewVisible" :report-id="previewId" />
  </div>
</template>
