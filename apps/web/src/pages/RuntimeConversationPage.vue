<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type {
  ConversationMessage,
  ConversationSession,
  ExternalInteractionMessagePayload,
  PauseSource,
  Scenario,
  TaskInstance,
  TextMessagePayload,
  WorkflowInstance,
} from '../types';

const props = defineProps<{
  scenarios: Scenario[];
  assistants: Scenario['assistants'];
  sessions: ConversationSession[];
  tasks: TaskInstance[];
  workflows: WorkflowInstance[];
  creatingSession: boolean;
  sendingSessionId: string | null;
  preferredSessionId: string | null;
  selectedSessionId: string | null;
  currentCustomerId: string | null;
}>();

const emit = defineEmits<{
  selectSession: [sessionId: string];
  createSession: [payload: { scenarioId: string; assistantId: string; customerId: string; openingMessage: string }];
  sendMessage: [payload: { sessionId: string; customerId: string; message: string }];
}>();
const createForm = reactive({
  scenarioId: '',
  assistantId: '',
  customerId: '',
  openingMessage: '',
});
const messageDraft = ref('');

const currentSession = computed(() =>
  props.sessions.find((item) => item.id === props.selectedSessionId) ?? props.sessions[0],
);

const currentScenario = computed(() =>
  props.scenarios.find((item) => item.id === currentSession.value?.scenarioId),
);
const isCurrentSessionSending = computed(() => props.sendingSessionId === currentSession.value?.id);

const availableAssistants = computed(() => currentScenario.value?.assistants ?? []);
const createSelectedAssistant = computed(() =>
  props.scenarios
    .find((item) => item.id === createForm.scenarioId)
    ?.assistants.find((item) => item.id === createForm.assistantId) ?? null,
);
const currentSessionAssistant = computed(() =>
  props.assistants.find((item) => item.id === currentSession.value?.assistantId) ?? null,
);
const latestWorkflow = computed(() =>
  props.workflows.find((item) => item.id === currentSession.value?.latestWorkflowInstanceId),
);
const latestTask = computed(() =>
  props.tasks.find((item) => item.id === currentSession.value?.latestTaskId),
);

function canRunAssistant(assistant?: Scenario['assistants'][number] | null) {
  if (!assistant) {
    return false;
  }
  return !!assistant.currentRelease || !!assistant.modelPolicy.defaultModelResourceId;
}

const createAssistantBlockingMessage = computed(() => {
  if (!createSelectedAssistant.value || canRunAssistant(createSelectedAssistant.value)) {
    return null;
  }
  return '该助手尚未发布，且草稿默认模型未配置，当前不能创建并启动对话。';
});

const currentSessionBlockingMessage = computed(() => {
  if (!currentSessionAssistant.value || canRunAssistant(currentSessionAssistant.value)) {
    return null;
  }
  return '该助手没有已发布版本，且草稿默认模型未配置，当前不能继续发送消息。';
});

const sourceLabel: Record<PauseSource, string> = {
  GRAPH_NODE: '编排人工节点',
  AGENT_REQUEST: '智能体主动求助',
  EXTERNAL_INTERACTION: '外部交互',
  TIMEOUT_POLICY: '超时策略',
};

watch(
  () => props.sessions,
  (sessions) => {
    if (sessions.length === 1 && props.selectedSessionId !== sessions[0].id) {
      emit('selectSession', sessions[0].id);
    }
  },
  { immediate: true },
);

watch(
  () => props.preferredSessionId,
  (sessionId) => {
    if (!sessionId) {
      return;
    }
    if (props.sessions.some((item) => item.id === sessionId)) {
      emit('selectSession', sessionId);
    }
  },
  { immediate: true },
);

watch(
  () => props.scenarios,
  (scenarios) => {
    if (!createForm.scenarioId && scenarios.length > 0) {
      createForm.scenarioId = scenarios[0].id;
      createForm.assistantId = scenarios[0].assistants[0]?.id ?? '';
    }
  },
  { immediate: true },
);
watch(
  () => props.currentCustomerId,
  (customerId) => {
    if (!createForm.customerId && customerId) {
      createForm.customerId = customerId;
    }
  },
  { immediate: true },
);

