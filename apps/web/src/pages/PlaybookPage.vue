<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import ObjectHistoryPanel from '../components/ObjectHistoryPanel.vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import { pageMeta } from '../config/navigation';
import type {
  Assistant,
  CreatePlaybookPayload,
  PlaybookExecutionPolicy,
  UpdatePlaybookPayload,
} from '../types';
import { createStarterPlaybookGraph, serializeEditorSnapshot } from './playbookEditor';

const props = defineProps<{
  assistants: Assistant[];
  catalogRevision: number;
  canManageGovernance: boolean;
  preferredAssistantId?: string | null;
  preferredPlaybookId?: string | null;
}>();

const emit = defineEmits<{
  createPlaybook: [payload: CreatePlaybookPayload];
  savePlaybook: [payload: { playbookId: string; playbook: UpdatePlaybookPayload }];
  deletePlaybook: [playbookId: string];
}>();

const router = useRouter();

const selectedAssistantId = ref('');
const selectedPlaybookId = ref('');
const createDrawerOpen = ref(false);

const createForm = reactive({
  assistantId: '',
  name: '',
  description: '',
  inputSchema: '',
  resultSchema: '',
  executionPolicy: {
    timeoutPolicy: null,
    retryPolicy: null,
  } as PlaybookExecutionPolicy,
  allowHumanTask: true,
  allowExternalInteraction: true,
});

const editForm = reactive({
  name: '',
  description: '',
  inputSchema: '',
  resultSchema: '',
  executionPolicy: {
    timeoutPolicy: null,
    retryPolicy: null,
  } as PlaybookExecutionPolicy,
  allowHumanTask: true,
  allowExternalInteraction: true,
});

const currentAssistant = computed(() =>
  props.assistants.find((item) => item.id === selectedAssistantId.value) ?? props.assistants[0] ?? null,
);
const assistantPlaybooks = computed(() => currentAssistant.value?.playbooks ?? []);
const currentPlaybook = computed(() =>
  assistantPlaybooks.value.find((item) => item.id === selectedPlaybookId.value) ?? assistantPlaybooks.value[0] ?? null,
);
const graphSummary = computed(() => {
  if (!currentPlaybook.value) {
    return null;
  }
  return {
    nodeCount: currentPlaybook.value.nodes.length,
    edgeCount: currentPlaybook.value.edges.length,
    entryNodeKey: currentPlaybook.value.entryNodeKey,
  };
});

watch(
  () => props.assistants,
  (assistants) => {
    if (!assistants.length) {
      selectedAssistantId.value = '';
      createForm.assistantId = '';
      return;
    }
    const preferredAssistantId = props.preferredAssistantId;
    if (preferredAssistantId && assistants.some((item) => item.id === preferredAssistantId)) {
      selectedAssistantId.value = preferredAssistantId;
    } else if (!assistants.some((item) => item.id === selectedAssistantId.value)) {
      selectedAssistantId.value = assistants[0].id;
    }
    if (!createForm.assistantId) {
      createForm.assistantId = selectedAssistantId.value || assistants[0].id;
    }
  },
  { immediate: true },
);

watch(
  currentAssistant,
  (assistant) => {
    if (!assistant) {
      selectedPlaybookId.value = '';
      return;
    }
    createForm.assistantId = assistant.id;
    const preferredPlaybookId = props.preferredPlaybookId;
    if (preferredPlaybookId && assistant.playbooks.some((item) => item.id === preferredPlaybookId)) {
      selectedPlaybookId.value = preferredPlaybookId;
      return;
    }
    if (!assistant.playbooks.some((item) => item.id === selectedPlaybookId.value)) {
      selectedPlaybookId.value = assistant.playbooks[0]?.id ?? '';
    }
  },
  { immediate: true },
);

watch(
  currentPlaybook,
  (playbook) => {
    if (!playbook) {
      editForm.name = '';
      editForm.description = '';
      editForm.inputSchema = '';
      editForm.resultSchema = '';
      editForm.executionPolicy = { timeoutPolicy: null, retryPolicy: null };
      editForm.allowHumanTask = true;
      editForm.allowExternalInteraction = true;
      return;
    }
    editForm.name = playbook.name;
    editForm.description = playbook.description ?? '';
    editForm.inputSchema = playbook.inputSchema ?? '';
    editForm.resultSchema = playbook.resultSchema ?? '';
    editForm.executionPolicy = { ...playbook.executionPolicy };
    editForm.allowHumanTask = playbook.allowHumanTask;
    editForm.allowExternalInteraction = playbook.allowExternalInteraction;
  },
  { immediate: true },
);

