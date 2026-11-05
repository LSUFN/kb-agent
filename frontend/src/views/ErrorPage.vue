<script setup lang="ts">
defineProps<{ code: 403 | 404 }>()
</script>

<template>
  <div class="page">
    <el-result
      :icon="code === 403 ? 'warning' : 'info'"
      :title="code === 403 ? '无权访问' : '页面不存在'"
      :sub-title="code === 403 ? '当前账号没有访问该页面的权限' : '请检查地址是否正确'"
    >
      <template #extra>
        <el-button type="primary" @click="$router.replace('/chat')">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>
