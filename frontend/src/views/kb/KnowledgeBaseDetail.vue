<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import { ACCESS_LABELS, VISIBILITY_LABELS } from '@/utils/labels'
import { notifyError } from '@/utils/notify'

import DocumentPanel from './DocumentPanel.vue'
import MemberPanel from './MemberPanel.vue'
import SearchDebug from './SearchDebug.vue'

const route = useRoute()
const router = useRouter()

const kb = ref<KnowledgeBase | null>(null)
const loading = ref(false)
const tab = ref<'documents' | 'search' | 'members'>('documents')

watch(
  () => route.params.kbId,
  async (raw) => {
    const id = Number(raw)
    if (!Number.isInteger(id) || id <= 0) {
      return
    }
    loading.value = true
    kb.value = null
    tab.value = 'documents'
    try {
      kb.value = await knowledgeApi.get(id)
    } catch (e) {
      notifyError(e, '知识库不存在或无权访问')
      await router.replace({ name: 'kb-list' })
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <div v-loading="loading" class="page">
    <template v-if="kb">
      <div class="page-card header">
        <el-button :icon="ArrowLeft" circle text @click="router.push({ name: 'kb-list' })" />
        <div class="info">
          <div class="name">
            {{ kb.name }}
            <el-tag size="small" :type="kb.visibility === 'PUBLIC' ? 'success' : 'info'">
              {{ VISIBILITY_LABELS[kb.visibility] }}
            </el-tag>
            <el-tag size="small" effect="plain">我的权限：{{ ACCESS_LABELS[kb.myAccess] }}</el-tag>
          </div>
          <div class="muted">{{ kb.description || '暂无描述' }} · 创建于 {{ formatDateTime(kb.createdAt) }}</div>
        </div>
      </div>
      <div class="page-card">
        <el-tabs v-model="tab">
          <el-tab-pane label="文档" name="documents">
            <DocumentPanel v-if="tab === 'documents'" :kb="kb" />
          </el-tab-pane>
          <el-tab-pane label="检索调试" name="search">
            <SearchDebug :kb="kb" />
          </el-tab-pane>
          <el-tab-pane v-if="kb.myAccess === 'MANAGE'" label="成员" name="members">
            <MemberPanel v-if="tab === 'members'" :kb="kb" />
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.info {
  min-width: 0;
}

.name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 4px;
}
</style>
