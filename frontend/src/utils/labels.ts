import type {
  CitationMode,
  ConversationMode,
  DocumentStatus,
  KbAccess,
  KbPermission,
  MessageStatus,
  Region,
  ReportType,
  Role,
  TaskStatus,
  TaskType,
  ToolDecision,
  Visibility,
} from '@/types/api'

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export const ROLE_LABELS: Record<Role, string> = {
  USER: '普通员工',
  ANALYST: '业务分析员',
  ADMIN: '管理员',
}

export const REGION_LABELS: Record<Region, string> = {
  EAST: '华东',
  NORTH: '华北',
  SOUTH: '华南',
  WEST: '西部',
  CENTRAL: '华中',
}

export const REGIONS = Object.keys(REGION_LABELS) as Region[]

export const MODE_LABELS: Record<ConversationMode, string> = {
  KB_QA: '知识库问答',
  AGENT: '业务分析 Agent',
}

export const VISIBILITY_LABELS: Record<Visibility, string> = {
  PRIVATE: '私有',
  PUBLIC: '公开',
}

export const ACCESS_LABELS: Record<KbAccess, string> = {
  NONE: '无权限',
  READ: '只读',
  EDIT: '可编辑',
  MANAGE: '可管理',
}

export const PERMISSION_LABELS: Record<KbPermission, string> = {
  READER: '读者',
  EDITOR: '编辑者',
}

export const DOC_STATUS: Record<DocumentStatus, { label: string; type: TagType }> = {
  PENDING: { label: '排队中', type: 'info' },
  PROCESSING: { label: '处理中', type: 'primary' },
  READY: { label: '可用', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'warning' },
}

export const TASK_STATUS: Record<TaskStatus, { label: string; type: TagType }> = {
  PENDING: { label: '等待执行', type: 'info' },
  RUNNING: { label: '执行中', type: 'primary' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败（不可重试）', type: 'danger' },
  DEAD: { label: '重试耗尽', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
}

export const TASK_TYPE_LABELS: Record<TaskType, string> = {
  INGEST_DOCUMENT: '文档入库',
  DELETE_FILE: '删除文件',
}

export const MESSAGE_STATUS: Record<MessageStatus, { label: string; type: TagType }> = {
  STREAMING: { label: '生成中', type: 'primary' },
  OK: { label: '完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  INTERRUPTED: { label: '已中断', type: 'warning' },
}

export const CITATION_MODE_LABELS: Record<CitationMode, string> = {
  NORMAL: '引用来源',
  FALLBACK: '参考资料（回答中没有标注引用）',
  NONE: '没有检索到相关资料',
}

export const REPORT_TYPE_LABELS: Record<ReportType, string> = {
  ORDER_SUMMARY: '订单经营报告',
  ANOMALY_SUMMARY: '异常事件报告',
}

/** 工具名 → 中文名（工具名与后端 ToolPolicy 中的常量一致） */
export const TOOL_LABELS: Record<string, string> = {
  searchKnowledgeBase: '检索知识库',
  queryOrderStats: '订单统计',
  countAnomalies: '异常事件统计',
  generateReport: '生成报告',
}

export function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

export const DECISION_LABELS: Record<ToolDecision, string> = {
  ALLOWED: '已执行',
  DENIED: '无权限，已拒绝',
  INVALID: '参数不合法',
  BUDGET_EXCEEDED: '超出调用预算',
}

/** 工具调用的显示状态：进行中、成功、被拒绝（含参数不合法、超预算）、失败 */
export function toolStatus(step: { running?: boolean; decision?: ToolDecision | null; ok?: boolean }): {
  label: string
  type: TagType
} {
  if (step.running) {
    return { label: '进行中', type: 'primary' }
  }
  if (step.decision === 'ALLOWED') {
    return step.ok ? { label: '成功', type: 'success' } : { label: '执行失败', type: 'danger' }
  }
  return { label: DECISION_LABELS[step.decision ?? 'DENIED'], type: step.decision === 'DENIED' ? 'danger' : 'warning' }
}

/** 错误码的默认说明（与后端 ErrorCode 一致），用于历史消息只有错误码的情况 */
export const ERROR_MESSAGES: Record<string, string> = {
  UPSTREAM_ERROR: '模型服务异常，请稍后重试',
  UPSTREAM_TIMEOUT: '模型服务超时，请稍后重试',
  AGENT_ABORTED: '模型反复调用工具，已终止本轮回答，请换个问法再试',
  RATE_LIMITED: '请求过于频繁，请稍后再试',
  INTERNAL_ERROR: '服务器内部错误',
  INTERRUPTED: '回答已中断',
}
