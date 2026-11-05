const pad = (n: number) => String(n).padStart(2, '0')

/** 2026-11-05 14:03 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '-'
  }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) {
    return iso
  }
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 带秒，用于日志 */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) {
    return '-'
  }
  const d = new Date(iso)
  return `${formatDateTime(iso)}:${pad(d.getSeconds())}`
}

/** yyyy-MM-dd（本地时区） */
export function formatDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) {
    return '-'
  }
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}

export function formatPercent(ratio: number | null | undefined, digits = 1): string {
  if (ratio == null || Number.isNaN(ratio)) {
    return '-'
  }
  return `${(ratio * 100).toFixed(digits)}%`
}

export function formatNumber(n: number | null | undefined): string {
  return n == null ? '-' : n.toLocaleString('zh-CN')
}

/** 尽量把 JSON 字符串格式化显示；不是 JSON 时原样返回 */
export function prettyJson(value: unknown): string {
  if (value == null) {
    return ''
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  return JSON.stringify(value, null, 2)
}

export type TimeGroup = '今天' | '昨天' | '近 7 天' | '近 30 天' | '更早'

/** 会话列表按更新时间分组 */
export function timeGroupOf(iso: string, now: Date = new Date()): TimeGroup {
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const t = new Date(iso).getTime()
  const day = 24 * 60 * 60 * 1000
  if (t >= startOfToday) {
    return '今天'
  }
  if (t >= startOfToday - day) {
    return '昨天'
  }
  if (t >= startOfToday - 6 * day) {
    return '近 7 天'
  }
  if (t >= startOfToday - 29 * day) {
    return '近 30 天'
  }
  return '更早'
}

/** 把已按时间倒序的列表按 timeGroupOf 分组，保持原有顺序 */
export function groupByTime<T>(items: T[], timeOf: (item: T) => string, now: Date = new Date()) {
  const groups: { label: TimeGroup; items: T[] }[] = []
  for (const item of items) {
    const label = timeGroupOf(timeOf(item), now)
    const last = groups[groups.length - 1]
    if (last && last.label === label) {
      last.items.push(item)
    } else {
      groups.push({ label, items: [item] })
    }
  }
  return groups
}
