import 'element-plus/dist/index.css'
import 'highlight.js/styles/github.css'
import './styles/global.css'

import ElementPlus, { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import { onSessionExpired } from '@/api/http'
import { router } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { useChatStreamStore } from '@/stores/chatStream'
import { useConversationStore } from '@/stores/conversation'
import { tokenStore } from '@/utils/tokenStore'

import App from './App.vue'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(ElementPlus, { locale: zhCn })

const auth = useAuthStore()

function goLogin() {
  useChatStreamStore().stop()
  useConversationStore().reset()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
}

// 刷新令牌失败、账号停用：清掉本地状态并回到登录页
onSessionExpired(() => {
  auth.clearLocal()
  ElMessage.warning({ message: '登录已失效，请重新登录', grouping: true })
  goLogin()
})

// 其他标签页登录、登出、刷新令牌
window.addEventListener('storage', (event) => {
  if (event.key !== tokenStore.key) {
    return
  }
  const wasLoggedIn = auth.loggedIn
  auth.syncFromStorage()
  if (wasLoggedIn && !auth.loggedIn) {
    goLogin()
  }
})

app.use(router)

// 页面加载时校准用户信息；令牌已失效时拦截器会刷新，刷新失败会跳到登录页
if (auth.loggedIn) {
  auth.reloadUser().catch(() => undefined)
}

app.mount('#app')
