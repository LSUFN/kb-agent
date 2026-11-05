<script setup lang="ts">
import { ChatLineSquare, Cpu, Delete, EditPen, MoreFilled, Plus, Search } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { useChatStreamStore } from '@/stores/chatStream'
import { useConversationStore } from '@/stores/conversation'
import type { Conversation } from '@/types/api'
import { groupByTime } from '@/utils/format'
import { MODE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const emit = defineEmits<{ open: [id: number]; create: [] }>()

const store = useConversationStore()
const stream = useChatStreamStore()

const groups = computed(() => groupByTime(store.conversations, (c) => c.updatedAt))

const keyword = ref(store.keyword)
let timer: ReturnType<typeof setTimeout> | undefined
watch(keyword, (value) => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    store.keyword = value
    store.loadList().catch((e) => notifyError(e, '加载会话失败'))
  }, 300)
})

async function rename(c: Conversation) {
  try {
    const { value } = await ElMessageBox.prompt('会话标题', '重命名', {
      inputValue: c.title,
      inputPattern: /\S/,
      inputErrorMessage: '标题不能为空',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    await store.rename(c.id, value.trim().slice(0, 128))
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      notifyError(e, '重命名失败')
    }
  }
}

async function remove(c: Conversation) {
  try {
    await ElMessageBox.confirm(`删除会话“${c.title}”？删除后不可恢复。`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await store.remove(c.id)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      notifyError(e, '删除失败')
    }
  }
}

function onCommand(command: 'rename' | 'delete', c: Conversation) {
  if (command === 'rename') {
    void rename(c)
  } else {
    void remove(c)
  }
}
</script>

<template>
  <div class="sidebar">
    <div class="top">
      <el-button type="primary" :icon="Plus" class="new" @click="emit('create')">新建会话</el-button>
      <el-input v-model="keyword" :prefix-icon="Search" placeholder="搜索会话" clearable />
    </div>
    <div v-loading="store.loadingList && !store.conversations.length" class="list">
      <template v-for="group in groups" :key="group.label">
        <div class="group-label">{{ group.label }}</div>
        <div
          v-for="c in group.items"
          :key="c.id"
          class="item"
          :class="{ active: c.id === store.currentId }"
          @click="emit('open', c.id)"
        >
          <el-icon class="mode-icon" :title="MODE_LABELS[c.mode]">
            <Cpu v-if="c.mode === 'AGENT'" />
            <ChatLineSquare v-else />
          </el-icon>
          <span class="title ellipsis" :title="c.title">{{ c.title }}</span>
          <span v-if="stream.active?.conversationId === c.id" class="live" title="正在生成回答" />
          <el-dropdown trigger="click" @command="(cmd: 'rename' | 'delete') => onCommand(cmd, c)" @click.stop>
            <el-icon class="more" @click.stop><MoreFilled /></el-icon>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename" :icon="EditPen">重命名</el-dropdown-item>
                <el-dropdown-item
                  command="delete"
                  :icon="Delete"
                  :disabled="stream.active?.conversationId === c.id"
                >
                  删除
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
      <el-empty v-if="!store.loadingList && !store.conversations.length" :image-size="60" description="暂无会话" />
      <div v-if="store.hasMore" class="more-row">
        <el-button text size="small" :loading="store.loadingList" @click="store.loadList(false)">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.top {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-bottom: 1px solid var(--app-border);
}

.new {
  width: 100%;
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 12px;
}

.group-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 12px 8px 4px;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.item:hover {
  background: var(--el-fill-color-light);
}

.item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.mode-icon {
  flex: none;
}

.title {
  flex: 1;
  min-width: 0;
}

.live {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 4px;
  background: var(--el-color-success);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.3;
  }
}

.more {
  visibility: hidden;
  color: var(--el-text-color-secondary);
  padding: 4px;
}

.item:hover .more,
.item.active .more {
  visibility: visible;
}

.more-row {
  text-align: center;
  padding-top: 8px;
}
</style>
