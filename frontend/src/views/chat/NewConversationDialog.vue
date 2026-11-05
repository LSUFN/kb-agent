<script setup lang="ts">
import { reactive, ref, watch } from 'vue'

import { knowledgeApi } from '@/api/knowledge'
import { useAuthStore } from '@/stores/auth'
import type { ConversationMode, KnowledgeBase } from '@/types/api'
import { notifyError } from '@/utils/notify'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ initialMode?: ConversationMode }>()
const emit = defineEmits<{ create: [mode: ConversationMode, kbId: number | null, title: string] }>()

const auth = useAuthStore()
const form = reactive<{ mode: ConversationMode; kbId: number | null; title: string }>({
  mode: 'KB_QA',
  kbId: null,
  title: '',
})
const knowledgeBases = ref<KnowledgeBase[]>([])
const loadingKb = ref(false)

watch(visible, async (open) => {
  if (!open) {
    return
  }
  form.mode = props.initialMode ?? 'KB_QA'
  form.kbId = null
  form.title = ''
  loadingKb.value = true
  try {
    knowledgeBases.value = (await knowledgeApi.list()).items
  } catch (e) {
    notifyError(e, '加载知识库失败')
  } finally {
    loadingKb.value = false
  }
})

function submit() {
  emit('create', form.mode, form.kbId, form.title.trim())
}
</script>

<template>
  <el-dialog v-model="visible" title="新建会话" width="520px">
    <el-form label-width="84px" @submit.prevent="submit">
      <el-form-item label="模式">
        <el-radio-group v-model="form.mode" class="modes">
          <el-radio value="KB_QA" border class="mode">
            <div class="mode-title">知识库问答</div>
            <div class="mode-desc">先检索知识库再回答，回答带引用来源；没有相关资料时直接拒答</div>
          </el-radio>
          <el-radio value="AGENT" border class="mode">
            <div class="mode-title">业务分析 Agent</div>
            <div class="mode-desc">
              模型按需调用工具：检索知识库、订单统计、异常事件统计、生成报告。
              <template v-if="!auth.canAnalyze">当前账号只能使用知识库检索工具。</template>
            </div>
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="知识库">
        <el-select v-model="form.kbId" :loading="loadingKb" clearable placeholder="全部有权限的知识库" class="full">
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <div class="muted">不选时在你有权限访问的全部知识库中检索</div>
      </el-form-item>
      <el-form-item label="标题">
        <el-input v-model="form.title" maxlength="128" placeholder="可不填，默认用第一个问题作为标题" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="submit">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.modes {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  width: 100%;
}

.mode {
  height: auto;
  margin-right: 0;
  padding: 10px 14px;
  align-items: flex-start;
  white-space: normal;
}

.mode :deep(.el-radio__input) {
  margin-top: 3px;
}

.mode-title {
  font-weight: 600;
  line-height: 1.6;
}

.mode-desc {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.full {
  width: 100%;
}
</style>
