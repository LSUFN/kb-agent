<script setup lang="ts">
import { CircleCheckFilled, CircleCloseFilled, Loading, WarningFilled } from '@element-plus/icons-vue'
import { ref } from 'vue'

import type { ToolStepVM } from '@/types/chat'
import { formatMs, prettyJson } from '@/utils/format'
import { toolLabel, toolStatus } from '@/utils/labels'

defineProps<{ steps: ToolStepVM[] }>()

const expanded = ref<string[]>([])

function iconOf(step: ToolStepVM) {
  if (step.running) {
    return Loading
  }
  if (step.decision === 'ALLOWED') {
    return step.ok ? CircleCheckFilled : CircleCloseFilled
  }
  return step.decision === 'DENIED' ? CircleCloseFilled : WarningFilled
}
</script>

<template>
  <div class="tool-timeline">
    <div class="tl-title">工具调用 · {{ steps.length }} 次</div>
    <el-collapse v-model="expanded" class="tl-collapse">
      <el-collapse-item v-for="step in steps" :key="step.callId" :name="step.callId">
        <template #title>
          <div class="tl-row">
            <el-icon class="tl-icon" :class="[toolStatus(step).type, { spin: step.running }]">
              <component :is="iconOf(step)" />
            </el-icon>
            <span class="tl-name">{{ toolLabel(step.tool) }}</span>
            <el-tag size="small" :type="toolStatus(step).type" effect="light">{{ toolStatus(step).label }}</el-tag>
            <span v-if="!step.running" class="tl-latency">{{ formatMs(step.latencyMs) }}</span>
            <span class="tl-summary ellipsis" :title="step.summary ?? ''">{{ step.summary }}</span>
          </div>
        </template>
        <div class="tl-detail">
          <div class="tl-label">
            工具 <code>{{ step.tool }}</code> · 调用编号 {{ step.callId }}
          </div>
          <div class="tl-label">参数（已脱敏）</div>
          <pre class="tl-args">{{ prettyJson(step.args) || '{}' }}</pre>
          <template v-if="step.summary">
            <div class="tl-label">结果摘要</div>
            <div class="tl-text">{{ step.summary }}</div>
          </template>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.tool-timeline {
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  padding: 6px 12px 2px;
}

.tl-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 2px 0 4px;
}

.tl-collapse {
  border: none;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
  --el-collapse-header-height: 36px;
  --el-collapse-border-color: var(--el-border-color-lighter);
}

.tl-collapse :deep(.el-collapse-item:last-child .el-collapse-item__header),
.tl-collapse :deep(.el-collapse-item:last-child .el-collapse-item__wrap) {
  border-bottom: none;
}

.tl-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  padding-right: 8px;
}

.tl-icon {
  font-size: 16px;
}

.tl-icon.success {
  color: var(--el-color-success);
}

.tl-icon.danger {
  color: var(--el-color-danger);
}

.tl-icon.warning {
  color: var(--el-color-warning);
}

.tl-icon.primary {
  color: var(--el-color-primary);
}

.tl-icon.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.tl-name {
  font-weight: 500;
  font-size: 13px;
  white-space: nowrap;
}

.tl-latency {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.tl-summary {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.tl-detail {
  padding: 0 0 4px 24px;
}

.tl-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 6px 0 4px;
}

.tl-args {
  margin: 0;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  overflow-x: auto;
}

.tl-text {
  font-size: 13px;
}
</style>
