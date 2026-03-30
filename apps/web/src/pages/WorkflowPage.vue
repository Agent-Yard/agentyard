<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import type { HumanActionType, HumanTaskSource, WorkflowInstance } from '../types';

const props = defineProps<{
  workflow?: WorkflowInstance;
  workflows: WorkflowInstance[];
  selectedWorkflowId: string | null;
}>();

const emit = defineEmits<{
  selectWorkflow: [workflowId: string];
  humanAction: [payload: { workflowId: string; action: string; comment: string; operatorId: string; attributes: Record<string, string> }];
}>();

const statusFilter = ref<'ALL' | 'WAITING_HUMAN' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'>('ALL');
const searchKeyword = ref('');
const humanForm = reactive({
  operatorId: 'operator-demo',
  comment: '人工已完成处理并同步客户',
});

const sourceLabel: Record<HumanTaskSource, string> = {
  GRAPH_NODE: '编排人工节点',
  AGENT_REQUEST: '智能体主动求助',
};

const filteredWorkflows = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase();
  return props.workflows.filter((item) => {
    const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value;
    const matchesKeyword = !keyword || [
      item.id,
      item.assistantName,
      item.summary,
      item.currentNodeKey ?? '',
      item.finalReply ?? '',
    ].some((value) => value.toLowerCase().includes(keyword));
    return matchesStatus && matchesKeyword;
  });
});

const current = computed(() =>
  filteredWorkflows.value.find((item) => item.id === props.selectedWorkflowId)
  ?? filteredWorkflows.value[0]
  ?? props.workflow
  ?? props.workflows.find((item) => item.id === props.selectedWorkflowId)
  ?? props.workflows[0],
);

function actionStatus(status: string) {
  if (status === 'COMPLETED') return 'finish';
  if (status === 'WAITING_HUMAN') return 'process';
  if (status === 'FAILED' || status === 'CANCELLED') return 'error';
  return 'wait';
}

function workflowTagColor(status: string) {
  if (status === 'WAITING_HUMAN') return 'orange';
  if (status === 'RUNNING') return 'processing';
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED' || status === 'CANCELLED') return 'red';
  return 'default';
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString();
}