function normalizeText(value: string) {
  const text = value.trim();
  return text ? text : null;
}

function openCreateDrawer() {
  createForm.assistantId = currentAssistant.value?.id ?? props.assistants[0]?.id ?? '';
  createForm.name = '';
  createForm.description = '';
  createForm.inputSchema = '';
  createForm.resultSchema = '';
  createForm.executionPolicy = { timeoutPolicy: null, retryPolicy: null };
  createForm.allowHumanTask = true;
  createForm.allowExternalInteraction = true;
  createDrawerOpen.value = true;
}

function submitCreate() {
  const starterGraph = serializeEditorSnapshot(createStarterPlaybookGraph());
  emit('createPlaybook', {
    assistantId: createForm.assistantId,
    name: createForm.name,
    description: normalizeText(createForm.description),
    inputSchema: normalizeText(createForm.inputSchema),
    resultSchema: normalizeText(createForm.resultSchema),
    executionPolicy: { ...createForm.executionPolicy },
    allowHumanTask: createForm.allowHumanTask,
    allowExternalInteraction: createForm.allowExternalInteraction,
    entryNodeKey: starterGraph.entryNodeKey,
    nodes: starterGraph.nodes,
    edges: starterGraph.edges,
  });
  createDrawerOpen.value = false;
}

function submitSave() {
  if (!currentPlaybook.value) {
    return;
  }
  emit('savePlaybook', {
    playbookId: currentPlaybook.value.id,
    playbook: {
      name: editForm.name,
      description: normalizeText(editForm.description),
      inputSchema: normalizeText(editForm.inputSchema),
      resultSchema: normalizeText(editForm.resultSchema),
      executionPolicy: { ...editForm.executionPolicy },
      allowHumanTask: editForm.allowHumanTask,
      allowExternalInteraction: editForm.allowExternalInteraction,
      entryNodeKey: currentPlaybook.value.entryNodeKey,
      nodes: currentPlaybook.value.nodes,
      edges: currentPlaybook.value.edges,
    },
  });
}

function openEditor(playbookId?: string) {
  const targetPlaybookId = playbookId ?? currentPlaybook.value?.id;
  if (!targetPlaybookId) {
    return;
  }
  void router.push({
    path: pageMeta['playbook-editor'].path,
    query: {
      assistantId: selectedAssistantId.value,
      playbookId: targetPlaybookId,
    },
  });
}
</script>

