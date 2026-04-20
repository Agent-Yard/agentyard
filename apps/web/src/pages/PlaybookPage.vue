<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import type {
  Assistant,
  CreatePlaybookPayload,
  Playbook,
  PlaybookEdge,
  PlaybookExecutionPolicy,
  PlaybookNode,
  UpdatePlaybookPayload,
} from '../types';

const props = defineProps<{
  assistants: Assistant[];
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  createPlaybook: [payload: CreatePlaybookPayload];
  savePlaybook: [payload: { playbookId: string; playbook: UpdatePlaybookPayload }];
  deletePlaybook: [playbookId: string];
}>();

const selectedAssistantId = ref('');
const selectedPlaybookId = ref('');

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
  entryNodeKey: '',
  nodesJson: defaultNodesJson(),
  edgesJson: defaultEdgesJson(),
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
  entryNodeKey: '',
  nodesJson: defaultNodesJson(),
  edgesJson: defaultEdgesJson(),
});

const currentAssistant = computed(() =>
  props.assistants.find((item) => item.id === selectedAssistantId.value) ?? props.assistants[0] ?? null,
);
const assistantPlaybooks = computed(() => currentAssistant.value?.playbooks ?? []);
const currentPlaybook = computed(() =>
  assistantPlaybooks.value.find((item) => item.id === selectedPlaybookId.value) ?? assistantPlaybooks.value[0] ?? null,
);

watch(
  () => props.assistants,
  (assistants) => {
    if (!assistants.length) {
      selectedAssistantId.value = '';
      createForm.assistantId = '';
      return;
    }
    if (!assistants.some((item) => item.id === selectedAssistantId.value)) {
      selectedAssistantId.value = assistants[0].id;
    }
    if (!createForm.assistantId) {
      createForm.assistantId = assistants[0].id;
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
      editForm.entryNodeKey = '';
      editForm.nodesJson = defaultNodesJson();
      editForm.edgesJson = defaultEdgesJson();
      return;
    }
    editForm.name = playbook.name;
    editForm.description = playbook.description ?? '';
    editForm.inputSchema = playbook.inputSchema ?? '';
    editForm.resultSchema = playbook.resultSchema ?? '';
    editForm.executionPolicy = { ...playbook.executionPolicy };
    editForm.allowHumanTask = playbook.allowHumanTask;
    editForm.allowExternalInteraction = playbook.allowExternalInteraction;
    editForm.entryNodeKey = playbook.entryNodeKey;
    editForm.nodesJson = JSON.stringify(playbook.nodes, null, 2);
    editForm.edgesJson = JSON.stringify(playbook.edges, null, 2);
  },
  { immediate: true },
);

function defaultNodesJson() {
  return JSON.stringify(
    [
      {
        nodeKey: 'start',
        nodeName: '开始步骤',
        nodeType: 'STEP',
        description: '',
        scriptRef: 'playbook.start',
        scriptVersion: 'v1',
        toolId: null,
        toolOperation: null,
        config: {
          scriptVersions: {
            v1: {
              runtime: 'python',
              code: "result = {'statePatch': {}, 'routeKey': None}",
            },
          },
        },
      },
      {
        nodeKey: 'finish',
        nodeName: '结束',
        nodeType: 'END',
        description: '',
        scriptRef: null,
        scriptVersion: null,
        toolId: null,
        toolOperation: null,
        config: {},
      },
    ],
    null,
    2,
  );
}

function defaultEdgesJson() {
  return JSON.stringify(
    [
      {
        edgeKey: 'start-to-finish',
        sourceNodeKey: 'start',
        targetNodeKey: 'finish',
        routeKey: null,
        label: null,
        defaultEdge: true,
      },
    ],
    null,
    2,
  );
}

function parseNodes(raw: string): PlaybookNode[] {
  return JSON.parse(raw) as PlaybookNode[];
}

function parseEdges(raw: string): PlaybookEdge[] {
  return JSON.parse(raw) as PlaybookEdge[];
}

