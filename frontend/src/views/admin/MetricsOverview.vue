<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'

import { adminApi } from '@/api/admin'
import type { MetricsDay, MetricsOverview } from '@/types/api'
import { formatDate, formatMs, formatNumber, formatPercent } from '@/utils/format'
import { notifyError } from '@/utils/notify'

function daysAgo(n: number) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d
}

/** [开始, 结束]（都包含），默认最近 7 天 */
const range = ref<[Date, Date]>([daysAgo(6), new Date()])
const data = ref<MetricsOverview | null>(null)
const loading = ref(false)

const shortcuts = [
  { text: '最近 7 天', value: () => [daysAgo(6), new Date()] },
  { text: '最近 30 天', value: () => [daysAgo(29), new Date()] },
]

async function load() {
  const [start, end] = range.value
  const to = new Date(end)
  to.setDate(to.getDate() + 1)
  loading.value = true
  try {
    data.value = await adminApi.metrics(formatDate(start), formatDate(to))
  } catch (e) {
    notifyError(e, '加载统计失败')
  } finally {
    loading.value = false
  }
}

function successRate(d: MetricsDay | undefined) {
  return d && d.llmCalls > 0 ? (d.llmCalls - d.llmFailures) / d.llmCalls : null
}

const total = computed(() => data.value?.total)

const cards = computed(() => {
  const t = total.value
  return [
    { label: '模型调用次数', value: formatNumber(t?.llmCalls), hint: t ? `失败 ${t.llmFailures} 次` : '' },
    { label: '成功率', value: formatPercent(successRate(t)), hint: '' },
    { label: '延迟 P50 / P95', value: `${formatMs(t?.p50LatencyMs)} / ${formatMs(t?.p95LatencyMs)}`, hint: '整轮模型调用' },
    { label: '首字延迟 P50', value: formatMs(t?.p50FirstTokenMs), hint: '流式对话' },
    {
      label: 'Token 用量',
      value: formatNumber((t?.promptTokens ?? 0) + (t?.completionTokens ?? 0)),
      hint: t ? `输入 ${formatNumber(t.promptTokens)} · 输出 ${formatNumber(t.completionTokens)}` : '',
    },
    { label: '工具调用', value: formatNumber(t?.toolCalls), hint: t ? `被拒绝 ${t.toolDenied} 次` : '' },
  ]
})

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-card toolbar-card">
      <el-date-picker
        v-model="range"
        type="daterange"
        :shortcuts="shortcuts"
        :clearable="false"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        @change="load"
      />
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="muted">按 {{ data?.zone ?? '业务时区' }} 的自然日聚合 llm_call_log 和 tool_call_log</span>
    </div>

    <div class="cards">
      <div v-for="card in cards" :key="card.label" class="page-card card">
        <div class="muted">{{ card.label }}</div>
        <div class="value">{{ card.value }}</div>
        <div class="muted">{{ card.hint }}</div>
      </div>
    </div>

    <div class="page-card">
      <el-table :data="data?.days ?? []" row-key="day" size="small" empty-text="这段时间没有调用记录">
        <el-table-column prop="day" label="日期" width="110" />
        <el-table-column label="模型调用" width="90" align="right">
          <template #default="{ row }">{{ formatNumber(row.llmCalls) }}</template>
        </el-table-column>
        <el-table-column label="成功率" width="90" align="right">
          <template #default="{ row }">{{ formatPercent(successRate(row)) }}</template>
        </el-table-column>
        <el-table-column label="P50 延迟" width="100" align="right">
          <template #default="{ row }">{{ formatMs(row.p50LatencyMs) }}</template>
        </el-table-column>
        <el-table-column label="P95 延迟" width="100" align="right">
          <template #default="{ row }">{{ formatMs(row.p95LatencyMs) }}</template>
        </el-table-column>
        <el-table-column label="首字 P50" width="100" align="right">
          <template #default="{ row }">{{ formatMs(row.p50FirstTokenMs) }}</template>
        </el-table-column>
        <el-table-column label="输入 Token" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出 Token" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="工具调用" width="90" align="right">
          <template #default="{ row }">{{ formatNumber(row.toolCalls) }}</template>
        </el-table-column>
        <el-table-column label="被拒绝" min-width="80" align="right">
          <template #default="{ row }">{{ formatNumber(row.toolDenied) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.toolbar-card {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.value {
  font-size: 22px;
  font-weight: 600;
}
</style>
