import { describe, expect, it } from 'vitest'

import { renderMarkdown } from '../markdown'

const cited = (...n: number[]) => ({ citations: new Set(n) })

function parse(html: string) {
  const div = document.createElement('div')
  div.innerHTML = html
  return div
}

describe('renderMarkdown 安全性', () => {
  it('不渲染模型输出里的 HTML，也不保留事件属性', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)"><script>alert(2)</script>**粗体**', cited())
    const dom = parse(html)
    expect(dom.querySelector('img')).toBeNull()
    expect(dom.querySelector('script')).toBeNull()
    expect(dom.querySelector('[onerror]')).toBeNull()
    // 原样显示为文本
    expect(dom.textContent).toContain('<img src=x onerror="alert(1)">')
    expect(html).toContain('&lt;script&gt;')
    expect(dom.querySelector('strong')?.textContent).toBe('粗体')
  })

  it('过滤 javascript: 链接，普通链接在新窗口打开且不带 opener', () => {
    const dom = parse(renderMarkdown('[点我](javascript:alert(1)) 和 https://example.com', cited()))
    const links = [...dom.querySelectorAll('a')]
    expect(links.every((a) => !a.getAttribute('href')?.startsWith('javascript:'))).toBe(true)
    const external = links.find((a) => a.getAttribute('href') === 'https://example.com')!
    expect(external.getAttribute('target')).toBe('_blank')
    expect(external.getAttribute('rel')).toBe('noopener noreferrer')
  })
})

describe('renderMarkdown 引用角标', () => {
  it('合法编号渲染成可点击角标，不合法的按普通文本显示', () => {
    const dom = parse(renderMarkdown('标准是 600 元[1]，另见[9]。', cited(1, 2)))
    const sups = dom.querySelectorAll('sup.cite[data-cite]')
    expect([...sups].map((s) => s.getAttribute('data-cite'))).toEqual(['1'])
    expect(dom.textContent).toContain('[9]')
  })

  it('识别与后端 CitationParser 相同的写法：连续、逗号分隔、全角和【】', () => {
    const dom = parse(renderMarkdown('A[1][2] B[1,2] C［2］ D【1】', cited(1, 2)))
    const indices = [...dom.querySelectorAll('sup.cite')].map((s) => s.getAttribute('data-cite'))
    expect(indices).toEqual(['1', '2', '1', '2', '2', '1'])
  })

  it('混合合法和不合法编号时只把合法的渲染成角标', () => {
    const dom = parse(renderMarkdown('见[1,9]', cited(1)))
    expect([...dom.querySelectorAll('sup.cite')].map((s) => s.textContent)).toEqual(['1'])
    expect(dom.querySelector('.cite-invalid')?.textContent).toBe('[9]')
  })

  it('代码块和行内代码里的 [n] 不是引用', () => {
    const md = '数组 `arr[1]`\n\n```java\nint x = a[2];\n```\n\n正文[1]'
    const dom = parse(renderMarkdown(md, cited(1, 2)))
    expect(dom.querySelectorAll('sup.cite')).toHaveLength(1)
    expect(dom.querySelector('code')?.textContent).toBe('arr[1]')
    expect(dom.querySelector('pre')?.textContent).toContain('a[2]')
  })

  it('流式输出中引用还没确定时显示为待定角标，不能点击', () => {
    const dom = parse(renderMarkdown('标准是 600 元[1]', {}))
    const sup = dom.querySelector('sup.cite')!
    expect(sup.classList.contains('pending')).toBe(true)
    expect(sup.hasAttribute('data-cite')).toBe(false)
  })

  it('代码块按语言高亮', () => {
    const dom = parse(renderMarkdown('```sql\nSELECT 1;\n```', cited()))
    expect(dom.querySelector('pre.hljs .hljs-keyword')?.textContent).toBe('SELECT')
  })
})
