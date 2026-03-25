<script setup lang="ts">
import { computed, reactive } from 'vue';
import type { WorkflowInstance } from '../types';

const props = defineProps<{
  workflow?: WorkflowInstance;
  workflows: WorkflowInstance[];
}>();

const emit = defineEmits<{
  humanAction: [payload: { workflowId: string; action: string; comment: string; operatorId: string; attributes: Record<string, string> }];
}>();

const current = computed(() => props.workflow ?? props.workflows[0]);
const humanForm = reactive({
  operatorId: 'operator-demo',
  comment: '人工已完成处理并同步客户',
});

function actionStatus(status: string) {
  if (status === 'COMPLETED') return 'finish';
  if (status === 'WAITING_HUMAN') return 'process';
  if (status === 'FAILED' || status === 'CANCELLED') return 'error';
  return 'wait';
}

function submitAction(action: string) {
  if (!current.value) {
    return;
  }
  emit('humanAction', {
    workflowId: current.value.id,
    action,
    comment: humanForm.comment,
    operatorId: humanForm.operatorId,
    attributes: {},
  });
}
</script>

<template>
  <a-space direction="vertical" style="width: 100%" size="large">
    <a-card v-if="current">
      <a-descriptions :column="2" :title="`流程 ${current.id}`">
        <a-descriptions-item label="助手">{{ current.assistantName }}</a-descriptions-item>
        <a-descriptions-item label="助手版本">{{ current.assistantReleaseVersion }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ current.status }}</a-descriptions-item>
        <a-descriptions-item label="当前节点">{{ current.currentNodeKey ?? '无' }}</a-descriptions-item>
        <a-descriptions-item label="摘要">{{ current.summary }}</a-descriptions-item>
        <a-descriptions-item label="最终回复">{{ current.finalReply ?? '尚未形成最终回复' }}</a-descriptions-item>
        <a-descriptions-item label="是否待人工">{{ current.escalationRequired ? '是' : '否' }}</a-descriptions-item>
        <a-descriptions-item label="人工记录">{{ current.interventions.length }}</a-descriptions-item>
        <a-descriptions-item label="Checkpoint">
          {{ current.checkpoint ? `${current.checkpoint.checkpointId} / resume=${current.checkpoint.resumeCount}` : '无' }}
        </a-descriptions-item>
        <a-descriptions-item label="资源锚点">
          {{ current.resourceAnchors.join(' / ') || '无' }}
        </a-descriptions-item>
        <a-descriptions-item label="工具外部引用">{{ current.latestToolOutcome?.externalReference || '无' }}</a-descriptions-item>
        <a-descriptions-item label="工具结果">
          {{ current.latestToolOutcome ? `${current.latestToolOutcome.status} / ${current.latestToolOutcome.recommendedAction}` : '无' }}
        </a-descriptions-item>
      </a-descriptions>

      <a-alert
        v-if="current.humanTask"
        type="warning"
        show-icon
        :message="current.humanTask.title"
        :description="`${current.humanTask.instruction} 期望动作：${current.humanTask.expectedAction}`"
        style="margin-top: 16px"
      />

      <a-steps
        style="margin-top: 16px"
        :items="current.nodes.map((node) => ({
          title: `${node.nodeName} (${node.nodeKey})`,
          description: node.detail,
          status: actionStatus(node.status),
        }))"
      />
    </a-card>

    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-card title="节点执行轨迹">
          <a-table :data-source="current?.nodes ?? []" :pagination="false" row-key="id" size="small">
            <a-table-column title="节点" key="node">
              <template #default="{ record }">
                {{ record.nodeName }} / {{ record.nodeKey }}
              </template>
            </a-table-column>
            <a-table-column title="状态" data-index="status" key="status" />
            <a-table-column title="说明" data-index="detail" key="detail" />
          </a-table>
        </a-card>
      </a-col>

      <a-col :span="12">
        <a-card title="工具与人工处理">
          <a-list :data-source="current?.toolCalls ?? []" size="small">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta
                  :title="`${item.resourceName} · ${item.operation}`"
                  :description="`${item.providerType} / ${item.status} / ${item.detail}`"
                />
              </a-list-item>
            </template>
          </a-list>

          <a-divider />

          <a-list :data-source="current?.interventions ?? []" size="small">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta
                  :title="`${item.action} · ${item.operator}`"
                  :description="item.comment"
                />
              </a-list-item>
            </template>
          </a-list>

          <a-form v-if="current?.status === 'WAITING_HUMAN'" layout="vertical" style="margin-top: 16px">
            <a-form-item label="处理人">
              <a-input v-model:value="humanForm.operatorId" />
            </a-form-item>
            <a-form-item label="处理备注">
              <a-textarea v-model:value="humanForm.comment" :rows="3" />
            </a-form-item>
            <a-space>
              <a-button type="primary" @click="submitAction('CONFIRM')">确认并恢复流程</a-button>
              <a-button danger @click="submitAction('TERMINATE')">终止流程</a-button>
            </a-space>
          </a-form>
        </a-card>
      </a-col>
    </a-row>
  </a-space>
</template>
