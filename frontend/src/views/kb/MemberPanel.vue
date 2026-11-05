<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { adminApi } from '@/api/admin'
import { knowledgeApi } from '@/api/knowledge'
import { useAuthStore } from '@/stores/auth'
import type { KbMember, KbPermission, KnowledgeBase, UserView } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import { PERMISSION_LABELS, ROLE_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const props = defineProps<{ kb: KnowledgeBase }>()

const auth = useAuthStore()
const members = ref<KbMember[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const form = reactive<{ userId: number | null; permission: KbPermission }>({ userId: null, permission: 'READER' })
const userOptions = ref<UserView[]>([])
const searching = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    members.value = await knowledgeApi.members(props.kb.id)
  } catch (e) {
    notifyError(e, '加载成员失败')
  } finally {
    loading.value = false
  }
}

/** 只有管理员能查询用户列表；知识库所有者需要输入对方的用户 ID */
async function searchUsers(keyword: string) {
  if (!auth.isAdmin) {
    return
  }
  searching.value = true
  try {
    userOptions.value = (await adminApi.users(keyword || undefined, 1, 20)).items
  } catch (e) {
    notifyError(e, '查询用户失败')
  } finally {
    searching.value = false
  }
}

function openAdd() {
  form.userId = null
  form.permission = 'READER'
  dialogVisible.value = true
  void searchUsers('')
}

async function save() {
  if (!form.userId) {
    ElMessage.warning('请选择用户')
    return
  }
  saving.value = true
  try {
    await knowledgeApi.putMember(props.kb.id, form.userId, form.permission)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e) {
    notifyError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function changePermission(member: KbMember, permission: KbPermission) {
  try {
    await knowledgeApi.putMember(props.kb.id, member.userId, permission)
    member.permission = permission
    ElMessage.success('权限已更新')
  } catch (e) {
    notifyError(e, '更新失败')
  }
}

async function remove(member: KbMember) {
  try {
    await ElMessageBox.confirm(`移除成员“${member.displayName}”？`, '移除成员', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消',
    })
    await knowledgeApi.removeMember(props.kb.id, member.userId)
    await load()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      notifyError(e, '移除失败')
    }
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="muted">读者可以阅读和检索；编辑者还可以上传、删除和重新处理文档。所有者和管理员始终可以管理。</span>
      <span class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openAdd">添加成员</el-button>
    </div>
    <el-table v-loading="loading" :data="members" row-key="userId" empty-text="还没有成员">
      <el-table-column prop="displayName" label="姓名" min-width="140" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column label="权限" width="160">
        <template #default="{ row }">
          <el-select
            :model-value="row.permission"
            size="small"
            @change="(p: KbPermission) => changePermission(row, p)"
          >
            <el-option v-for="(label, value) in PERMISSION_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="加入时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" @click="remove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="添加成员" width="440px">
      <el-form label-width="64px" @submit.prevent="save">
        <el-form-item label="用户" required>
          <el-select
            v-if="auth.isAdmin"
            v-model="form.userId"
            filterable
            remote
            :remote-method="searchUsers"
            :loading="searching"
            placeholder="输入用户名或姓名搜索"
            class="full"
          >
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.displayName}（${u.username}）`"
              :value="u.id"
            >
              <span>{{ u.displayName }}（{{ u.username }}）</span>
              <span class="muted option-role">{{ ROLE_LABELS[u.role] }}</span>
            </el-option>
          </el-select>
          <template v-else>
            <el-input-number v-model="form.userId" :min="1" :controls="false" placeholder="对方的用户 ID" class="full" />
            <div class="muted">只有管理员能浏览用户列表，请向对方或管理员询问用户 ID</div>
          </template>
        </el-form-item>
        <el-form-item label="权限">
          <el-radio-group v-model="form.permission">
            <el-radio v-for="(label, value) in PERMISSION_LABELS" :key="value" :value="value">{{ label }}</el-radio>
          </el-radio-group>
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
.full {
  width: 100%;
}

.option-role {
  float: right;
  margin-left: 12px;
}
</style>
