<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import PageHeadActions from '../components/PageHeadActions.vue';
import { api } from '../services/api';
import { renderMarkdown } from '../utils/markdown';
import type {
  Assistant,
  PrivacyMappingSummary,
  PlaybookRun,
  RuntimeDraftMessage,
  RuntimeReplyDraftMessage,
  RuntimeUserDraftMessage,
  Scenario,
  SessionEvent,
  SessionMessage,
  SessionProgressEvent,
  SessionRuntimeDetail,
  SessionRuntimeSession,
} from '../types';

type RuntimeDetailPanel = 'session' | 'privacy' | 'progress' | 'events' | 'playbooks' | 'shared-state';
type ChatSide = 'left' | 'right' | 'center';

type ChatTimelineItem =
  | {
    kind: 'message';
    id: string;
    sortTime: string;
    side: ChatSide;
    message: SessionMessage;
  }
  | {
    kind: 'user-draft';
    id: string;
    sortTime: string;
    side: 'right';
    draft: RuntimeUserDraftMessage;
  }
  | {
    kind: 'reply-draft';
    id: string;
    sortTime: string;
    side: 'left';
    draft: RuntimeReplyDraftMessage;
  };

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
  startSession: [payload: { assistantId: string; customerId: string; openingMessage: string }];
  sendMessage: [payload: { sessionId: string; customerId: string; assistantId: string; message: string }];
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
const activePanel = ref<RuntimeDetailPanel | null>(null);
const messageScrollRef = ref<HTMLElement | null>(null);

const currentSession = computed(() =>
  props.sessions.find((item) => item.id === props.selectedSessionId) ?? props.sessions[0] ?? null,
);
const currentDetail = computed(() =>
  props.sessionDetail?.session.id === currentSession.value?.id ? props.sessionDetail : null,
);
const currentUserDrafts = computed(() =>
  currentSession.value
    ? props.runtimeDrafts.filter((draft): draft is RuntimeUserDraftMessage =>
      draft.draftType === 'USER' && draft.sessionId === currentSession.value?.id)
    : [],
);
const currentReplyDrafts = computed(() =>
  currentSession.value
    ? props.runtimeDrafts.filter((draft): draft is RuntimeReplyDraftMessage =>
      draft.draftType === 'REPLY' && draft.sessionId === currentSession.value?.id)
    : [],
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
const activePanelOpen = computed(() => activePanel.value !== null);
const activePanelTitle = computed(() => {
  switch (activePanel.value) {
    case 'session':
      return 'Session 状态';
    case 'privacy':
      return '隐私映射';
    case 'progress':
      return '实时进度';
    case 'events':
      return 'Session Events';
    case 'playbooks':
      return 'Playbook Runs';
    case 'shared-state':
      return '共享状态';
    default:
      return '';
  }
});

const chatTimeline = computed<ChatTimelineItem[]>(() => {
  const messages = (currentDetail.value?.messages ?? []).map((message): ChatTimelineItem => ({
    kind: 'message',
    id: message.messageId,
    sortTime: message.occurredAt ?? message.createdAt,
    side: messageSide(message),
    message,
  }));
  const userDrafts = currentUserDrafts.value.map((draft): ChatTimelineItem => ({
    kind: 'user-draft',
    id: `${draft.turnDedupKey}:${draft.clientMessageId}`,
    sortTime: draft.updatedAt,
    side: 'right',
    draft,
  }));
  const replyDrafts = currentReplyDrafts.value.map((draft): ChatTimelineItem => ({
    kind: 'reply-draft',
    id: `${draft.turnId}:${draft.replyMessageId}`,
    sortTime: draft.updatedAt,
    side: 'left',
    draft,
  }));
  return [...messages, ...userDrafts, ...replyDrafts].sort((left, right) =>
    left.sortTime.localeCompare(right.sortTime) || left.id.localeCompare(right.id),
  );
});

const statusPanelItems = computed(() => [
  {
    key: 'session' as const,
    marker: 'S',
    label: 'Session',
    value: currentSession.value?.status ?? '无会话',
    tone: currentSession.value?.draining ? 'danger' : 'default',
  },
  {
    key: 'privacy' as const,
    marker: '隐',
    label: '隐私',
    value: privacySummary.value?.enabled ? `${privacySummary.value.placeholderCount} placeholder` : '未开启',
    tone: privacySummary.value?.blockedEventCount ? 'warning' : 'default',
  },
  {
    key: 'progress' as const,
    marker: '进',
    label: '进度',
    value: currentProgress.value.at(-1)?.title ?? '暂无进度',
    tone: currentProgress.value.at(-1)?.status === 'FAILED' ? 'danger' : 'default',
  },
  {
    key: 'playbooks' as const,
    marker: 'P',
    label: 'Playbook',
    value: currentSession.value?.activePlaybookRunId ?? `${currentDetail.value?.playbookRuns.length ?? 0} 条记录`,
    tone: currentSession.value?.activePlaybookRunId ? 'active' : 'default',
  },
  {
    key: 'events' as const,
    marker: '事',
    label: '事件',
    value: `${currentSession.value?.latestEventSequence ?? 0} latest`,
    tone: 'default',
  },
  {
    key: 'shared-state' as const,
    marker: '{}',
    label: '状态',
    value: `${Object.keys(currentSession.value?.sharedState ?? {}).length} keys`,
    tone: 'default',
  },
]);

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
      activePanel.value = null;
      return;
    }
    privacySummary.value = await api.getRuntimeSessionPrivacyMappingSummary(sessionId);
  },
  { immediate: true },
);

