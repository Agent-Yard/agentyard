<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type { ConversationSession, Scenario, TaskInstance, WorkflowInstance } from '../types';

const props = defineProps<{
  scenarios: Scenario[];
  assistants: Scenario['assistants'];
  sessions: ConversationSession[];
  tasks: TaskInstance[];
  workflows: WorkflowInstance[];
}>();

const emit = defineEmits<{
  createSession: [payload: { scenarioId: string; assistantId: string; requester: string; openingMessage: string }];
  sendMessage: [payload: { sessionId: string; requester: string; message: string }];
}>();

const selectedSessionId = ref('');
const createForm = reactive({
  scenarioId: '',
  assistantId: '',
  requester: '业务用户A',
  openingMessage: '',
});
const messageDraft = ref('');

const currentSession = computed(() =>
  props.sessions.find((item) => item.id === selectedSessionId.value) ?? props.sessions[0],
);

const currentScenario = computed(() =>
  props.scenarios.find((item) => item.id === currentSession.value?.scenarioId),
);

const availableAssistants = computed(() => currentScenario.value?.assistants ?? []);
const latestWorkflow = computed(() =>
  props.workflows.find((item) => item.id === currentSession.value?.latestWorkflowInstanceId),
);
const latestTask = computed(() =>
  props.tasks.find((item) => item.id === currentSession.value?.latestTaskId),
);

watch(
  () => props.sessions,
  (sessions) => {
    if (!sessions.length) {
      selectedSessionId.value = '';
      return;
    }

    if (!sessions.some((item) => item.id === selectedSessionId.value)) {
      selectedSessionId.value = sessions[0].id;
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
  () => createForm.scenarioId,
  (scenarioId) => {
    const scenario = props.scenarios.find((item) => item.id === scenarioId);
    createForm.assistantId = scenario?.assistants[0]?.id ?? '';
  },
);

function submitCreate() {
  emit('createSession', { ...createForm });
  createForm.openingMessage = '';
}

function submitMessage() {
  if (!currentSession.value || !messageDraft.value.trim()) {
    return;
  }

  emit('sendMessage', {
    sessionId: currentSession.value.id,
    requester: currentSession.value.requester,
    message: messageDraft.value,
  });
  messageDraft.value = '';
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="7">
      <a-card title="新建会话">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="业务场景">
            <a-select
              v-model:value="createForm.scenarioId"
              :options="scenarios.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="会话助手">
            <a-select
              v-model:value="createForm.assistantId"
              :options="(scenarios.find((item) => item.id === createForm.scenarioId)?.assistants ?? []).map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="发起人">
            <a-input v-model:value="createForm.requester" />
          </a-form-item>
          <a-form-item label="开场问题">
            <a-textarea v-model:value="createForm.openingMessage" :rows="4" />
          </a-form-item>
          <a-button type="primary" html-type="submit">创建并开始对话</a-button>
        </a-form>
      </a-card>

      <a-card title="会话列表">
        <a-list :data-source="sessions">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedSessionId === item.id }"
              @click="selectedSessionId = item.id"
            >
              <a-list-item-meta
                :title="item.title"
                :description="`${item.assistantName} · ${item.messages.length} 条消息`"
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
            </a-space>
          </template>

          <a-row :gutter="[16, 16]">
            <a-col :span="16">
              <div class="conversation-board">
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
                  <p>{{ message.content }}</p>
                  <span v-if="message.workflowInstanceId" class="conversation-bubble__meta">
                    workflow: {{ message.workflowInstanceId }}
                  </span>
                </div>
              </div>

              <a-form layout="vertical" @finish="submitMessage">
                <a-form-item label="继续对话">
                  <a-textarea
                    v-model:value="messageDraft"
                    :rows="4"
                    placeholder="继续输入问题，消息会持续交给当前会话选择的助手处理"
                  />
                </a-form-item>
                <a-button type="primary" html-type="submit">发送消息</a-button>
              </a-form>
            </a-col>
            <a-col :span="8">
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
                <a-descriptions-item label="摘要">{{ latestWorkflow.summary }}</a-descriptions-item>
                <a-descriptions-item label="MCP 摘要">
                  {{ currentSession?.latestMcpSummary?.externalTicketId
                    ? `${currentSession.latestMcpSummary.capabilityName} / ${currentSession.latestMcpSummary.externalTicketId} / ${currentSession.latestMcpSummary.recommendedAction}`
                    : '当前无 MCP 调用记录' }}
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
