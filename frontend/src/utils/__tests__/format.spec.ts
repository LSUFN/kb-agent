import { describe, expect, it } from 'vitest'

import { filenameFromDisposition } from '@/api/reports'

import { groupByTime, timeGroupOf } from '../format'

describe('会话按时间分组', () => {
  const now = new Date(2026, 10, 5, 15, 0, 0)
  const at = (days: number, hour = 10) => new Date(2026, 10, 5 - days, hour, 0, 0).toISOString()

  it('按自然日划分今天、昨天、近 7 天、近 30 天、更早', () => {
    expect(timeGroupOf(at(0, 0), now)).toBe('今天')
    expect(timeGroupOf(at(1, 23), now)).toBe('昨天')
    expect(timeGroupOf(at(6), now)).toBe('近 7 天')
    expect(timeGroupOf(at(7), now)).toBe('近 30 天')
    expect(timeGroupOf(at(30), now)).toBe('更早')
  })

  it('保持原有顺序，相邻同组合并', () => {
    const items = [at(0), at(0), at(1), at(10), at(40)]
    expect(groupByTime(items, (x) => x, now).map((g) => [g.label, g.items.length])).toEqual([
      ['今天', 2],
      ['昨天', 1],
      ['近 30 天', 1],
      ['更早', 1],
    ])
  })
})

describe('filenameFromDisposition', () => {
  it('优先使用 RFC 6266 的 filename*（UTF-8 中文文件名）', () => {
    const header = `attachment; filename="=?UTF-8?Q?x?="; filename*=UTF-8''${encodeURIComponent('订单经营报告（10 月）.md')}`
    expect(filenameFromDisposition(header)).toBe('订单经营报告（10 月）.md')
  })

  it('没有 filename* 时使用 filename，没有响应头时返回 null', () => {
    expect(filenameFromDisposition('attachment; filename="report.md"')).toBe('report.md')
    expect(filenameFromDisposition(undefined)).toBeNull()
  })
})
