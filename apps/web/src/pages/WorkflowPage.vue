<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type { ResumeActionType, WorkflowInstance } from '../types';
import { failureAlertDescription, failureAlertMessage, failureAlertType, failureSummary, hasActiveFailure } from './workflowFailure';
import { pauseSourceLabel as sourceLabel, toolCallTitle, workflowDecisionSummary as formatDecision } from './runtimePresentation';

const props = defineProps<{
  workflow?: WorkflowInstance;
  workflows: WorkflowInstance[];
  selectedWorkflowId: string | null;
  currentUserId: string | null;
}>();

const emit = defineEmits<{
  selectWorkflow: [workflowId: string];
  resumeAction: [payload: { workflowId: string; type: string; comment: string; userId: string; attributes: Record<string, string> }];
}>();

const statusFilter = ref<'ALL' | 'WAITING_RESUME' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'>('ALL');
const searchKeyword = ref('');
const humanForm = reactive({
  userId: props.currentUserId ?? '',
  comment: '人工已完成处理并同步客户',
});

watch(
  () => props.currentUserId,
  (userId) => {
    if (!humanForm.userId && userId) {
      humanForm.userId = userId;
    }
  },
  { immediate: true },
);

const filteredWorkflows = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase();
  return props.workflows.filter((item) => {
    const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value;
    const matchesKeyword = !keyword || [
      item.id,
      item.assistantName,
      item.summary,
      item.latestFailure?.code ?? '',
      item.latestFailure?.rootCause ?? '',
      item.currentNodeKey ?? '',
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
  if (status === 'WAITING_RESUME') return 'process';
  if (status === 'FAILED' || status === 'CANCELLED') return 'error';
  return 'wait';
}

function workflowTagColor(status: string) {
  if (status === 'WAITING_RESUME') return 'orange';
  if (status === 'RUNNING') return 'processing';
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED' || status === 'CANCELLED') return 'red';
  return 'default';
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString();
}

function submitAction(action: ResumeActionType) {
  if (!current.value) {
    return;
  }
  emit('resumeAction', {
    workflowId: current.value.id,
    type: action,
    comment: humanForm.comment,
    userId: humanForm.userId,
    attributes: {},
  });
}

function allowedActions(actions?: ResumeActionType[]) {
  return actions ?? ['CONTINUE', 'TERMINATE'];
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

function formatFailureLocation(workflow: WorkflowInstance) {
  if (!workflow.latestFailure) {
    return '无';
  }
  const node = workflow.latestFailure.failedNodeKey
    ? `${workflow.latestFailure.failedNodeName ?? workflow.latestFailure.failedNodeKey} / ${workflow.latestFailure.failedNodeKey}`
    : '';
  const resource = workflow.latestFailure.failedResourceId
    ? `${workflow.latestFailure.failedResourceName ?? workflow.latestFailure.failedResourceId} / ${workflow.latestFailure.failedResourceId}`
    : '';
  return [node, resource].filter(Boolean).join(' | ') || '无';
}

function modelSourceLabel(source: string) {
  return source === 'AGENT_OVERRIDE' ? 'Agent Override' : 'Assistant Default';
}

function formatLatestModelHit(workflow?: WorkflowInstance | null) {
  const hit = workflow?.modelHits.at(-1);
  if (!hit) {
    return '无';
  }
  return `${hit.resourceName} @ ${hit.resourceVersion} · ${hit.providerType} / ${hit.modelId} · ${modelSourceLabel(hit.source)}`;
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
              { label: '待恢复', value: 'WAITING_RESUME' },
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
                    <span v-if="hasActiveFailure(item)" style="max-width: 180px; text-align: right">{{ failureSummary(item) }}</span>
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
            <a-descriptions-item label="是否待恢复">{{ current.escalationRequired ? '是' : '否' }}</a-descriptions-item>
            <a-descriptions-item label="摘要">{{ current.summary }}</a-descriptions-item>
            <a-descriptions-item label="恢复记录">{{ current.resumeInterventions.length }}</a-descriptions-item>
            <a-descriptions-item label="Checkpoint">
              {{ current.checkpoint ? `${current.checkpoint.checkpointId} / resume=${current.checkpoint.resumeCount}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="挂起原因">
              {{ current.pauseReason ? `${current.pauseReason.code} / ${current.pauseReason.detail}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="失败诊断">
              {{ current.latestFailure ? `${current.latestFailure.category} / ${current.latestFailure.code}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="工具结果">
              {{ current.latestToolOutcome ? `${current.latestToolOutcome.toolName} / ${current.latestToolOutcome.operation} / ${current.latestToolOutcome.toolKind}` : '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="最新模型命中">
              {{ formatLatestModelHit(current) }}
            </a-descriptions-item>
            <a-descriptions-item label="资源锚点">
              {{ current.resourceAnchors.join(' / ') || '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="已加载 Skill">
              {{ current.loadedSkillResourceVersionIds.join(' / ') || '无' }}
            </a-descriptions-item>
          </a-descriptions>

          <a-alert
            v-if="current.resumeTask"
            type="warning"
            show-icon
            :message="current.resumeTask.title"
                :description="`${current.resumeTask.instruction} 来源：${sourceLabel[current.resumeTask.source]}。处理指引：${current.resumeTask.expectedAction}`"
            style="margin-top: 16px"
          />

          <a-alert
            v-if="hasActiveFailure(current)"
            :type="failureAlertType(current)"
            show-icon
            :message="failureAlertMessage(current)"
            :description="failureAlertDescription(current)"
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

          <a-card size="small" title="模型命中" style="margin-top: 16px">
            <a-alert
              v-if="!current.modelHits.length"
              type="info"
              show-icon
              message="当前还没有记录到模型命中"
              description="只有 agent 节点真正准备发起模型请求时，才会写入运行时命中记录。"
            />
            <a-table
              v-else
              :data-source="current.modelHits"
              :pagination="false"
              size="small"
              row-key="capturedAt"
            >
              <a-table-column title="轮次" data-index="turnIndex" key="turnIndex" />
              <a-table-column title="Agent / 节点" key="agentNode">
                <template #default="{ record }">
                  {{ record.agentName }} / {{ record.nodeName }}
                </template>
              </a-table-column>
              <a-table-column title="来源" key="source">
                <template #default="{ record }">
                  {{ modelSourceLabel(record.source) }}
                </template>
              </a-table-column>
              <a-table-column title="模型" key="model">
                <template #default="{ record }">
                  {{ record.providerType }} / {{ record.modelId }}
                </template>
              </a-table-column>
              <a-table-column title="资源版本" key="resource">
                <template #default="{ record }">
                  {{ record.resourceName }} @ {{ record.resourceVersion }}
                </template>
              </a-table-column>
              <a-table-column title="记录时间" key="capturedAt">
                <template #default="{ record }">
                  {{ formatDateTime(record.capturedAt) }}
                </template>
              </a-table-column>
            </a-table>
          </a-card>

          <a-card size="small" title="失败诊断" style="margin-top: 16px">
            <a-alert
              v-if="!current.latestFailure"
              type="info"
              show-icon
              message="当前没有结构化失败快照"
            />
            <a-descriptions v-else :column="2" size="small">
              <a-descriptions-item label="Category">{{ current.latestFailure.category }}</a-descriptions-item>
              <a-descriptions-item label="Code">{{ current.latestFailure.code }}</a-descriptions-item>
              <a-descriptions-item label="Root Cause" :span="2">{{ current.latestFailure.rootCause }}</a-descriptions-item>
              <a-descriptions-item label="Detail" :span="2">{{ current.latestFailure.detail }}</a-descriptions-item>
              <a-descriptions-item label="失败位置" :span="2">{{ formatFailureLocation(current) }}</a-descriptions-item>
              <a-descriptions-item label="发生时间" :span="2">{{ formatDateTime(current.latestFailure.occurredAt) }}</a-descriptions-item>
            </a-descriptions>
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
            <a-card title="工具与恢复记录">
              <a-list :data-source="current.toolCalls" size="small">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta
                      :title="toolCallTitle(item)"
                      :description="`${item.providerType} / ${item.status} / ${item.detail}`"
                    />
                  </a-list-item>
                </template>
              </a-list>

              <a-divider />

              <a-list :data-source="current.resumeInterventions" size="small">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta
                      :title="`${item.type} / ${item.source} · ${item.userId}`"
                      :description="item.comment"
                    />
                  </a-list-item>
                </template>
              </a-list>

              <a-form v-if="current.status === 'WAITING_RESUME'" layout="vertical" style="margin-top: 16px">
                <a-form-item label="恢复用户 ID">
                  <a-input v-model:value="humanForm.userId" disabled />
                </a-form-item>
                <a-form-item label="恢复备注">
                  <a-textarea v-model:value="humanForm.comment" :rows="3" />
                </a-form-item>
                <a-space>
                  <a-button
                    v-if="allowedActions(current.resumeTask?.allowedActions).includes('CONTINUE')"
                    type="primary"
                    @click="submitAction('CONTINUE')"
                  >
                    确认并恢复流程
                  </a-button>
                  <a-button
                    v-if="allowedActions(current.resumeTask?.allowedActions).includes('TERMINATE')"
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
