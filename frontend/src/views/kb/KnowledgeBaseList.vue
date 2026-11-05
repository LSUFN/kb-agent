<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase, Visibility } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import { ACCESS_LABELS, VISIBILITY_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

const router = useRouter()

const items = ref<KnowledgeBase[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)

const dialogVisible = ref(false)
const editing = ref<KnowledgeBase | null>(null)
const form = reactive<{ name: string; description: string; visibility: Visibility }>({
  name: '',
  description: '',
  visibility: 'PRIVATE',
})
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await knowledgeApi.list(page.value, size)
    items.value = result.items
    total.value = result.total
  } catch (e) {
    notifyError(e, '加载知识库失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', description: '', visibility: 'PRIVATE' })
  dialogVisible.value = true
}

function openEdit(kb: KnowledgeBase) {
  editing.value = kb
  Object.assign(form, { name: kb.name, description: kb.description ?? '', visibility: kb.visibility })
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  saving.value = true
  try {
    const payload = { name: form.name.trim(), description: form.description.trim(), visibility: form.visibility }
    if (editing.value) {
      await knowledgeApi.update(editing.value.id, payload)
      ElMessage.success('已保存')
    } else {
      const kb = await knowledgeApi.create(payload)
      ElMessage.success('已创建，可以开始上传文档')
      dialogVisible.value = false
      await router.push({ name: 'kb-detail', params: { kbId: kb.id } })
      return
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    notifyError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(kb: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(`删除知识库“${kb.name}”？知识库中还有文档时不能删除。`, '删除知识库', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await knowledgeApi.remove(kb.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      notifyError(e, '删除失败')
    }
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-card">
      <div class="toolbar">
        <span class="muted">只显示你有权访问的知识库：自己创建的、被加为成员的，以及公开的知识库</span>
        <span class="spacer" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
      </div>
      <el-table v-loading="loading" :data="items" row-key="id" @row-click="(row: KnowledgeBase) => router.push(`/kb/${row.id}`)">
        <el-table-column prop="name" label="名称" min-width="180">
          <template #default="{ row }">
            <el-link type="primary" :underline="false">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <el-table-column label="可见性" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.visibility === 'PUBLIC' ? 'success' : 'info'">
              {{ VISIBILITY_LABELS[row.visibility as Visibility] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="我的权限" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ ACCESS_LABELS[row.myAccess as KnowledgeBase['myAccess']] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="router.push(`/kb/${row.id}`)">进入</el-button>
            <template v-if="row.myAccess === 'MANAGE'">
              <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click.stop="remove(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="total > size" class="pagination">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" @current-change="load" />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑知识库' : '新建知识库'" width="480px">
      <el-form label-width="72px" @submit.prevent="save">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" show-word-limit />
        </el-form-item>
        <el-form-item label="可见性">
          <el-radio-group v-model="form.visibility">
            <el-radio value="PRIVATE">私有（仅成员可见）</el-radio>
            <el-radio value="PUBLIC">公开（所有登录用户可读）</el-radio>
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
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
