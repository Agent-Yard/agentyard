<script setup lang="ts">
import { reactive } from 'vue';
import type { Scenario, TaskInstance } from '../types';

const props = defineProps<{
  scenarios: Scenario[];
  tasks: TaskInstance[];
}>();

const emit = defineEmits<{
  launch: [payload: { scenarioId: string; question: string; requester: string }];
}>();

const formState = reactive({
  scenarioId: props.scenarios[0]?.id ?? '',
  requester: '业务用户A',
  question: '',
});

const columns = [
  { title: '任务 ID', dataIndex: 'id', key: 'id' },
  { title: '问题', dataIndex: 'question', key: 'question' },
  { title: '发起人', dataIndex: 'requester', key: 'requester' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '流程 ID', dataIndex: 'workflowInstanceId', key: 'workflowInstanceId' },
];

function submit() {
  emit('launch', { ...formState });
  formState.question = '';
}
</script>

<template>
  <a-card>
    <a-form layout="vertical" :model="formState" @finish="submit">
      <a-form-item label="业务场景" name="scenarioId">
        <a-select
          v-model:value="formState.scenarioId"
          :options="scenarios.map((item) => ({ label: item.name, value: item.id }))"
        />
      </a-form-item>
      <a-form-item label="发起人" name="requester">
        <a-input v-model:value="formState.requester" />
      </a-form-item>
      <a-form-item label="业务问题" name="question">
        <a-textarea v-model:value="formState.question" :rows="4" placeholder="例如：客户投诉未收到退款，需要人工介入" />
      </a-form-item>
      <a-button type="primary" html-type="submit">发起任务</a-button>
    </a-form>
  </a-card>

  <a-card title="任务列表">
    <a-table :columns="columns" :data-source="tasks" :pagination="false" row-key="id" />
  </a-card>
</template>
