import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import type { Role } from '@/types/api'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 允许访问的角色；不写表示登录即可 */
    roles?: Role[]
    /** 不需要登录 */
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/chat',
    children: [
      {
        path: 'chat/:id?',
        name: 'chat',
        component: () => import('@/views/chat/ChatView.vue'),
        meta: { title: '智能对话' },
      },
      {
        path: 'kb',
        name: 'kb-list',
        component: () => import('@/views/kb/KnowledgeBaseList.vue'),
        meta: { title: '知识库' },
      },
      {
        path: 'kb/:kbId',
        name: 'kb-detail',
        component: () => import('@/views/kb/KnowledgeBaseDetail.vue'),
        meta: { title: '知识库详情' },
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/report/ReportList.vue'),
        meta: { title: '分析报告' },
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManagement.vue'),
        meta: { title: '用户管理', roles: ['ADMIN'] },
      },
      {
        path: 'admin/logs',
        name: 'admin-logs',
        component: () => import('@/views/admin/CallLogs.vue'),
        meta: { title: '调用日志', roles: ['ADMIN'] },
      },
      {
        path: 'admin/tasks',
        name: 'admin-tasks',
        component: () => import('@/views/admin/TaskList.vue'),
        meta: { title: '任务管理', roles: ['ADMIN'] },
      },
      {
        path: 'admin/metrics',
        name: 'admin-metrics',
        component: () => import('@/views/admin/MetricsOverview.vue'),
        meta: { title: '统计概览', roles: ['ADMIN'] },
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/ErrorPage.vue'),
        props: { code: 403 },
        meta: { title: '无权访问' },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/ErrorPage.vue'),
        props: { code: 404 },
        meta: { title: '页面不存在' },
      },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 菜单和路由守卫按角色控制，只用于改善体验；真正的权限由后端判断（§9）
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return to.name === 'login' && auth.loggedIn ? { path: '/chat' } : true
  }
  if (!auth.loggedIn) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.meta.roles && (!auth.role || !to.meta.roles.includes(auth.role))) {
    return { name: 'forbidden' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 企业智能知识库` : '企业智能知识库'
})