function submitCreate() {
  emit('createPlaybook', {
    assistantId: createForm.assistantId,
    name: createForm.name,
    description: normalizeText(createForm.description),
    inputSchema: normalizeText(createForm.inputSchema),
    resultSchema: normalizeText(createForm.resultSchema),
    executionPolicy: { ...createForm.executionPolicy },
    allowHumanTask: createForm.allowHumanTask,
    allowExternalInteraction: createForm.allowExternalInteraction,
    entryNodeKey: createForm.entryNodeKey,
    nodes: parseNodes(createForm.nodesJson),
    edges: parseEdges(createForm.edgesJson),
  });
  createForm.name = '';
  createForm.description = '';
  createForm.inputSchema = '';
  createForm.resultSchema = '';
  createForm.executionPolicy = { timeoutPolicy: null, retryPolicy: null };
  createForm.allowHumanTask = true;
  createForm.allowExternalInteraction = true;
  createForm.entryNodeKey = '';
  createForm.nodesJson = defaultNodesJson();
  createForm.edgesJson = defaultEdgesJson();
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
      entryNodeKey: editForm.entryNodeKey,
      nodes: parseNodes(editForm.nodesJson),
      edges: parseEdges(editForm.edgesJson),
    },
  });
}

function normalizeText(value: string) {
  const text = value.trim();
  return text ? text : null;
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="9">
      <a-card v-if="canManageGovernance" title="新建 Playbook">
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
              <a-form-item label="入口节点">
                <a-input v-model:value="createForm.entryNodeKey" placeholder="start" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="超时策略">
                <a-input v-model:value="createForm.executionPolicy.timeoutPolicy" placeholder="可选" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="重试策略">
            <a-input v-model:value="createForm.executionPolicy.retryPolicy" placeholder="可选" />
          </a-form-item>
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
          <a-form-item label="Nodes JSON">
            <a-textarea v-model:value="createForm.nodesJson" :rows="12" />
          </a-form-item>
          <a-form-item label="Edges JSON">
            <a-textarea v-model:value="createForm.edgesJson" :rows="8" />
          </a-form-item>
          <a-button type="primary" html-type="submit">创建 Playbook</a-button>
        </a-form>
      </a-card>

      <a-card title="助手视图">
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="selectedAssistantId"
            :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
          />
          <a-list :data-source="assistantPlaybooks">
            <template #renderItem="{ item }">
              <a-list-item class="clickable-item" @click="selectedPlaybookId = item.id">
                <a-list-item-meta :title="item.name" :description="item.entryNodeKey" />
                <a-tag v-if="selectedPlaybookId === item.id" color="blue">当前</a-tag>
              </a-list-item>
            </template>
          </a-list>
        </a-space>
      </a-card>
    </a-col>

    <a-col :span="15">
      <a-card v-if="currentPlaybook" :title="currentPlaybook.name">
        <template #extra>
          <a-space>
            <a-tag color="blue">{{ currentAssistant?.name ?? currentPlaybook.assistantId }}</a-tag>
            <a-button v-if="canManageGovernance" danger ghost @click="emit('deletePlaybook', currentPlaybook.id)">删除 Playbook</a-button>
          </a-space>
        </template>

        <a-form layout="vertical" :model="editForm" @finish="submitSave">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="名称">
                <a-input v-model:value="editForm.name" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="入口节点">
                <a-input v-model:value="editForm.entryNodeKey" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="描述">
            <a-textarea v-model:value="editForm.description" :rows="3" />
          </a-form-item>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="超时策略">
                <a-input v-model:value="editForm.executionPolicy.timeoutPolicy" placeholder="可选" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="重试策略">
                <a-input v-model:value="editForm.executionPolicy.retryPolicy" placeholder="可选" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="允许人工节点">
                <a-switch v-model:checked="editForm.allowHumanTask" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="允许站外交互节点">
                <a-switch v-model:checked="editForm.allowExternalInteraction" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="Input Schema">
            <a-textarea v-model:value="editForm.inputSchema" :rows="3" />
          </a-form-item>
          <a-form-item label="Result Schema">
            <a-textarea v-model:value="editForm.resultSchema" :rows="3" />
          </a-form-item>
          <a-form-item label="Nodes JSON">
            <a-textarea v-model:value="editForm.nodesJson" :rows="14" />
          </a-form-item>
          <a-form-item label="Edges JSON">
            <a-textarea v-model:value="editForm.edgesJson" :rows="10" />
          </a-form-item>
          <a-space v-if="canManageGovernance">
            <a-button type="primary" html-type="submit">保存 Playbook</a-button>
          </a-space>
        </a-form>

        <ObjectReferencePanel
          :object-type="'PLAYBOOK'"
          :object-id="currentPlaybook.id"
          :reload-key="`${catalogRevision}:${currentPlaybook.id}`"
          title="Playbook 引用分析"
          style="margin-top: 16px"
        />
      </a-card>

      <a-empty v-else description="当前助手下还没有 Playbook" />
    </a-col>
  </a-row>
</template>
