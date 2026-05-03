<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import PageHeadActions from '../components/PageHeadActions.vue';
import { api } from '../services/api';
import { renderMarkdown } from '../utils/markdown';
import type {
  Assistant,
  PrivacyMappingSummary,
  PlaybookRun,
  RuntimeDraftMessage,
  Scenario,
  SessionEvent,
  SessionMessage,
  SessionProgressEvent,
  SessionRuntimeDetail,
  SessionRuntimeSession,
} from '../types';

const props = defineProps<{
  scenarios: Scenario[];
  assistants: Assistant[];
  sessions: SessionRuntimeSession[];
  sessionDetail: SessionRuntimeDetail | null;
  runtimeDrafts: RuntimeDraftMessage[];
  runtimeProgress: SessionProgressEvent[];
  creatingSession: boolean;
  sendingSessionId: string | null;
  preferredSessionId: string | null;
  selectedSessionId: string | null;
  currentCustomerId: string | null;
}>();

const emit = defineEmits<{
  selectSession: [sessionId: string];
  createSession: [payload: { assistantId: string; customerId: string; openingMessage: string }];
  sendMessage: [payload: { sessionId: string; customerId: string; message: string }];
}>();

const createForm = reactive({
  scenarioId: '',
  assistantId: '',
  customerId: '',
  openingMessage: '',
});
const createModalOpen = ref(false);
const messageDraft = ref('');
const privacySummary = ref<PrivacyMappingSummary | null>(null);

const currentSession = computed(() =>
  props.sessions.find((item) => item.id === props.selectedSessionId) ?? props.sessions[0] ?? null,
);
const currentDetail = computed(() =>
  props.sessionDetail?.session.id === currentSession.value?.id ? props.sessionDetail : null,
);
const currentDrafts = computed(() =>
  currentSession.value ? props.runtimeDrafts.filter((draft) => draft.sessionId === currentSession.value?.id) : [],
);
const currentProgress = computed(() =>
  currentSession.value ? props.runtimeProgress.filter((event) => event.sessionId === currentSession.value?.id).slice(-12) : [],
);
const currentScenario = computed(() =>
  props.scenarios.find((item) => item.id === currentSession.value?.scenarioId) ?? null,
);
const isCurrentSessionSending = computed(() => props.sendingSessionId === currentSession.value?.id);
const availableAssistants = computed(() =>
  props.scenarios.find((item) => item.id === createForm.scenarioId)?.assistants ?? [],
);
const createSelectedAssistant = computed(() =>
  props.assistants.find((item) => item.id === createForm.assistantId) ?? null,
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
    createForm.assistantId = props.scenarios.find((item) => item.id === scenarioId)?.assistants[0]?.id ?? '';
  },
);

watch(
  () => props.preferredSessionId,
  (sessionId) => {
    if (sessionId && props.sessions.some((item) => item.id === sessionId) && sessionId !== props.selectedSessionId) {
      emit('selectSession', sessionId);
    }
  },
  { immediate: true },
);

watch(
  () => currentSession.value?.id,
  async (sessionId) => {
    if (!sessionId) {
      privacySummary.value = null;
      return;
    }
    privacySummary.value = await api.getRuntimeSessionPrivacyMappingSummary(sessionId);
  },
  { immediate: true },
);

function canRunAssistant(assistant?: Assistant | null) {
  return !!assistant?.currentRelease;
}

const createAssistantBlockingMessage = computed(() => {
  if (!createSelectedAssistant.value || canRunAssistant(createSelectedAssistant.value)) {
    return null;
  }
  return '该助手还没有发布版本，当前不能创建 session。';
});

function submitCreate() {
  if (!createForm.assistantId || !createForm.customerId || props.creatingSession || createAssistantBlockingMessage.value) {
    return;
  }
  emit('createSession', {
    assistantId: createForm.assistantId,
    customerId: createForm.customerId,
    openingMessage: createForm.openingMessage.trim(),
  });
  createModalOpen.value = false;
  createForm.openingMessage = '';
}