watch(
  () => chatTimeline.value.map((item) => `${item.id}:${item.sortTime}`).join('|'),
  async () => {
    await nextTick();
    const container = messageScrollRef.value;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
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
  return '该助手还没有发布版本，当前不能启动 session。';
});

function submitCreate() {
  if (
    !createForm.assistantId
    || !createForm.customerId
    || !createForm.openingMessage.trim()
    || props.creatingSession
    || createAssistantBlockingMessage.value
  ) {
    return;
  }
  emit('startSession', {
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
    assistantId: currentSession.value.assistantId,
    message: messageDraft.value.trim(),
  });
  messageDraft.value = '';
}

function selectSession(sessionId: string) {
  emit('selectSession', sessionId);
}

function openPanel(panel: RuntimeDetailPanel) {
  if (!currentSession.value) {
    return;
  }
  activePanel.value = panel;
}

function closePanel() {
  activePanel.value = null;
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

function messageSide(message: SessionMessage): ChatSide {
  if (message.role === 'USER') {
    return 'right';
  }
  if (message.role === 'SYSTEM') {
    return 'center';
  }
  return 'left';
}

function playbookSummary(run: PlaybookRun) {
  const result = Object.keys(run.result ?? {}).length ? JSON.stringify(run.result, null, 2) : '无';
  return `${run.playbookId} · ${run.status}${run.waitingReason ? ` · ${run.waitingReason}` : ''}\n结果: ${result}`;
}

function formatSharedState(value: Record<string, unknown> | null | undefined) {
  return JSON.stringify(value ?? {}, null, 2);
}

function formatMessageTime(value: string | null | undefined) {
  return value ?? '无时间';
}
</script>

<template>
  <PageHeadActions>
    <a-button
      type="primary"
      :disabled="!currentCustomerId"
      @click="openCreateModal"
    >
      启动 Session
    </a-button>
  </PageHeadActions>

  <div class="runtime-im">
    <aside class="runtime-im__sessions">
      <div class="runtime-panel-head">
        <div>
          <div class="runtime-panel-head__label">Sessions</div>
          <strong>会话切换</strong>
        </div>
        <a-tag class="console-accent-tag">{{ sessions.length }}</a-tag>
      </div>

      <div v-if="sessions.length" class="runtime-session-list">
        <button
          v-for="item in sessions"
          :key="item.id"
          type="button"
          class="runtime-session-item"
          :class="{ 'runtime-session-item--active': currentSession?.id === item.id }"
          @click="selectSession(item.id)"
        >
          <span class="runtime-session-item__top">
            <strong>{{ item.title }}</strong>
            <a-tag :color="statusColor(item.status)">{{ item.status }}</a-tag>
          </span>
          <span class="runtime-session-item__meta">{{ item.assistantName }} · owner {{ item.currentOwnerAgentId }}</span>
          <span class="runtime-session-item__foot">{{ item.updatedAt }} · #{{ item.latestMessageSequence }}</span>
        </button>
      </div>
      <a-empty v-else class="runtime-empty" description="暂无 Session" />
    </aside>

    <main class="runtime-im__chat" :class="{ 'runtime-im__chat--empty': !currentSession }">
      <template v-if="currentSession">
        <header class="runtime-chat-head">
          <div class="runtime-chat-head__copy">
            <h2>{{ currentSession.title }}</h2>
            <div class="runtime-chat-head__meta">
              <span>{{ currentSession.assistantName }}</span>
              <span>{{ currentScenario?.name ?? currentSession.scenarioId }}</span>
              <span>customer {{ currentSession.customerId }}</span>
            </div>
          </div>
          <div class="runtime-chat-head__tags">
            <a-tag :color="statusColor(currentSession.status)">{{ currentSession.status }}</a-tag>
            <a-tag v-if="currentSession.sessionHumanHandoffActive" color="warning">HANDOFF</a-tag>
            <a-tag v-if="currentSession.draining" color="error">DRAINING</a-tag>
          </div>
        </header>

        <section ref="messageScrollRef" class="runtime-message-scroll" aria-label="会话消息记录">
          <div v-if="chatTimeline.length" class="runtime-message-list">
            <article
              v-for="item in chatTimeline"
              :key="item.id"
              class="runtime-chat-row"
              :class="[`runtime-chat-row--${item.side}`, { 'runtime-chat-row--draft-failed': item.kind !== 'message' && item.draft.failed }]"
            >
              <div v-if="item.kind === 'message' && item.side === 'center'" class="runtime-system-message">
                <span>{{ messageTitle(item.message) }}</span>
                <div class="runtime-system-message__body">
                  <template v-for="(block, index) in item.message.blocks" :key="`${item.message.messageId}-${index}`">
                    <span v-if="block.type === 'TEXT'">{{ block.text }}</span>
                    <span v-else-if="block.type === 'RICH_TEXT'" v-html="renderMarkdown(block.content)" />
                    <span v-else>{{ block.type }}</span>
                  </template>
                </div>
                <small>{{ formatMessageTime(item.message.createdAt) }}</small>
              </div>

              <div v-else class="runtime-chat-bubble">
                <div class="runtime-chat-bubble__meta">
                  <strong v-if="item.kind === 'message'">{{ messageTitle(item.message) }}</strong>
                  <strong v-else-if="item.kind === 'user-draft'">用户消息草稿</strong>
                  <strong v-else>助手回复中</strong>
                  <span v-if="item.kind === 'message'">
                    {{ formatMessageTime(item.message.createdAt) }} · {{ messageSender(item.message) }}
                  </span>
                  <span v-else>
                    {{ formatMessageTime(item.draft.updatedAt) }}{{ item.draft.failed ? ' · 失败' : ' · pending' }}
                  </span>
                </div>

                <div v-if="item.kind === 'message'" class="message-blocks">
                  <template v-for="(block, index) in item.message.blocks" :key="`${item.message.messageId}-${index}`">
                    <pre v-if="block.type === 'TEXT'" class="runtime-text-block">{{ block.text }}</pre>
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
                      <a-space v-if="block.actions?.length" wrap>
                        <a-button
                          v-for="(action, actionIndex) in block.actions"
                          :key="`${item.message.messageId}-${index}-${actionIndex}`"
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

                <div v-else-if="item.kind === 'user-draft'" class="message-blocks">
                  <template v-for="(block, index) in item.draft.blocks" :key="`${item.draft.clientMessageId}-${index}`">
                    <pre v-if="block.type === 'TEXT'" class="runtime-text-block">{{ block.text }}</pre>
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
                    </a-card>
                  </template>
                </div>

                <pre v-else class="runtime-text-block">{{ item.draft.text }}</pre>
              </div>
            </article>
          </div>
          <a-empty v-else class="runtime-empty" description="当前 Session 暂无消息" />
        </section>

        <footer class="runtime-composer">
          <a-textarea
            v-model:value="messageDraft"
            :rows="3"
            :disabled="isCurrentSessionSending"
            placeholder="输入用户消息"
          />
          <div class="runtime-composer__actions">
            <span>{{ currentSession.currentOwnerAgentId }} · {{ currentSession.latestMessageSequence }} messages</span>
            <a-button type="primary" :loading="isCurrentSessionSending" :disabled="!messageDraft.trim()" @click="submitMessage">
              {{ isCurrentSessionSending ? '发送中...' : '发送' }}
            </a-button>
          </div>
        </footer>
      </template>

      <a-empty v-else description="选择或启动一个 Session 后开始对话" />
    </main>

    <aside class="runtime-im__status">
      <div class="runtime-panel-head runtime-panel-head--compact">
        <div>
          <div class="runtime-panel-head__label">Status</div>
          <strong>运行状态</strong>
        </div>
      </div>

      <div class="runtime-status-list">
        <button
          v-for="item in statusPanelItems"
          :key="item.key"
          type="button"
          class="runtime-status-button"
          :class="[`runtime-status-button--${item.tone}`]"
          :disabled="!currentSession"
          @click="openPanel(item.key)"
        >
          <span class="runtime-status-button__icon">{{ item.marker }}</span>
          <span class="runtime-status-button__copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.value }}</small>
          </span>
        </button>
      </div>
    </aside>
  </div>

  <a-drawer
    :open="activePanelOpen"
    :title="activePanelTitle"
    width="520px"
    placement="right"
    @close="closePanel"
  >
    <template v-if="currentSession">
      <a-descriptions v-if="activePanel === 'session'" :column="1" bordered size="small">
        <a-descriptions-item label="状态">{{ currentSession.status }}</a-descriptions-item>
        <a-descriptions-item label="当前 Owner">{{ currentSession.currentOwnerAgentId }}</a-descriptions-item>
        <a-descriptions-item label="Primary Agent">{{ currentSession.primaryAgentId }}</a-descriptions-item>
        <a-descriptions-item label="Active Playbook">{{ currentSession.activePlaybookRunId ?? '无' }}</a-descriptions-item>
        <a-descriptions-item label="Idle Deadline">{{ currentSession.idleDeadline ?? '无' }}</a-descriptions-item>
        <a-descriptions-item label="最新消息序号">{{ currentSession.latestMessageSequence }}</a-descriptions-item>
        <a-descriptions-item label="最新事件序号">{{ currentSession.latestEventSequence }}</a-descriptions-item>
        <a-descriptions-item label="创建时间">{{ currentSession.createdAt }}</a-descriptions-item>
        <a-descriptions-item label="更新时间">{{ currentSession.updatedAt }}</a-descriptions-item>
      </a-descriptions>

      <a-descriptions v-else-if="activePanel === 'privacy'" :column="1" bordered size="small">
        <a-descriptions-item label="是否开启">{{ privacySummary?.enabled ? '开启' : '关闭' }}</a-descriptions-item>
        <a-descriptions-item label="映射模型">{{ privacySummary?.privacyModelName ?? '未配置' }}</a-descriptions-item>
        <a-descriptions-item label="Placeholder 总量">{{ privacySummary?.placeholderCount ?? 0 }}</a-descriptions-item>
        <a-descriptions-item label="阻断次数">{{ privacySummary?.blockedEventCount ?? 0 }}</a-descriptions-item>
        <a-descriptions-item label="未解析占位符">{{ privacySummary?.unresolvedPlaceholderCount ?? 0 }}</a-descriptions-item>
        <a-descriptions-item label="最近处理时间">{{ privacySummary?.lastProcessedAt ?? '无' }}</a-descriptions-item>
        <a-descriptions-item label="实体分布">
          <pre class="runtime-json">{{ JSON.stringify(privacySummary?.entityTypeBreakdown ?? {}, null, 2) }}</pre>
        </a-descriptions-item>
      </a-descriptions>

      <a-timeline v-else-if="activePanel === 'progress' && currentProgress.length">
        <a-timeline-item
          v-for="event in currentProgress"
          :key="event.id"
          :color="event.status === 'FAILED' ? 'red' : event.status === 'SUCCEEDED' ? 'green' : 'blue'"
        >
          <div class="timeline-title">
            <strong>{{ event.title }}</strong>
          </div>
          <div class="timeline-meta">{{ event.occurredAt }} · {{ event.phase }}</div>
          <pre v-if="event.detail" class="runtime-json">{{ JSON.stringify(event.detail, null, 2) }}</pre>
        </a-timeline-item>
      </a-timeline>
      <a-empty v-else-if="activePanel === 'progress'" description="暂无实时进度" />

      <a-timeline v-else-if="activePanel === 'events' && currentDetail?.events.length">
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
      <a-empty v-else-if="activePanel === 'events'" description="暂无事件" />

      <a-list v-else-if="activePanel === 'playbooks' && currentDetail?.playbookRuns.length" :data-source="currentDetail.playbookRuns">
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
      <a-empty v-else-if="activePanel === 'playbooks'" description="暂无 Playbook Run" />

      <pre v-else-if="activePanel === 'shared-state'" class="runtime-json runtime-json--panel">{{ formatSharedState(currentSession.sharedState) }}</pre>
    </template>
  </a-drawer>

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
              :disabled="!createForm.assistantId || !createForm.customerId || !createForm.openingMessage.trim() || !!createAssistantBlockingMessage"
              @click="submitCreate"
            >
              {{ creatingSession ? '正在启动...' : '发送并启动' }}
            </a-button>
          </div>
        </a-form>
      </div>
    </div>
  </a-modal>
</template>

<style scoped>
.runtime-im {
  display: grid;
  grid-template-columns: 280px minmax(480px, 1fr) 188px;
  gap: 14px;
  min-height: calc(100vh - 196px);
  height: calc(100vh - 196px);
  min-width: 0;
}

.runtime-im__sessions,
.runtime-im__chat,
.runtime-im__status {
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: rgba(252, 251, 248, 0.88);
  box-shadow: var(--shadow-sm);
}

.runtime-im__sessions,
.runtime-im__status {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.runtime-im__chat {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.54);
}

.runtime-im__chat--empty {
  align-items: center;
  justify-content: center;
}

.runtime-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 58px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}