function submitAction(action: HumanActionType) {
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

function allowedActions(actions?: HumanActionType[]) {
  return actions ?? ['CONFIRM', 'TERMINATE'];
}

function selectWorkflow(workflowId: string) {
  emit('selectWorkflow', workflowId);
}

function hasSharedState(value?: { facts: Record<string, unknown>; artifacts: Record<string, unknown>; agentScopes: Record<string, Record<string, unknown>> } | null) {
  if (!value) {
    return false;
  }
  return Object.keys(value.facts).length > 0 || Object.keys(value.artifacts).length > 0 || Object.keys(value.agentScopes).length > 0;
}

function formatSharedState(value?: { facts: Record<string, unknown>; artifacts: Record<string, unknown>; agentScopes: Record<string, Record<string, unknown>> } | null) {
  return JSON.stringify(value ?? { facts: {}, artifacts: {}, agentScopes: {} }, null, 2);
}

function formatDecision(value?: WorkflowInstance['agentTurnState'] | null) {
  if (!value?.latestDecision) {
    return '暂无';
  }
  const decision = value.latestDecision;
  const parts: string[] = [decision.decisionType];
  if (decision.routeDecision) {
    parts.push(`route=${decision.routeDecision}`);
  }
  if (decision.toolRequests.length) {
    parts.push(`tools=${decision.toolRequests.map((item) => item.operation).join(', ')}`);
  }
  if (decision.skillReads.length) {
    parts.push(`skills=${decision.skillReads.length}`);
  }
  if (decision.message) {
    parts.push(decision.message);
  }
  return parts.join(' / ');
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="流程列表">
        <a-space direction="vertical" style="width: 100%" size="middle">
          <a-input v-model:value="searchKeyword" allow-clear placeholder="搜索 workflow / 助手 / 摘要" />
          <a-select
            v-model:value="statusFilter"
            :options="[
              { label: '全部状态', value: 'ALL' },
              { label: '待人工', value: 'WAITING_HUMAN' },
              { label: '运行中', value: 'RUNNING' },
              { label: '已完成', value: 'COMPLETED' },
              { label: '失败', value: 'FAILED' },
              { label: '已取消', value: 'CANCELLED' },
            ]"
          />

          <a-list v-if="filteredWorkflows.length" :data-source="filteredWorkflows">
            <template #renderItem="{ item }">
              <a-list-item
                class="clickable-item"
                :class="{ 'graph-list-item--active': current?.id === item.id }"
                @click="selectWorkflow(item.id)"
              >
                <a-list-item-meta
                  :title="item.id"
                  :description="`${item.assistantName} · ${item.summary}`"
                />
                <template #extra>
                  <a-space direction="vertical" size="small" style="align-items: flex-end">
                    <a-tag :color="workflowTagColor(item.status)">{{ item.status }}</a-tag>
                    <span>{{ formatDateTime(item.updatedAt) }}</span>
                  </a-space>
                </template>
              </a-list-item>
            </template>
          </a-list>
          <a-empty v-else description="没有符合条件的流程" />
        </a-space>
      </a-card>
    </a-col>

    <a-col :span="16">
      <a-space direction="vertical" style="width: 100%" size="large">
        <a-card v-if="current">
          <a-descriptions :column="2" :title="`流程 ${current.id}`">
            <a-descriptions-item label="任务 ID">{{ current.taskId }}</a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-tag :color="workflowTagColor(current.status)">{{ current.status }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="助手">{{ current.assistantName }}</a-descriptions-item>
            <a-descriptions-item label="助手版本">{{ current.assistantReleaseVersion }}</a-descriptions-item>
            <a-descriptions-item label="创建时间">{{ formatDateTime(current.createdAt) }}</a-descriptions-item>
            <a-descriptions-item label="最近更新">{{ formatDateTime(current.updatedAt) }}</a-descriptions-item>
            <a-descriptions-item label="当前节点">{{ current.currentNodeKey ?? '无' }}</a-descriptions-item>
            <a-descriptions-item label="是否待人工">{{ current.escalationRequired ? '是' : '否' }}</a-descriptions-item>
            <a-descriptions-item label="摘要">{{ current.summary }}</a-descriptions-item>
            <a-descriptions-item label="最终回复">{{ current.finalReply ?? '尚未形成最终回复' }}</a-descriptions-item>
            <a-descriptions-item label="人工记录">{{ current.interventions.length }}</a-descriptions-item>
            <a-descriptions-item label="Checkpoint">
              {{ current.checkpoint ? `${current.checkpoint.checkpointId} / resume=${current.checkpoint.resumeCount}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="挂起原因">
              {{ current.pauseReason ? `${current.pauseReason.code} / ${current.pauseReason.detail}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="工具结果">
              {{ current.latestToolOutcome ? `${current.latestToolOutcome.toolResourceName} / ${current.latestToolOutcome.operation}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="资源锚点">
              {{ current.resourceAnchors.join(' / ') || '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="已加载 Skill">
              {{ current.loadedSkillResourceVersionIds.join(' / ') || '无' }}
            </a-descriptions-item>
          </a-descriptions>

          <a-alert
            v-if="current.humanTask"
            type="warning"
            show-icon
            :message="current.humanTask.title"
            :description="`${current.humanTask.instruction} 来源：${sourceLabel[current.humanTask.source]}。处理指引：${current.humanTask.expectedAction}`"
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

          <a-card size="small" title="Workflow 共享状态" style="margin-top: 16px">
            <a-alert
              v-if="!hasSharedState(current.sharedState)"
              type="info"
              show-icon
              message="当前共享状态为空"
              description="facts / artifacts / agentScopes 还没有可观测内容。"
            />
            <pre v-else style="white-space: pre-wrap; word-break: break-word; margin: 0">{{ formatSharedState(current.sharedState) }}</pre>
          </a-card>

          <a-card size="small" title="结构化决策" style="margin-top: 16px">
            <a-descriptions :column="2" size="small">
              <a-descriptions-item label="当前阶段">{{ current.agentTurnState?.phase ?? 'IDLE' }}</a-descriptions-item>
              <a-descriptions-item label="轮次">{{ current.agentTurnState?.turnIndex ?? 0 }}</a-descriptions-item>
              <a-descriptions-item label="最新决策" :span="2">
                {{ formatDecision(current.agentTurnState) }}
              </a-descriptions-item>
            </a-descriptions>
            <a-alert
              v-if="!(current.agentTurnState?.turnLogs?.length)"
              type="info"
              show-icon
              message="当前还没有结构化决策日志"
              style="margin-top: 12px"
            />
            <a-table
              v-else
              style="margin-top: 12px"
              :data-source="current.agentTurnState?.turnLogs ?? []"
              :pagination="false"
              size="small"
              row-key="turnIndex"
            >
              <a-table-column title="轮次" data-index="turnIndex" key="turnIndex" />
              <a-table-column title="阶段" data-index="phase" key="phase" />
              <a-table-column title="决策" key="decisionType">
                <template #default="{ record }">
                  {{ record.decisionType ?? '-' }}
                </template>
              </a-table-column>
              <a-table-column title="工具调用" data-index="toolCallsDelta" key="toolCallsDelta" />
              <a-table-column title="路由来源" key="routeSource">
                <template #default="{ record }">
                  {{ record.routeSource || '-' }}
                </template>
              </a-table-column>
              <a-table-column title="失败原因" key="failureReason">
                <template #default="{ record }">
                  {{ record.failureReason || '-' }}
                </template>
              </a-table-column>
            </a-table>
          </a-card>
        </a-card>

        <a-empty v-else description="还没有可观测的流程" />

        <a-row v-if="current" :gutter="[16, 16]">
          <a-col :span="12">
            <a-card title="节点执行轨迹">
              <a-table :data-source="current.nodes" :pagination="false" row-key="id" size="small">
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
              <a-list :data-source="current.toolCalls" size="small">
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

              <a-list :data-source="current.interventions" size="small">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta
                      :title="`${item.action} · ${item.operator}`"
                      :description="item.comment"
                    />
                  </a-list-item>
                </template>
              </a-list>

              <a-form v-if="current.status === 'WAITING_HUMAN'" layout="vertical" style="margin-top: 16px">
                <a-form-item label="处理人">
                  <a-input v-model:value="humanForm.operatorId" />
                </a-form-item>
                <a-form-item label="处理备注">
                  <a-textarea v-model:value="humanForm.comment" :rows="3" />
                </a-form-item>
                <a-space>
                  <a-button
                    v-if="allowedActions(current.humanTask?.allowedActions).includes('CONFIRM')"
                    type="primary"
                    @click="submitAction('CONFIRM')"
                  >
                    确认并恢复流程
                  </a-button>
                  <a-button
                    v-if="allowedActions(current.humanTask?.allowedActions).includes('TERMINATE')"
                    danger
                    @click="submitAction('TERMINATE')"
                  >
                    终止流程
                  </a-button>
                </a-space>
              </a-form>
            </a-card>
          </a-col>
        </a-row>
      </a-space>
    </a-col>
  </a-row>
</template>