function openCreateModal() {
  createForm.customerId = props.currentCustomerId ?? '';
  createForm.openingMessage = '';
  if (!createForm.scenarioId && props.scenarios.length > 0) {
    createForm.scenarioId = props.scenarios[0].id;
  }
  createModalOpen.value = true;
}

function closeCreateModal() {
  createModalOpen.value = false;
  createForm.openingMessage = '';
}

function generateDebugCustomerId() {
  const baseId = (props.currentCustomerId || 'debug-customer').replace(/[^a-zA-Z0-9_-]/g, '-');
  const timestamp = new Date()
    .toISOString()
    .replace(/[-:TZ.]/g, '')
    .slice(0, 14);
  createForm.customerId = `${baseId}-debug-${timestamp}`;
}

function submitMessage() {
  if (!currentSession.value || !messageDraft.value.trim() || isCurrentSessionSending.value) {
    return;
  }
  emit('sendMessage', {
    sessionId: currentSession.value.id,
    customerId: currentSession.value.customerId,
    message: messageDraft.value.trim(),
  });
  messageDraft.value = '';
}

function selectSession(sessionId: string) {
  emit('selectSession', sessionId);
}

function statusColor(status: string) {
  switch (status) {
    case 'ACTIVE':
    case 'RUNNING':
      return 'processing';
    case 'WAITING':
      return 'warning';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
    case 'CANCELLED':
    case 'DRAINING':
      return 'error';
    default:
      return 'default';
  }
}

function eventTitle(event: SessionEvent) {
  switch (event.eventType) {
    case 'OWNER_SWITCH':
      return 'Owner 切换';
    case 'PLAYBOOK_STARTED':
      return 'Playbook 启动';
    case 'PLAYBOOK_WAITING':
      return 'Playbook 等待';
    case 'PLAYBOOK_COMPLETED':
      return 'Playbook 完成';
    case 'SESSION_HUMAN_HANDOFF_STARTED':
      return '人工接管开始';
    case 'SESSION_HUMAN_HANDOFF_ENDED':
      return '人工接管结束';
    case 'AGENT_DECISION_REJECTED':
      return '决策被拒绝';
    case 'AGENT_TURN_FAILED':
      return 'Agent Turn 失败';
    case 'USER_MESSAGE_SECURITY_BLOCKED':
      return '安全拦截';
    default:
      return event.eventType;
  }
}

function eventSummary(event: SessionEvent) {
  const reason = event.payload?.reason;
  if (typeof reason === 'string' && reason.trim()) {
    return reason;
  }
  return JSON.stringify(event.payload ?? {}, null, 2);
}

function messageTitle(message: SessionMessage) {
  switch (message.role) {
    case 'USER':
      return '用户消息';
    case 'ASSISTANT':
      return '助手回复';
    case 'HUMAN_OPERATOR':
      return '人工回复';
    case 'SYSTEM':
      return '系统消息';
    default:
      return message.role;
  }
}

function messageSender(message: SessionMessage) {
  return message.sender.senderName ?? message.sender.senderId ?? message.sender.senderType;
}

function playbookSummary(run: PlaybookRun) {
  const result = Object.keys(run.result ?? {}).length ? JSON.stringify(run.result, null, 2) : '无';
  return `${run.playbookId} · ${run.status}${run.waitingReason ? ` · ${run.waitingReason}` : ''}\n结果: ${result}`;
}

function formatSharedState(value: Record<string, unknown> | null | undefined) {
  return JSON.stringify(value ?? {}, null, 2);
}
</script>

