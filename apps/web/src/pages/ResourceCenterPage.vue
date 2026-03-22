<script setup lang="ts">
import { computed } from 'vue';
import PageHeaderCard from '../components/PageHeaderCard.vue';
import type { ResourceCenter } from '../types';

const props = defineProps<{
  resourceCenter: ResourceCenter;
}>();

const columns = computed(() => [
  { title: '资源名', dataIndex: 'resourceName', key: 'resourceName' },
  { title: '类型', dataIndex: 'type', key: 'type' },
  { title: '共享范围', dataIndex: 'shareScope', key: 'shareScope' },
  { title: '归属', dataIndex: 'ownerLabel', key: 'ownerLabel' },
  { title: '绑定智能体', dataIndex: 'boundAgents', key: 'boundAgents' },
  { title: '绑定智能体组', dataIndex: 'boundAgentGroups', key: 'boundAgentGroups' },
]);
</script>

<template>
  <PageHeaderCard
    title="资源中心页"
    subtitle="统一查看 Skill、MCP、知识库的归属、共享范围与绑定情况。"
  />

  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card><a-statistic title="资源总数" :value="resourceCenter.totalResources" /></a-card>
    </a-col>
    <a-col :span="8">
      <a-card><a-statistic title="域内共享" :value="resourceCenter.domainSharedResources" /></a-card>
    </a-col>
    <a-col :span="8">
      <a-card><a-statistic title="私有资源" :value="resourceCenter.privateResources" /></a-card>
    </a-col>
  </a-row>

  <a-card title="资源绑定矩阵">
    <a-table :columns="columns" :data-source="resourceCenter.usages" :pagination="false" row-key="resourceId">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'shareScope'">
          <a-tag :color="record.shareScope === 'DOMAIN_SHARED' ? 'green' : 'gold'">
            {{ record.shareScope }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'boundAgents'">
          {{ record.boundAgents.join(' / ') }}
        </template>
        <template v-else-if="column.key === 'boundAgentGroups'">
          {{ record.boundAgentGroups.join(' / ') }}
        </template>
      </template>
    </a-table>
  </a-card>
</template>
