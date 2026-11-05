<script setup lang="ts">
import {
  ChatDotRound,
  Collection,
  DataAnalysis,
  Document,
  List,
  Monitor,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useChatStreamStore } from '@/stores/chatStream'
import { useConversationStore } from '@/stores/conversation'
import { REGION_LABELS, ROLE_LABELS } from '@/utils/labels'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 当前高亮的菜单项：/chat/12 → /chat，/kb/3 → /kb */
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/chat')) {
    return '/chat'
  }
  if (path.startsWith('/kb')) {
    return '/kb'
  }
  return path
})

const breadcrumbs = computed(() => {
  const items: { title: string; to?: string }[] = []
  if (route.path.startsWith('/admin')) {
    items.push({ title: '系统管理' })
  }
  if (route.name === 'kb-detail') {
    items.push({ title: '知识库', to: '/kb' })
  }
  if (route.meta.title) {
    items.push({ title: route.meta.title })
  }
  return items
})

const scopeText = computed(() => {
  const user = auth.user
  if (!user) {
    return ''
  }
  if (user.role === 'ADMIN') {
    return '全部区域'
  }
  if (user.role === 'ANALYST') {
    return user.regionScope.length ? user.regionScope.map((r) => REGION_LABELS[r]).join('、') : '未分配区域'
  }
  return ''
})

async function logout() {
  useChatStreamStore().stop()
  useConversationStore().reset()
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside class="aside">
      <div class="logo">
        <img src="/favicon.svg" alt="" />
        <span>智能知识库</span>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能对话</span>
        </el-menu-item>
        <el-menu-item index="/kb">
          <el-icon><Collection /></el-icon>
          <span>知识库</span>
        </el-menu-item>
        <el-menu-item index="/reports">
          <el-icon><Document /></el-icon>
          <span>分析报告</span>
        </el-menu-item>
        <el-sub-menu v-if="auth.isAdmin" index="admin">
          <template #title>
            <el-icon><Monitor /></el-icon>
            <span>系统管理</span>
          </template>
          <el-menu-item index="/admin/users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/logs">
            <el-icon><List /></el-icon>
            <span>调用日志</span>
          </el-menu-item>
          <el-menu-item index="/admin/tasks">
            <el-icon><Collection /></el-icon>
            <span>任务管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/metrics">
            <el-icon><DataAnalysis /></el-icon>
            <span>统计概览</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container class="right">
      <el-header class="header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.title" :to="item.to">
            {{ item.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>
        <el-dropdown trigger="click">
          <div class="user">
            <el-avatar :size="28" class="avatar">{{ auth.user?.displayName?.slice(0, 1) }}</el-avatar>
            <span class="name">{{ auth.user?.displayName }}</span>
            <el-tag size="small" effect="plain">{{ auth.role ? ROLE_LABELS[auth.role] : '' }}</el-tag>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                <div class="user-detail">
                  <div>用户名：{{ auth.user?.username }}</div>
                  <div v-if="scopeText">数据范围：{{ scopeText }}</div>
                </div>
              </el-dropdown-item>
              <el-dropdown-item divided :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  width: var(--app-aside-width);
  background: #fff;
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
}

.logo {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  font-weight: 600;
  font-size: 16px;
  border-bottom: 1px solid var(--app-border);
}

.logo img {
  width: 26px;
  height: 26px;
}

.menu {
  border-right: none;
  flex: 1;
}

.right {
  min-width: 0;
}

.header {
  height: var(--app-header-height);
  background: #fff;
  border-bottom: 1px solid var(--app-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.avatar {
  background: var(--el-color-primary);
}

.name {
  font-size: 14px;
}

.user-detail {
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}

.main {
  padding: 0;
  overflow: auto;
  position: relative;
}
</style>
