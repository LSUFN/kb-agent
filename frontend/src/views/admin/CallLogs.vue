<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { adminApi } from '@/api/admin'
import type { LlmCallView, ToolCallView, ToolDecision } from '@/types/api'
import { formatMs, formatTime, prettyJson } from '@/utils/format'
import { DECISION_LABELS, TOOL_LABELS, toolLabel } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

import TraceDrawer from './TraceDrawer.vue'

const route = useRoute()

const tab = ref<'llm' | 'tool'>('llm')
const filter = reactive<{
  traceId: string
  userId: number | undefined
  range: [Date, Date] | null
  success?: 'true' | 'false'
  toolName: string
  decision: '' | ToolDecision
}>({ traceId: '', userId: undefined, range: null, success: undefined, toolName: '', decision: '' })

const llm = reactive({ items: [] as LlmCallView[], total: 0, page: 1, loading: false })
const tool = reactive({ items: [] as ToolCallView[], total: 0, page: 1, loading: false })
const size = 20

const traceId = ref<string | null>(null)
const traceVisible = ref(false)

function common() {
  return {
    traceId: filter.traceId.trim() || undefined,
    userId: filter.userId || undefined,
    from: filter.range?.[0]?.toISOString(),
    to: filter.range?.[1]?.toISOString(),
  }
}

async function loadLlm() {
  llm.loading = true
  try {
    const result = await adminApi.llmCalls(
      { ...common(), success: filter.success === undefined ? undefined : filter.success === 'true' },
      llm.page,
      size,
    )
    llm.items = result.items
    llm.total = result.total
  } catch (e) {
    notifyError(e, '加载模型调用日志失败')
  } finally {
    llm.loading = false
  }
}

async function loadTool() {
  tool.loading = true
  try {
    const result = await adminApi.toolCalls(
      { ...common(), toolName: filter.toolName || undefined, decision: filter.decision || undefined },
      tool.page,
      size,
    )
    tool.items = result.items
    tool.total = result.total
  } catch (e) {
    notifyError(e, '加载工具调用日志失败')
  } finally {
    tool.loading = false
  }
}

function search() {
  llm.page = 1
  tool.page = 1
  void loadLlm()
  void loadTool()
}

function reset() {
  Object.assign(filter, { traceId: '', userId: undefined, range: null, success: undefined, toolName: '', decision: '' })
  search()
}

function openTrace(id: string) {
  traceId.value = id
  traceVisible.value = true
}

function decisionType(decision: ToolDecision) {
  return decision === 'ALLOWED' ? 'success' : decision === 'DENIED' ? 'danger' : 'warning'
}

// 从对话页的 traceId 链接进来：按 traceId 过滤并打开串联视图
watch(
  () => route.query.traceId,
  (value) => {
    if (typeof value === 'string' && value) {
      filter.traceId = value
      openTrace(value)
    }
  },
  { immediate: true },
)

onMounted(search)
</script>

