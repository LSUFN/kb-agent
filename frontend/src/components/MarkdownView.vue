<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  source: string
  /** 合法的引用编号；undefined 表示引用还没确定（流式输出中），角标显示为待定 */
  citations?: ReadonlySet<number>
  /** 当前高亮的引用编号 */
  activeCite?: number | null
}>()

const emit = defineEmits<{ cite: [index: number] }>()

const root = ref<HTMLElement>()
const html = computed(() => renderMarkdown(props.source, { citations: props.citations }))

function onClick(event: MouseEvent) {
  const target = (event.target as HTMLElement).closest<HTMLElement>('sup.cite[data-cite]')
  if (target) {
    emit('cite', Number(target.dataset.cite))
  }
}

// 高亮当前引用：内容是 v-html 渲染的，只能在 DOM 更新后直接操作 class
watch(
  [html, () => props.activeCite],
  () => {
    root.value?.querySelectorAll<HTMLElement>('sup.cite[data-cite]').forEach((el) => {
      el.classList.toggle('active', Number(el.dataset.cite) === props.activeCite)
    })
  },
  { flush: 'post', immediate: true },
)
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 已经过 markdown-it(html:false) + DOMPurify -->
  <div ref="root" class="markdown-body" @click="onClick" v-html="html" />
</template>
