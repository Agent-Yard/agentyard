<script setup lang="ts">
import type { CollaborationRole, TaskPermission } from '../../../../packages/contracts/src/task';
const model = defineModel<CollaborationRole[]>({ required: true });
const permissions: { label: string; value: TaskPermission }[] = [
  { label: '查看', value: 'VIEW' }, { label: '贡献', value: 'CONTRIBUTE' }, { label: '决策', value: 'DECIDE' },
  { label: '执行', value: 'EXECUTE' }, { label: '管理', value: 'MANAGE' },
];
</script>
<template>
  <div class="role-editor">
    <div v-for="(role, index) in model" :key="index" class="role-row">
      <a-input v-model:value="role.key" placeholder="角色标识，如 researcher" aria-label="角色标识" />
      <a-input v-model:value="role.name" placeholder="角色名称" aria-label="角色名称" />
      <a-select v-model:value="role.permissions" mode="multiple" :options="permissions" placeholder="权限" aria-label="角色权限" />
      <a-button danger @click="model.splice(index, 1)">移除</a-button>
    </div>
    <a-button @click="model.push({ key: '', name: '', permissions: ['VIEW'] })">添加角色</a-button>
  </div>
</template>
<style scoped>
.role-editor { display: grid; gap: 12px; }
.role-row { display: grid; grid-template-columns: 130px 130px minmax(220px, 1fr) auto; gap: 8px; }
@media(max-width: 900px) { .role-row { grid-template-columns: 1fr; } }
</style>