<template>
  <div class="page">
    <div class="page-card">
      <el-form inline class="filters" @submit.prevent="search">
        <el-form-item label="traceId">
          <el-input v-model="filter.traceId" clearable placeholder="一次请求的追踪号" class="w-trace" />
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input-number v-model="filter.userId" :min="1" :controls="false" class="w-user" />
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="filter.range"
            type="datetimerange"
            start-placeholder="开始"
            end-placeholder="结束"
            class="w-range"
          />
        </el-form-item>
        <el-form-item v-if="tab === 'llm'" label="结果">
          <el-select v-model="filter.success" clearable placeholder="全部" class="w-select">
            <el-option label="成功" value="true" />
            <el-option label="失败" value="false" />
          </el-select>
        </el-form-item>
        <template v-else>
          <el-form-item label="工具">
            <el-select v-model="filter.toolName" clearable placeholder="全部" class="w-select">
              <el-option v-for="(label, name) in TOOL_LABELS" :key="name" :label="label" :value="name" />
            </el-select>
          </el-form-item>
          <el-form-item label="决策">
            <el-select v-model="filter.decision" clearable placeholder="全部" class="w-select">
              <el-option v-for="(label, value) in DECISION_LABELS" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item>
          <el-button type="primary" :icon="Search" native-type="submit">查询</el-button>
          <el-button :icon="Refresh" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
      <p class="muted note">日志在写入时已经脱敏（手机号、身份证号、邮箱、银行卡号等），这里看到的就是存储的内容，没有任何接口能返回原文。</p>

      <el-tabs v-model="tab">
        <el-tab-pane :label="`模型调用（${llm.total}）`" name="llm">
          <el-table v-loading="llm.loading" :data="llm.items" row-key="id" size="small">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand">
                  <div><b>输入预览（已脱敏）：</b></div>
                  <pre>{{ row.inputPreview || '-' }}</pre>
                  <div v-if="row.errorMessage"><b>错误：</b>{{ row.errorCode }} {{ row.errorMessage }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="traceId" width="130">
              <template #default="{ row }">
                <el-link type="primary" class="mono" @click="openTrace(row.traceId)">{{ row.traceId.slice(0, 12) }}…</el-link>
              </template>
            </el-table-column>
            <el-table-column prop="userId" label="用户" width="64" />
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                {{ row.callType === 'CHAT' ? '对话' : '向量化' }}
                <span v-if="row.chatMode" class="muted">{{ row.chatMode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="model" label="模型" width="150" show-overflow-tooltip />
            <el-table-column label="Token（输入/输出）" width="140">
              <template #default="{ row }">
                {{ row.promptTokens ?? '-' }} / {{ row.completionTokens ?? '-' }}
                <span v-if="row.usageEstimated" class="muted">估算</span>
              </template>
            </el-table-column>
            <el-table-column label="首字" width="80" align="right">
              <template #default="{ row }">{{ formatMs(row.firstTokenMs) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="80" align="right">
              <template #default="{ row }">{{ formatMs(row.latencyMs) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="120">
              <template #default="{ row }">
                <el-tag v-if="row.success" size="small" type="success">成功</el-tag>
                <el-tooltip v-else :content="row.errorMessage ?? ''" placement="top">
                  <el-tag size="small" type="danger">{{ row.errorCode ?? '失败' }}</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination">
            <el-pagination
              v-model:current-page="llm.page"
              :page-size="size"
              :total="llm.total"
              layout="total, prev, pager, next"
              @current-change="loadLlm"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`工具调用（${tool.total}）`" name="tool">
          <el-table v-loading="tool.loading" :data="tool.items" row-key="id" size="small">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand">
                  <div><b>参数（已脱敏）：</b></div>
                  <pre>{{ prettyJson(row.args) }}</pre>
                  <div><b>实际生效的数据范围：</b>{{ row.effectiveScope.join('、') || '-' }}</div>
                  <div v-if="row.errorCode"><b>错误码：</b>{{ row.errorCode }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="traceId" width="130">
              <template #default="{ row }">
                <el-link type="primary" class="mono" @click="openTrace(row.traceId)">{{ row.traceId.slice(0, 12) }}…</el-link>
              </template>
            </el-table-column>
            <el-table-column label="用户" width="110">
              <template #default="{ row }">{{ row.userId }} <span class="muted">{{ row.userRole }}</span></template>
            </el-table-column>
            <el-table-column label="工具" width="120">
              <template #default="{ row }">{{ toolLabel(row.toolName) }}</template>
            </el-table-column>
            <el-table-column label="决策" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="decisionType(row.decision)">{{ DECISION_LABELS[row.decision as ToolDecision] ?? row.decision }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="执行" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.decision === 'ALLOWED'" size="small" :type="row.success ? 'success' : 'danger'" effect="plain">
                  {{ row.success ? '成功' : '失败' }}
                </el-tag>
                <span v-else class="muted">未执行</span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="80" align="right">
              <template #default="{ row }">{{ formatMs(row.latencyMs) }}</template>
            </el-table-column>
            <el-table-column prop="resultSummary" label="结果摘要" min-width="200" show-overflow-tooltip />
          </el-table>
          <div class="pagination">
            <el-pagination
              v-model:current-page="tool.page"
              :page-size="size"
              :total="tool.total"
              layout="total, prev, pager, next"
              @current-change="loadTool"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
    <TraceDrawer v-model="traceVisible" :trace-id="traceId" />
  </div>
</template>

<style scoped>
.filters {
  margin-bottom: -8px;
}

.filters :deep(.el-form-item) {
  margin-right: 16px;
}

.w-trace {
  width: 220px;
}

.w-user {
  width: 90px;
}

.w-range {
  width: 340px;
}

.w-select {
  width: 130px;
}

.note {
  margin: 0 0 4px;
}

.expand {
  padding: 4px 48px;
  font-size: 12px;
  line-height: 1.8;
}

.expand pre {
  margin: 4px 0 8px;
  padding: 8px 10px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
