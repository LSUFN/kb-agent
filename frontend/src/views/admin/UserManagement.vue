<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { adminApi } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'
import type { Region, Role, UserView } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import { REGION_LABELS, REGIONS, ROLE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const auth = useAuthStore()
const items = ref<UserView[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const loading = ref(false)

const dialogVisible = ref(false)
const editing = ref<UserView | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<{
  username: string
  password: string
  displayName: string
  role: Role
  regionScope: Region[]
}>({ username: '', password: '', displayName: '', role: 'USER', regionScope: [] })
const saving = ref(false)
const toggling = ref<number | null>(null)

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: '3-32 位字母、数字、下划线、点或连字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度为 8-64 位', trigger: 'blur' },
  ],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const result = await adminApi.users(keyword.value.trim() || undefined, page.value, size)
    items.value = result.items
    total.value = result.total
  } catch (e) {
    notifyError(e, '加载用户失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { username: '', password: '', displayName: '', role: 'USER', regionScope: [] })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(user: UserView) {
  editing.value = user
  Object.assign(form, {
    username: user.username,
    password: '',
    displayName: user.displayName,
    role: user.role,
    regionScope: [...user.regionScope],
  })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function save() {
  if (!editing.value && !(await formRef.value?.validate().catch(() => false))) {
    return
  }
  saving.value = true
  try {
    // 区域范围只对 ANALYST 有意义，其他角色传空数组
    const regionScope = form.role === 'ANALYST' ? form.regionScope : []
    if (editing.value) {
      await adminApi.updateUser(editing.value.id, {
        displayName: form.displayName.trim(),
        role: form.role,
        regionScope,
      })
      ElMessage.success('已保存，权限变化立即生效')
    } else {
      await adminApi.createUser({
        username: form.username.trim(),
        password: form.password,
        displayName: form.displayName.trim(),
        role: form.role,
        regionScope,
      })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    notifyError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(user: UserView, enabled: boolean) {
  toggling.value = user.id
  try {
    const updated = await adminApi.updateUser(user.id, { enabled })
    Object.assign(user, updated)
    ElMessage.success(enabled ? '已启用' : '已停用，该用户的登录状态立即失效')
  } catch (e) {
    notifyError(e, '操作失败')
  } finally {
    toggling.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="用户名或显示名"
          clearable
          class="search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button @click="search">查询</el-button>
        <span class="spacer" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
      <el-table v-loading="loading" :data="items" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'ADMIN' ? 'danger' : row.role === 'ANALYST' ? 'warning' : 'info'">
              {{ ROLE_LABELS[row.role as Role] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" min-width="160">
          <template #default="{ row }">
            <template v-if="row.role === 'ADMIN'">全部区域</template>
            <template v-else-if="row.role === 'ANALYST'">
              <span v-if="!row.regionScope.length" class="muted">未分配（查不到任何业务数据）</span>
              <el-tag v-for="r in row.regionScope" :key="r" size="small" effect="plain" class="region">
                {{ REGION_LABELS[r as Region] }}
              </el-tag>
            </template>
            <span v-else class="muted">不能使用业务分析工具</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="toggling === row.id"
              :disabled="row.id === auth.user?.id"
              @change="(v: string | number | boolean) => toggleEnabled(row, Boolean(v))"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? `编辑用户：${editing.username}` : '新建用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="editing ? {} : rules" label-width="84px" @submit.prevent="save">
        <el-form-item v-if="!editing" label="用户名" prop="username">
          <el-input v-model="form.username" maxlength="32" />
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password maxlength="64" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="form.role" :disabled="editing?.id === auth.user?.id">
            <el-radio v-for="(label, value) in ROLE_LABELS" :key="value" :value="value">{{ label }}</el-radio>
          </el-radio-group>
          <div v-if="editing?.id === auth.user?.id" class="muted">不能修改自己的角色</div>
        </el-form-item>
        <el-form-item v-if="form.role === 'ANALYST'" label="数据范围">
          <el-checkbox-group v-model="form.regionScope">
            <el-checkbox v-for="r in REGIONS" :key="r" :value="r">{{ REGION_LABELS[r] }}</el-checkbox>
          </el-checkbox-group>
          <div class="muted">分析员只能查询这些区域的订单和异常事件，越权请求会被工具层拒绝并记入审计</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.search {
  width: 240px;
}

.region {
  margin-right: 4px;
}
</style>