<template>
  <PageHeadActions>
    <a-button
      type="primary"
      :disabled="!currentCustomerId"
      @click="openCreateModal"
    >
      新建 Session
    </a-button>
  </PageHeadActions>

  <a-row :gutter="[16, 16]">
    <a-col :span="7">
      <a-card title="Session 列表">
        <template #extra>
          <a-tag class="console-accent-tag">{{ sessions.length }} 个会话</a-tag>
        </template>
        <a-list :data-source="sessions">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': props.selectedSessionId === item.id }"
              @click="selectSession(item.id)"
            >
              <a-list-item-meta
                :title="item.title"
                :description="`${item.assistantName} · ${item.status} · owner ${item.currentOwnerAgentId}`"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="17">
      <a-space direction="vertical" size="large" style="width: 100%">
        <a-card v-if="currentSession" :title="currentSession.title">
          <template #extra>
            <a-space>
              <a-tag class="console-accent-tag">{{ currentSession.assistantName }}</a-tag>
              <a-tag>{{ currentScenario?.name ?? currentSession.scenarioId }}</a-tag>
              <a-tag :color="statusColor(currentSession.status)">{{ currentSession.status }}</a-tag>
              <a-tag v-if="currentSession.sessionHumanHandoffActive" color="warning">HANDOFF</a-tag>
              <a-tag v-if="currentSession.draining" color="error">DRAINING</a-tag>
            </a-space>
          </template>

          <a-descriptions :column="2" bordered size="small">
            <a-descriptions-item label="当前 Owner">{{ currentSession.currentOwnerAgentId }}</a-descriptions-item>
            <a-descriptions-item label="Primary Agent">{{ currentSession.primaryAgentId }}</a-descriptions-item>
            <a-descriptions-item label="Active Playbook">{{ currentSession.activePlaybookRunId ?? '无' }}</a-descriptions-item>
            <a-descriptions-item label="Idle Deadline">{{ currentSession.idleDeadline ?? '无' }}</a-descriptions-item>
            <a-descriptions-item label="最新消息序号">{{ currentSession.latestMessageSequence }}</a-descriptions-item>
            <a-descriptions-item label="最新事件序号">{{ currentSession.latestEventSequence }}</a-descriptions-item>
            <a-descriptions-item label="共享状态">
              <pre class="runtime-json">{{ formatSharedState(currentSession.sharedState) }}</pre>
            </a-descriptions-item>
          </a-descriptions>

          <a-form layout="vertical" style="margin-top: 16px">
            <a-form-item label="发送消息">
              <a-textarea
                v-model:value="messageDraft"
                :rows="4"
                :disabled="isCurrentSessionSending"
                placeholder="输入用户消息"
              />
            </a-form-item>
            <a-button type="primary" :loading="isCurrentSessionSending" @click="submitMessage">
              {{ isCurrentSessionSending ? '发送中...' : '发送消息' }}
            </a-button>
          </a-form>
        </a-card>

        <a-card v-if="currentSession" title="隐私映射">
          <a-descriptions :column="2" bordered size="small">
            <a-descriptions-item label="是否开启">{{ privacySummary?.enabled ? '开启' : '关闭' }}</a-descriptions-item>
            <a-descriptions-item label="映射模型">{{ privacySummary?.privacyModelName ?? '未配置' }}</a-descriptions-item>
            <a-descriptions-item label="Placeholder 总量">{{ privacySummary?.placeholderCount ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="阻断次数">{{ privacySummary?.blockedEventCount ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="未解析占位符">{{ privacySummary?.unresolvedPlaceholderCount ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="最近处理时间">
              {{ privacySummary?.lastProcessedAt ?? '无' }}
            </a-descriptions-item>
            <a-descriptions-item label="实体分布" :span="2">
              <pre class="runtime-json">{{ JSON.stringify(privacySummary?.entityTypeBreakdown ?? {}, null, 2) }}</pre>
            </a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card v-if="currentDetail" title="Messages">
          <a-list :data-source="currentDetail.messages">
            <template #renderItem="{ item }">
              <a-list-item>
                <div style="width: 100%">
                  <div class="timeline-title">
                    <strong>#{{ item.sequence }} {{ messageTitle(item) }}</strong>
                  </div>
                  <div class="timeline-meta">
                    {{ item.createdAt }} · {{ messageSender(item) }}{{ item.relatedOwnerAgentId ? ` · owner ${item.relatedOwnerAgentId}` : '' }}
                  </div>
                  <div class="message-blocks">
                    <template v-for="(block, index) in item.blocks" :key="`${item.messageId}-${index}`">
                      <pre v-if="block.type === 'TEXT'" class="runtime-json">{{ block.text }}</pre>
                      <img
                        v-else-if="block.type === 'IMAGE'"
                        :src="block.url"
                        :alt="block.alt ?? 'image'"
                        class="runtime-message-image"
                      />
                      <div v-else-if="block.type === 'RICH_TEXT'" class="runtime-markdown" v-html="renderMarkdown(block.content)" />
                      <a-card v-else-if="block.type === 'CARD'" size="small" class="runtime-message-card">
                        <template #title>{{ block.cardType }} · {{ block.version }}</template>
                        <pre class="runtime-json">{{ JSON.stringify(block.data ?? {}, null, 2) }}</pre>
                        <a-space v-if="block.actions?.length">
                          <a-button
                            v-for="(action, actionIndex) in block.actions"
                            :key="`${item.messageId}-${index}-${actionIndex}`"
                            type="link"
                            :href="action.url"
                            target="_blank"
                          >
                            {{ action.label }}
                          </a-button>
                        </a-space>
                      </a-card>
                    </template>
                  </div>
                </div>
              </a-list-item>
            </template>
          </a-list>
          <div v-if="currentDrafts.length" class="runtime-drafts">
            <div
              v-for="draft in currentDrafts"
              :key="draft.messageId"
              class="runtime-draft"
              :class="{ 'runtime-draft--failed': draft.failed }"
            >
              <div class="timeline-title">
                <strong>助手回复草稿</strong>
              </div>
              <div class="timeline-meta">{{ draft.updatedAt }}</div>
              <pre class="runtime-json">{{ draft.text }}</pre>
            </div>
          </div>
        </a-card>

        <a-card v-if="currentProgress.length" title="实时进度">
          <a-timeline>
            <a-timeline-item
              v-for="event in currentProgress"
              :key="event.id"
              :color="event.status === 'FAILED' ? 'red' : event.status === 'SUCCEEDED' ? 'green' : 'blue'"
            >
              <div class="timeline-title">
                <strong>{{ event.title }}</strong>
              </div>
              <div class="timeline-meta">{{ event.occurredAt }} · {{ event.phase }}</div>
            </a-timeline-item>
          </a-timeline>
        </a-card>

        <a-card v-if="currentDetail" title="Session Events">
          <a-timeline>
            <a-timeline-item v-for="event in currentDetail.events" :key="event.eventId" :color="statusColor(event.eventType)">
              <div class="timeline-title">
                <strong>#{{ event.sequence }} {{ eventTitle(event) }}</strong>
              </div>
              <div class="timeline-meta">
                {{ event.createdAt }} · {{ event.actorType }}{{ event.relatedOwnerAgentId ? ` · owner ${event.relatedOwnerAgentId}` : '' }}
              </div>
              <pre class="runtime-json">{{ eventSummary(event) }}</pre>
            </a-timeline-item>
          </a-timeline>
        </a-card>

        <a-card v-if="currentDetail" title="Playbook Runs">
          <a-list :data-source="currentDetail.playbookRuns">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta
                  :title="`${item.playbookId} · ${item.runId}`"
                  :description="playbookSummary(item)"
                />
                <template #actions>
                  <a-tag :color="statusColor(item.status)">{{ item.status }}</a-tag>
                </template>
              </a-list-item>
            </template>
          </a-list>
        </a-card>

        <a-empty v-if="!currentSession" description="暂无 Session" />
      </a-space>
    </a-col>
  </a-row>

  <a-modal
    :open="createModalOpen"
    :footer="null"
    width="560px"
    @cancel="closeCreateModal"
  >
    <div class="create-modal">
      <div class="create-modal__kicker">05.01 / 会话运行 / Session</div>
      <div class="create-modal__body">
        <a-form layout="vertical" :model="createForm">
          <a-form-item label="业务场景">
            <a-select
              v-model:value="createForm.scenarioId"
              :disabled="creatingSession"
              :options="scenarios.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="助手">
            <a-select
              v-model:value="createForm.assistantId"
              :disabled="creatingSession"
              :options="availableAssistants.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="客户 ID">
            <div class="customer-id-row">
              <a-input
                v-model:value="createForm.customerId"
                :disabled="creatingSession"
                allow-clear
              />
              <a-button :disabled="creatingSession" @click="generateDebugCustomerId">
                生成
              </a-button>
            </div>
          </a-form-item>
          <a-form-item label="开场消息">
            <a-textarea
              v-model:value="createForm.openingMessage"
              :rows="4"
              :disabled="creatingSession"
              placeholder="例如：帮我查一下最近这笔订单为什么还没发货。"
            />
          </a-form-item>
          <a-alert
            v-if="createAssistantBlockingMessage"
            type="warning"
            show-icon
            :message="createAssistantBlockingMessage"
          />
          <div class="create-modal__actions">
            <a-button @click="closeCreateModal">取消</a-button>
            <a-button
              type="primary"
              :loading="creatingSession"
              :disabled="!createForm.assistantId || !createForm.customerId || !!createAssistantBlockingMessage"
              @click="submitCreate"
            >
              {{ creatingSession ? '正在创建...' : '创建 Session' }}
            </a-button>
          </div>
        </a-form>
      </div>
    </div>
  </a-modal>
</template>

<style scoped>
.timeline-title {
  margin-bottom: 4px;
}

.timeline-meta {
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 8px;
}

.runtime-json {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-blocks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.runtime-drafts {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 16px;
}

.runtime-draft {
  padding: 12px;
  border: 1px solid rgba(22, 119, 255, 0.2);
  border-radius: 8px;
  background: rgba(22, 119, 255, 0.04);
}

.runtime-draft--failed {
  border-color: rgba(255, 77, 79, 0.3);
  background: rgba(255, 77, 79, 0.04);
}

.runtime-message-image {
  max-width: 100%;
  border-radius: 8px;
  border: 1px solid rgba(5, 5, 5, 0.08);
}

.runtime-markdown :deep(h1),
.runtime-markdown :deep(h2),
.runtime-markdown :deep(h3) {
  margin: 0 0 8px;
}

.runtime-markdown :deep(p),
.runtime-markdown :deep(ul),
.runtime-markdown :deep(ol),
.runtime-markdown :deep(blockquote),
.runtime-markdown :deep(pre),
.runtime-markdown :deep(table) {
  margin: 0 0 12px;
}

.runtime-markdown :deep(ul),
.runtime-markdown :deep(ol) {
  padding-left: 20px;
}

.runtime-markdown :deep(blockquote) {
  padding-left: 12px;
  border-left: 3px solid rgba(5, 5, 5, 0.12);
  color: rgba(0, 0, 0, 0.65);
}

.runtime-markdown :deep(pre) {
  padding: 12px;
  overflow-x: auto;
  border-radius: 8px;
  background: #fafafa;
  border: 1px solid rgba(5, 5, 5, 0.06);
}

.runtime-markdown :deep(code) {
  font-family: 'SFMono-Regular', 'Consolas', monospace;
}

.runtime-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
}

.runtime-markdown :deep(th),
.runtime-markdown :deep(td) {
  padding: 8px 10px;
  border: 1px solid rgba(5, 5, 5, 0.08);
  text-align: left;
}

.runtime-message-card {
  width: 100%;
}

.customer-id-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.customer-id-row :deep(.ant-input-affix-wrapper),
.customer-id-row :deep(.ant-btn) {
  height: 40px;
}

.customer-id-row :deep(.ant-input-affix-wrapper) {
  align-items: center;
}

.customer-id-row :deep(.ant-input-affix-wrapper .ant-input),
.customer-id-row :deep(.ant-input-affix-wrapper .ant-input:hover),
.customer-id-row :deep(.ant-input-affix-wrapper .ant-input:focus) {
  min-height: 0 !important;
  height: 22px !important;
  line-height: 22px !important;
  padding: 0 !important;
  border: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  background: transparent !important;
}

.customer-id-row :deep(.ant-input-clear-icon) {
  display: inline-flex;
  align-items: center;
}

.customer-id-row :deep(.ant-btn) {
  padding-inline: 18px;
}
</style>