.runtime-panel-head--compact {
  min-height: 56px;
}

.runtime-panel-head strong {
  display: block;
  color: var(--ink);
  font-size: var(--text-body);
}

.runtime-panel-head__label {
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.runtime-session-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.runtime-session-item {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease;
}

.runtime-session-item:hover,
.runtime-session-item--active {
  border-color: var(--line);
  background: rgba(255, 255, 255, 0.78);
}

.runtime-session-item--active {
  border-left: 2px solid var(--accent);
}

.runtime-session-item__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.runtime-session-item__top strong {
  min-width: 0;
  color: var(--ink);
  font-size: var(--text-secondary);
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.runtime-session-item__meta,
.runtime-session-item__foot {
  color: var(--ink-faint);
  font-size: var(--text-caption);
  overflow-wrap: anywhere;
}

.runtime-session-item__foot {
  font-family: var(--font-mono);
}

.runtime-chat-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
  background: rgba(252, 251, 248, 0.86);
}

.runtime-chat-head__copy {
  min-width: 0;
}

.runtime-chat-head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0;
}

.runtime-chat-head__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  color: var(--ink-faint);
  font-size: var(--text-caption);
}

.runtime-chat-head__meta span + span::before {
  content: '/';
  margin-right: 8px;
  color: var(--ink-ghost);
}

