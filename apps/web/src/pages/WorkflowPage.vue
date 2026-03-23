<script setup lang="ts">
import { computed } from 'vue';
import type { WorkflowInstance } from '../types';

const props = defineProps<{
  workflow?: WorkflowInstance;
  workflows: WorkflowInstance[];
}>();

const emit = defineEmits<{
  humanAction: [payload: { workflowId: string; action: string; comment: string }];
}>();

const current = computed(() => props.workflow ?? props.workflows[0]);
const columns = [
  { title: '节点', dataIndex: 'nodeName', key: 'nodeName' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '说明', dataIndex: 'detail', key: 'detail' },
];

function actionStatus(status: string) {
  if (status === 'COMPLETED') return 'finish';
  if (status === 'WAITING_HUMAN') return 'process';
  if (status === 'FAILED') return 'error';
  return 'wait';
}
</script>

<template>
  <a-card v-if="current">
    <a-descriptions :column="2" :title="`流程 ${current.id}`">
      <a-descriptions-item label="助手">{{ current.assistantName }}</a-descriptions-item>
      <a-descriptions-item label="助手版本">{{ current.assistantReleaseVersion }}</a-descriptions-item>
      <a-descriptions-item label="状态">{{ current.status }}</a-descriptions-item>
      <a-descriptions-item label="摘要">{{ current.summary }}</a-descriptions-item>
      <a-descriptions-item label="是否待人工">{{ current.escalationRequired ? '是' : '否' }}</a-descriptions-item>
      <a-descriptions-item label="人工记录">{{ current.interventions.length }}</a-descriptions-item>
      <a-descriptions-item label="MCP 工单">{{ current.mcpSummary?.externalTicketId ?? '无' }}</a-descriptions-item>
      <a-descriptions-item label="MCP 结果">
        {{ current.mcpSummary ? `${current.mcpSummary.status} / ${current.mcpSummary.recommendedAction}` : '无' }}
      </a-descriptions-item>
      <a-descriptions-item label="MCP 说明">{{ current.mcpSummary?.detail ?? '无' }}</a-descriptions-item>
      <a-descriptions-item label="资源锚点">{{ current.resourceAnchors.join(' / ') }}</a-descriptions-item>
    </a-descriptions>

    <a-steps
      :items="current.nodes.map((node) => ({
        title: node.nodeName,
        description: node.status,
        status: actionStatus(node.status),
      }))"
    />

    <a-space v-if="current.status === 'WAITING_HUMAN'" style="margin-top: 16px">
      <a-button
        type="primary"
        @click="emit('humanAction', { workflowId: current.id, action: 'CONFIRM', comment: '人工已确认并接管处理' })"
      >
        确认处理
      </a-button>
      <a-button
        danger
        @click="emit('humanAction', { workflowId: current.id, action: 'TERMINATE', comment: '流程终止' })"
      >
        终止流程
      </a-button>
    </a-space>
  </a-card>

  <a-card title="节点明细">
    <a-table :columns="columns" :data-source="current?.nodes ?? []" :pagination="false" row-key="id" />
  </a-card>
</template>
