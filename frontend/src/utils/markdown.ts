import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import MarkdownIt, { type Env, type StateCore, type Token } from 'markdown-it'

// 模型输出的渲染（§9）：markdown-it 关闭 HTML → DOMPurify 净化 → 代码高亮。
// 引用标记 [n] 渲染成可点击的角标；识别规则与后端 CitationParser 一致：
// 半角/全角方括号和【】内的一个或多个整数（逗号、顿号或空白分隔），代码块和行内代码里的不算。

for (const [name, language] of Object.entries({ bash, java, javascript, json, python, sql, typescript, xml, yaml })) {
  hljs.registerLanguage(name, language)
}
hljs.registerAliases(['sh', 'shell'], { languageName: 'bash' })
hljs.registerAliases(['js'], { languageName: 'javascript' })
hljs.registerAliases(['ts'], { languageName: 'typescript' })
hljs.registerAliases(['html', 'vue'], { languageName: 'xml' })
hljs.registerAliases(['yml'], { languageName: 'yaml' })

const MARK = /[[【［]\s*-?\d{1,6}(?:\s*[,，、\s]\s*-?\d{1,6})*\s*[\]】］]/g
const NUMBER = /-?\d+/g

/**
 * 渲染时的引用信息。
 * citations 为 undefined 表示还在流式输出、引用还没确定：所有编号都显示成待定角标；
 * 确定之后，只有在集合里的编号是可点击角标，其余按普通文本显示（后端的 invalidIndices）。
 */
export interface RenderEnv extends Env {
  citations?: ReadonlySet<number>
}

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }

function escapeHtml(text: string): string {
  return text.replace(/[&<>"]/g, (ch) => ESCAPES[ch]!)
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code: string, lang: string): string {
    const language = lang && hljs.getLanguage(lang) ? lang : null
    const body = language ? hljs.highlight(code, { language, ignoreIllegals: true }).value : escapeHtml(code)
    return `<pre class="hljs"><code>${body}</code></pre>`
  },
})

md.core.ruler.push('citation', (state: StateCore) => {
  for (const block of state.tokens) {
    if (block.type !== 'inline' || !block.children) {
      continue
    }
    const children: Token[] = []
    let linkDepth = 0
    for (const token of block.children) {
      if (token.type === 'link_open') {
        linkDepth++
      } else if (token.type === 'link_close') {
        linkDepth--
      }
      if (token.type !== 'text' || linkDepth > 0) {
        children.push(token)
        continue
      }
      let last = 0
      for (const match of token.content.matchAll(MARK)) {
        const start = match.index
        if (start > last) {
          children.push(textToken(state, token.content.slice(last, start)))
        }
        const citation = new state.Token('citation', '', 0)
        citation.content = match[0]
        citation.meta = { indices: [...match[0].matchAll(NUMBER)].map((n) => Number(n[0])) }
        children.push(citation)
        last = start + match[0].length
      }
      if (last === 0) {
        children.push(token)
      } else if (last < token.content.length) {
        children.push(textToken(state, token.content.slice(last)))
      }
    }
    block.children = children
  }
})

function textToken(state: StateCore, content: string): Token {
  const token = new state.Token('text', '', 0)
  token.content = content
  return token
}

md.renderer.rules.citation = (tokens, idx, _options, env) => {
  const token = tokens[idx]!
  const indices = (token.meta?.indices ?? []) as number[]
  const valid = (env as RenderEnv | undefined)?.citations
  if (!valid) {
    return indices.map((n) => `<sup class="cite pending">${n}</sup>`).join('')
  }
  if (!indices.some((n) => valid.has(n))) {
    return escapeHtml(token.content)
  }
  return indices
    .map((n) =>
      valid.has(n)
        ? `<sup class="cite" data-cite="${n}" title="查看引用 ${n}">${n}</sup>`
        : `<span class="cite-invalid">[${n}]</span>`,
    )
    .join('')
}

let hooksInstalled = false

function sanitize(html: string): string {
  if (!hooksInstalled) {
    // 链接一律在新窗口打开，且不带 opener
    DOMPurify.addHook('afterSanitizeAttributes', (node) => {
      if (node.tagName === 'A') {
        node.setAttribute('target', '_blank')
        node.setAttribute('rel', 'noopener noreferrer')
      }
    })
    hooksInstalled = true
  }
  return DOMPurify.sanitize(html, { ADD_ATTR: ['target'] })
}

export function renderMarkdown(source: string, env: RenderEnv = {}): string {
  return sanitize(md.render(source ?? '', env))
}
