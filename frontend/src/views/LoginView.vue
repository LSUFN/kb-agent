<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { toApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/chat'
    await router.replace(redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/chat')
  } catch (e) {
    const apiError = toApiError(e)
    error.value =
      apiError.code === 'RATE_LIMITED' && apiError.retryAfter
        ? `登录尝试过于频繁，请 ${apiError.retryAfter} 秒后再试`
        : apiError.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <img src="/favicon.svg" alt="" />
        <div>
          <h1>企业智能知识库</h1>
          <p>知识库问答 · 业务分析 Agent</p>
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="error" />
        <el-button type="primary" :loading="loading" class="submit" native-type="submit">登录</el-button>
      </el-form>
      <p class="hint">演示账号由后端启动时创建（admin / analyst / user），密码见 .env 中的 DEMO_USER_PASSWORD</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef3ff 0%, #f7f9fc 55%, #eaf6f1 100%);
  padding: 16px;
}

.login-card {
  width: 380px;
  max-width: 100%;
  background: #fff;
  border-radius: 12px;
  padding: 32px 32px 20px;
  box-shadow: 0 12px 32px rgba(31, 45, 61, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
}

.brand img {
  width: 44px;
  height: 44px;
}

.brand h1 {
  margin: 0;
  font-size: 20px;
}

.brand p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.error {
  margin-bottom: 16px;
}

.submit {
  width: 100%;
}

.hint {
  margin: 18px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