.runtime-chat-head__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.runtime-message-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
}

.runtime-message-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.runtime-chat-row {
  display: flex;
  width: 100%;
}

.runtime-chat-row--left {
  justify-content: flex-start;
}

.runtime-chat-row--right {
  justify-content: flex-end;
}

.runtime-chat-row--center {
  justify-content: center;
}

.runtime-chat-bubble {
  width: fit-content;
  max-width: min(72%, 760px);
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: rgba(252, 251, 248, 0.94);
}

.runtime-chat-row--right .runtime-chat-bubble {
  border-color: var(--accent-line);
  background: color-mix(in srgb, var(--accent-tint) 52%, white);
}

.runtime-chat-row--draft-failed .runtime-chat-bubble {
  border-color: rgba(191, 77, 57, 0.34);
  background: var(--err-tint);
}

.runtime-chat-bubble__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--ink-faint);
  font-size: var(--text-caption);
}

.runtime-chat-bubble__meta strong {
  color: var(--ink-soft);
  font-weight: 600;
}

.runtime-system-message {
  width: min(72%, 640px);
  padding: 8px 12px;
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
  background: rgba(238, 235, 229, 0.66);
  color: var(--ink-soft);
  text-align: center;
}

.runtime-system-message span,
.runtime-system-message small {
  display: block;
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.runtime-system-message__body {
  margin: 4px 0;
  overflow-wrap: anywhere;
}

.runtime-composer {
  border-top: 1px solid var(--line);
  padding: 12px 14px;
  background: rgba(252, 251, 248, 0.92);
}

.runtime-composer :deep(.ant-input) {
  min-height: 72px;
  resize: vertical;
}

.runtime-composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.runtime-composer__actions span {
  min-width: 0;
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  overflow-wrap: anywhere;
}

.runtime-status-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.runtime-status-button {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 56px;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: rgba(255, 255, 255, 0.58);
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease;
}

.runtime-status-button:hover:not(:disabled),
.runtime-status-button--active {
  border-color: var(--accent-line);
  background: rgba(255, 255, 255, 0.88);
}

.runtime-status-button:disabled {
  cursor: not-allowed;
  opacity: 0.52;
}

.runtime-status-button--warning {
  border-color: rgba(186, 124, 34, 0.28);
}

.runtime-status-button--danger {
  border-color: rgba(191, 77, 57, 0.28);
}

.runtime-status-button__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: rgba(252, 251, 248, 0.92);
  color: var(--accent-ink);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  font-weight: 700;
}