watch(
  () => createForm.scenarioId,
  (scenarioId) => {
    const scenario = props.scenarios.find((item) => item.id === scenarioId);
    createForm.assistantId = scenario?.assistants[0]?.id ?? '';
  },
);

function submitCreate() {
  if (!createForm.scenarioId || !createForm.assistantId || props.creatingSession || createAssistantBlockingMessage.value) {
    return;
  }
  emit('createSession', { ...createForm });
  createForm.openingMessage = '';
}

function submitMessage() {
  if (!currentSession.value || !messageDraft.value.trim() || isCurrentSessionSending.value || currentSessionBlockingMessage.value) {
    return;
  }

  emit('sendMessage', {
    sessionId: currentSession.value.id,
    customerId: currentSession.value.customerId,
    message: messageDraft.value.trim(),
  });
}

function selectSession(sessionId: string) {
  emit('selectSession', sessionId);
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

function textPayload(message: ConversationMessage): TextMessagePayload | null {
  return message.payloadType === 'TEXT' ? (message.payload as TextMessagePayload) : null;
}

function interactionPayload(message: ConversationMessage): ExternalInteractionMessagePayload | null {
  return message.payloadType === 'EXTERNAL_INTERACTION' ? (message.payload as ExternalInteractionMessagePayload) : null;
}

function messageText(message: ConversationMessage) {
  const text = textPayload(message)?.text?.trim();
  if (text) {
    return text;
  }
  const interaction = interactionPayload(message);
  if (!interaction) {
    return '';
  }
  const title = interaction.title?.trim();
  const description = interaction.description?.trim();
  return [title, description].filter(Boolean).join(' · ');
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="7">
      <a-card title="新建会话">
        <a-form layout="vertical" :model="createForm">
          <a-form-item label="业务场景">
            <a-select
              v-model:value="createForm.scenarioId"
              :disabled="creatingSession"
              :options="scenarios.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="会话助手">
            <a-select
              v-model:value="createForm.assistantId"
              :disabled="creatingSession"
              :options="(scenarios.find((item) => item.id === createForm.scenarioId)?.assistants ?? []).map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="客户 ID">
            <a-input v-model:value="createForm.customerId" disabled />
          </a-form-item>
          <a-form-item label="开场问题">
            <a-textarea v-model:value="createForm.openingMessage" :rows="4" :disabled="creatingSession" />
          </a-form-item>
          <a-alert
            v-if="createAssistantBlockingMessage"
            type="warning"
            show-icon
            :message="createAssistantBlockingMessage"
            style="margin-bottom: 16px"
          />
          <a-button
            type="primary"
            :loading="creatingSession"
            :disabled="!createForm.scenarioId || !createForm.assistantId || !createForm.customerId || !!createAssistantBlockingMessage"
            @click="submitCreate"
          >
            {{ creatingSession ? '正在创建会话...' : '创建并开始对话' }}
          </a-button>
        </a-form>
      </a-card>

      <a-card title="会话列表">
        <a-list :data-source="sessions">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': props.selectedSessionId === item.id }"
              @click="selectSession(item.id)"
            >
              <a-list-item-meta
                :title="item.title"
                :description="`${item.assistantName} · ${item.messages.length} 条消息${item.latestWorkflowInstanceId ? '' : ' · 尚未开始'}${sendingSessionId === item.id ? ' · 正在处理中' : ''}`"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="17">
      <a-space direction="vertical" style="width: 100%" size="large">
        <a-card v-if="currentSession" :title="currentSession.title">
          <template #extra>
            <a-space>
              <a-tag color="blue">{{ currentSession.assistantName }}</a-tag>
              <a-tag>{{ currentScenario?.name }}</a-tag>
              <a-tag v-if="isCurrentSessionSending" color="processing">处理中</a-tag>
            </a-space>
          </template>

          <a-row :gutter="[16, 16]">
            <a-col :span="16">
              <a-alert
                v-if="isCurrentSessionSending"
                type="info"
                show-icon
                message="消息已提交，助手正在后台执行 workflow。"
                description="这次请求只负责受理，后续结果会通过页面轮询自动收口。"
                style="margin-bottom: 16px"
              />
              <a-alert
                v-if="currentSession.latestResumeTask"
                type="warning"
                show-icon
                :message="currentSession.latestResumeTask.title"
                :description="`${currentSession.latestResumeTask.instruction} 来源：${sourceLabel[currentSession.latestResumeTask.source]}。处理指引：${currentSession.latestResumeTask.expectedAction}${currentSession.latestPauseReason ? `。挂起原因：${currentSession.latestPauseReason.code}` : ''}。如果之前操作页已超时，也可以直接去流程观测页继续恢复这个 workflow。`"
                style="margin-bottom: 16px"
              />
              <a-alert
                v-else-if="latestWorkflow?.status === 'RUNNING'"
                type="info"
                show-icon
                message="workflow 正在后台继续执行"
                :description="`当前流程 ${latestWorkflow.id} 正在后台执行，页面会通过轮询自动刷新运行结果。`"
                style="margin-bottom: 16px"
              />
              <a-alert
                v-else-if="latestWorkflow?.status === 'FAILED' && latestWorkflow.latestFailure"
                type="error"
                show-icon
                message="workflow 执行失败"
                :description="`${latestWorkflow.latestFailure.category} / ${latestWorkflow.latestFailure.code} / ${latestWorkflow.latestFailure.rootCause}`"
                style="margin-bottom: 16px"
              />
              <a-alert
                v-else-if="latestWorkflow?.status === 'WAITING_RESUME' && latestWorkflow.latestFailure"
                type="warning"
                show-icon
                message="workflow 后台出错，已进入待恢复状态"
                :description="`${latestWorkflow.latestFailure.category} / ${latestWorkflow.latestFailure.code} / ${latestWorkflow.latestFailure.rootCause}`"
                style="margin-bottom: 16px"
              />
              <a-alert
                v-if="currentSessionBlockingMessage"
                type="warning"
                show-icon
                :message="currentSessionBlockingMessage"
                style="margin-bottom: 16px"
              />
              <a-spin :spinning="isCurrentSessionSending">
                <div class="conversation-board" :class="{ 'conversation-board--empty': currentSession.messages.length === 0 }">
                  <template v-if="currentSession.messages.length">
                    <div
                      v-for="message in currentSession.messages"
                      :key="message.id"
                      class="conversation-bubble"
                      :class="{
                        'conversation-bubble--user': message.role === 'USER',
                        'conversation-bubble--assistant': message.role === 'ASSISTANT',
                        'conversation-bubble--system': message.role === 'SYSTEM',
                      }"
                    >
                      <strong>{{ message.senderName }}</strong>
                      <template v-if="message.payloadType === 'EXTERNAL_INTERACTION'">
                        <div class="conversation-bubble__card">
                          <p><strong>{{ interactionPayload(message)?.title }}</strong></p>
                          <p>{{ interactionPayload(message)?.description }}</p>
                          <span class="conversation-bubble__meta">
                            状态: {{ interactionPayload(message)?.status }}
                          </span>
                        </div>
                      </template>
                      <p v-else>{{ messageText(message) }}</p>
                      <span v-if="message.workflowInstanceId" class="conversation-bubble__meta">
                        workflow: {{ message.workflowInstanceId }}
                      </span>
                    </div>
                  </template>
                  <a-empty v-else description="这个会话还没有开始。发送第一条消息后，会触发助手运行和 workflow 观测。" />
                </div>
              </a-spin>

              <a-form layout="vertical">
                <a-form-item label="继续对话">
                  <a-textarea
                    v-model:value="messageDraft"
                    :rows="4"
                    :disabled="isCurrentSessionSending || !!currentSessionBlockingMessage"
                    placeholder="继续输入问题，消息会持续交给当前会话选择的助手处理"
                  />
                </a-form-item>
                <a-button
                  type="primary"
                  :loading="isCurrentSessionSending"
                  :disabled="!currentSession || !messageDraft.trim() || !!currentSessionBlockingMessage"
                  @click="submitMessage"
                >
                  {{ isCurrentSessionSending ? '正在发送...' : '发送消息' }}
                </a-button>
              </a-form>
            </a-col>
            <a-col :span="8">
              <a-space direction="vertical" style="width: 100%" size="middle">
                <a-card size="small" title="当前助手">
                  <a-descriptions :column="1" size="small">
                    <a-descriptions-item label="助手名称">{{ currentSession.assistantName }}</a-descriptions-item>
                    <a-descriptions-item label="运行版本">{{ currentSession.assistantReleaseVersion }}</a-descriptions-item>
                    <a-descriptions-item label="所属场景">{{ currentScenario?.name }}</a-descriptions-item>
                    <a-descriptions-item label="可用助手">
                      {{ availableAssistants.map((item) => item.name).join(' / ') }}
                    </a-descriptions-item>
                  </a-descriptions>
                </a-card>
                <a-card size="small" title="Session 共享状态">
                  <a-alert
                    v-if="!hasSharedState(currentSession.sharedState)"
                    type="info"
                    show-icon
                    message="当前共享状态为空"
                    description="facts / artifacts / agentScopes 还没有被写入。"
                  />
                  <pre v-else style="white-space: pre-wrap; word-break: break-word; margin: 0">{{ formatSharedState(currentSession.sharedState) }}</pre>
                </a-card>
              </a-space>
            </a-col>
          </a-row>
        </a-card>

        <a-row :gutter="[16, 16]">
          <a-col :span="12">
            <a-card title="最新任务">
              <a-descriptions v-if="latestTask" :column="1" size="small">
                <a-descriptions-item label="任务 ID">{{ latestTask.id }}</a-descriptions-item>
                <a-descriptions-item label="助手版本">{{ latestTask.assistantReleaseVersion }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ latestTask.status }}</a-descriptions-item>
                <a-descriptions-item label="问题">{{ latestTask.question }}</a-descriptions-item>
              </a-descriptions>
            </a-card>
          </a-col>
          <a-col :span="12">
            <a-card title="最新流程">
              <a-descriptions v-if="latestWorkflow" :column="1" size="small">
                <a-descriptions-item label="流程 ID">{{ latestWorkflow.id }}</a-descriptions-item>
                <a-descriptions-item label="助手版本">{{ latestWorkflow.assistantReleaseVersion }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ latestWorkflow.status }}</a-descriptions-item>
                <a-descriptions-item label="当前节点">{{ latestWorkflow.currentNodeKey }}</a-descriptions-item>
                <a-descriptions-item label="摘要">{{ latestWorkflow.summary }}</a-descriptions-item>
                <a-descriptions-item label="最终回复">{{ latestWorkflow.finalReply ?? '尚未输出' }}</a-descriptions-item>
                <a-descriptions-item label="工具结果">
                  {{ currentSession?.latestToolOutcome
                    ? `${currentSession.latestToolOutcome.toolResourceName} / ${currentSession.latestToolOutcome.operation}`
                    : '当前无工具调用记录' }}
                </a-descriptions-item>
                <a-descriptions-item label="人工待办">
                  {{ currentSession?.latestResumeTask
                    ? `${currentSession.latestResumeTask.title} / ${sourceLabel[currentSession.latestResumeTask.source]} / ${currentSession.latestResumeTask.expectedAction}`
                    : '当前无人工待办' }}
                </a-descriptions-item>
                <a-descriptions-item label="挂起原因">
                  {{ currentSession?.latestPauseReason
                    ? `${currentSession.latestPauseReason.code} / ${currentSession.latestPauseReason.detail}`
                    : '当前无挂起原因' }}
                </a-descriptions-item>
                <a-descriptions-item label="失败诊断">
                  {{ latestWorkflow.latestFailure
                    ? `${latestWorkflow.latestFailure.category} / ${latestWorkflow.latestFailure.code} / ${latestWorkflow.latestFailure.rootCause}`
                    : '当前无结构化失败信息' }}
                </a-descriptions-item>
                <a-descriptions-item label="资源锚点">{{ latestWorkflow.resourceAnchors.join(' / ') }}</a-descriptions-item>
              </a-descriptions>
            </a-card>
          </a-col>
        </a-row>
      </a-space>
    </a-col>
  </a-row>
</template>