<template>
  <div v-if="canManageGovernance" class="page-inline-toolbar">
    <a-button type="primary" @click="openCreateDrawer">新建 Playbook</a-button>
  </div>

  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="助手视图">
        <template #extra>
          <a-tag class="console-accent-tag">{{ assistantPlaybooks.length }} 个流程</a-tag>
        </template>
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="selectedAssistantId"
            :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
          />
          <a-list :data-source="assistantPlaybooks">
            <template #renderItem="{ item }">
              <a-list-item class="clickable-item" @click="selectedPlaybookId = item.id">
                <a-list-item-meta :title="item.name" :description="`${item.entryNodeKey} · ${item.nodes.length} 节点`" />
                <a-tag v-if="selectedPlaybookId === item.id" class="console-accent-tag">当前</a-tag>
              </a-list-item>
            </template>
          </a-list>
        </a-space>
      </a-card>
    </a-col>

    <a-col :span="16">
      <a-card v-if="currentPlaybook" :title="currentPlaybook.name">
        <template #extra>
          <a-space>
            <a-tag class="console-accent-tag">{{ currentAssistant?.name ?? currentPlaybook.assistantId }}</a-tag>
            <a-button @click="openEditor()">进入图编辑页</a-button>
            <a-button v-if="canManageGovernance" type="primary" @click="submitSave">保存基础配置</a-button>
            <a-button v-if="canManageGovernance" danger ghost @click="emit('deletePlaybook', currentPlaybook.id)">删除 Playbook</a-button>
          </a-space>
        </template>

        <a-form layout="vertical" :model="editForm">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="名称">
                <a-input v-model:value="editForm.name" :disabled="!canManageGovernance" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="入口节点">
                <a-input :value="graphSummary?.entryNodeKey" disabled />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="描述">
            <a-textarea v-model:value="editForm.description" :disabled="!canManageGovernance" :rows="3" />
          </a-form-item>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="超时策略">
                <a-input v-model:value="editForm.executionPolicy.timeoutPolicy" :disabled="!canManageGovernance" placeholder="可选" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="重试策略">
                <a-input v-model:value="editForm.executionPolicy.retryPolicy" :disabled="!canManageGovernance" placeholder="可选" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="允许人工节点">
                <a-switch v-model:checked="editForm.allowHumanTask" :disabled="!canManageGovernance" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="允许站外交互节点">
                <a-switch v-model:checked="editForm.allowExternalInteraction" :disabled="!canManageGovernance" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="Input Schema">
                <a-textarea v-model:value="editForm.inputSchema" :disabled="!canManageGovernance" :rows="4" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="Result Schema">
                <a-textarea v-model:value="editForm.resultSchema" :disabled="!canManageGovernance" :rows="4" />
              </a-form-item>
            </a-col>
          </a-row>
        </a-form>

        <a-row :gutter="[16, 16]">
          <a-col :span="8">
            <a-card size="small" title="图定义概览">
              <a-space direction="vertical" style="width: 100%">
                <div class="playbook-summary__line">节点数：{{ graphSummary?.nodeCount ?? 0 }}</div>
                <div class="playbook-summary__line">连线数：{{ graphSummary?.edgeCount ?? 0 }}</div>
                <div class="playbook-summary__line">入口节点：{{ graphSummary?.entryNodeKey ?? '-' }}</div>
              </a-space>
            </a-card>
          </a-col>
          <a-col :span="16">
            <a-card size="small" title="编排编辑">
              <a-space direction="vertical" style="width: 100%" size="middle">
                <a-typography-text type="secondary">
                  在独立编排台中编辑节点、连线、条件路由和画布布局。
                </a-typography-text>
                <a-button type="primary" @click="openEditor()">打开可视化编排台</a-button>
              </a-space>
            </a-card>
          </a-col>
        </a-row>

        <ObjectReferencePanel
          :object-type="'PLAYBOOK'"
          :object-id="currentPlaybook.id"
          :reload-key="`${catalogRevision}:${currentPlaybook.id}`"
          title="Playbook 引用分析"
          style="margin-top: 16px"
        />

        <ObjectHistoryPanel
          aggregate-type="PLAYBOOK"
          :object-id="currentPlaybook.id"
          :reload-key="`${catalogRevision}:${currentPlaybook.id}`"
          style="margin-top: 16px"
        />
      </a-card>

      <a-empty v-else description="当前助手下还没有 Playbook" />
    </a-col>
  </a-row>

  <a-drawer
    :open="createDrawerOpen"
    title="新建 Playbook"
    :width="640"
    destroy-on-close
    @close="createDrawerOpen = false"
  >
    <div class="create-drawer">
      <div class="create-drawer__kicker">02.03 / 助手构建 / Playbook</div>
      <div class="create-drawer__body">
        <a-alert
          type="info"
          show-icon
          message="创建时会自动生成一个最小 starter graph"
          description="创建完成后进入图编辑页继续补节点、配置连线和调整布局。"
        />
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="所属助手">
            <a-select
              v-model:value="createForm.assistantId"
              :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="名称">
            <a-input v-model:value="createForm.name" placeholder="例如：退款受理流程" />
          </a-form-item>
          <a-form-item label="描述">
            <a-textarea v-model:value="createForm.description" :rows="3" />
          </a-form-item>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="超时策略">
                <a-input v-model:value="createForm.executionPolicy.timeoutPolicy" placeholder="可选" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="重试策略">
                <a-input v-model:value="createForm.executionPolicy.retryPolicy" placeholder="可选" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="允许人工节点">
                <a-switch v-model:checked="createForm.allowHumanTask" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="允许站外交互节点">
                <a-switch v-model:checked="createForm.allowExternalInteraction" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="Input Schema">
            <a-textarea v-model:value="createForm.inputSchema" :rows="3" />
          </a-form-item>
          <a-form-item label="Result Schema">
            <a-textarea v-model:value="createForm.resultSchema" :rows="3" />
          </a-form-item>
          <div class="create-drawer__actions">
            <a-button @click="createDrawerOpen = false">取消</a-button>
            <a-button type="primary" html-type="submit">创建 Playbook</a-button>
          </div>
        </a-form>
      </div>
    </div>
  </a-drawer>
</template>

<style scoped>
.playbook-summary__line {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 11px;
}
</style>