.runtime-status-button__copy {
  min-width: 0;
}

.runtime-status-button__copy strong,
.runtime-status-button__copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-status-button__copy strong {
  color: var(--ink);
  font-size: var(--text-secondary);
}

.runtime-status-button__copy small {
  color: var(--ink-faint);
  font-size: var(--text-caption);
}

.timeline-title {
  margin-bottom: 4px;
}

.timeline-meta {
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 8px;
}

.runtime-json,
.runtime-text-block {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.runtime-json--panel {
  padding: 12px;
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
  background: rgba(255, 255, 255, 0.72);
}

.message-blocks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.runtime-message-image {
  max-width: 100%;
  border-radius: var(--r-sm);
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

.runtime-markdown :deep(p:last-child),
.runtime-markdown :deep(ul:last-child),
.runtime-markdown :deep(ol:last-child),
.runtime-markdown :deep(pre:last-child) {
  margin-bottom: 0;
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
  border-radius: var(--r-sm);
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

.runtime-empty {
  margin: auto;
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

@media (max-width: 1280px) {
  .runtime-im {
    grid-template-columns: 240px minmax(420px, 1fr) 160px;
  }
}

@media (max-width: 1024px) {
  .runtime-im {
    grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
    height: auto;
    min-height: calc(100vh - 196px);
  }

  .runtime-im__status {
    grid-column: 1 / -1;
    min-height: 0;
  }

  .runtime-status-list {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
