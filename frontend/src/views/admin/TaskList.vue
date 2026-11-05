<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { adminApi } from '@/api/admin'
import type { AdminTaskView, TaskStatus, TaskType } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import { TASK_STATUS, TASK_TYPE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const filter = reactive<{ status?: TaskStatus; type?: TaskType }>({ status: 'DEAD', type: undefined })
const items = ref<AdminTaskView[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const retrying = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await adminApi.tasks({
      status: filter.status || undefined,
      type: filter.type || undefined,
      page: page.value,
      size,
    })
    items.value = result.items
    total.value = result.total
  } catch (e) {
    notifyError(e, '加载任务失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

async function retry(task: AdminTaskView) {
  try {
    await ElMessageBox.confirm(
      `让任务 #${task.id}（${TASK_TYPE_LABELS[task.type]}，文档 #${task.bizId}）重新排队？尝试次数清零，文档状态同步改为排队中。请先确认外部故障（例如模型服务）已经恢复。`,
      '手动重试',
      { type: 'warning', confirmButtonText: '重试', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  retrying.value = task.id
  try {
    await adminApi.retryTask(task.id)
    ElMessage.success(`任务 #${task.id} 已重新排队`)
    await load()
  } catch (e) {
    notifyError(e, '重试失败')
  } finally {
    retrying.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-card">
      <div class="toolbar">
        <el-select v-model="filter.status" clearable placeholder="全部状态" class="w-select" @change="search">
          <el-option v-for="(s, value) in TASK_STATUS" :key="value" :label="s.label" :value="value" />
        </el-select>
        <el-select v-model="filter.type" clearable placeholder="全部类型" class="w-select" @change="search">
          <el-option v-for="(label, value) in TASK_TYPE_LABELS" :key="value" :label="label" :value="value" />
        </el-select>
        <span class="muted">
          异步任务失败后按指数退避自动重试；重试次数耗尽（DEAD）的任务需要人工处理，可以在这里重新提交。
        </span>
        <span class="spacer" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="items" row-key="id" empty-text="没有符合条件的任务">
        <el-table-column prop="id" label="任务 ID" width="80" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ TASK_TYPE_LABELS[row.type as TaskType] }}</template>
        </el-table-column>
        <el-table-column label="业务对象" width="110">
          <template #default="{ row }">文档 #{{ row.bizId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="TASK_STATUS[row.status as TaskStatus].type">
              {{ TASK_STATUS[row.status as TaskStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="尝试次数" width="90">
          <template #default="{ row }">{{ row.attempt }} / {{ row.maxAttempts }}</template>
        </el-table-column>
        <el-table-column label="最后一次错误（已脱敏）" min-width="260" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.lastError || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DEAD'"
              link
              type="primary"
              :loading="retrying === row.id"
              @click="retry(row)"
            >
              重试
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.w-select {
  width: 150px;
}
</style>
