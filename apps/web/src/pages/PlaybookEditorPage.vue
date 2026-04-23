<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import { pageMeta } from '../config/navigation';
import type {
  Assistant,
  PlaybookEdge,
  PlaybookExecutionPolicy,
  PlaybookNodeType,
  UpdatePlaybookPayload,
} from '../types';
import {
  createEdgeDraft,
  createNodeDraft,
  parseImportedPlaybookGraph,
  removeNodeAndConnectedEdges,
  serializeEditorSnapshot,
  toEditorSnapshot,
  validatePlaybookEditorGraph,
  type PlaybookEditorNode,
} from './playbookEditor';

const props = defineProps<{
  assistants: Assistant[];
  catalogRevision: number;
  canManageGovernance: boolean;
  preferredAssistantId?: string | null;
  preferredPlaybookId?: string | null;
}>();

const emit = defineEmits<{
  savePlaybook: [payload: { playbookId: string; playbook: UpdatePlaybookPayload }];
  deletePlaybook: [playbookId: string];
}>();

const router = useRouter();

type DragState =
  | {
      kind: 'node';
      nodeKey: string;
      offsetX: number;
      offsetY: number;
    }
  | {
      kind: 'pan';
      startX: number;
      startY: number;
      originX: number;
      originY: number;
    };

const nodePalette = [
  { type: 'STEP', title: '脚本步骤', description: '运行版本化脚本，按 routeKey 决定后续走向。', tone: 'accent' },
  { type: 'TOOL_TASK', title: '工具任务', description: '直接调用 Tool 及其 operation。', tone: 'ok' },
  { type: 'HUMAN_TASK', title: '人工节点', description: '等待人工处理、恢复并继续推进。', tone: 'warn' },
  { type: 'EXTERNAL_INTERACTION', title: '站外交互', description: '等待第三方回调或外部确认。', tone: 'ink' },
  { type: 'END', title: '结束节点', description: '终止流程并返回结果。', tone: 'ghost' },
] as const satisfies Array<{ type: PlaybookNodeType; title: string; description: string; tone: string }>;

const nodeTypeLabel: Record<PlaybookNodeType, string> = {
  STEP: 'STEP',
  TOOL_TASK: 'TOOL_TASK',
  HUMAN_TASK: 'HUMAN_TASK',
  EXTERNAL_INTERACTION: 'EXTERNAL_INTERACTION',
  END: 'END',
};

const selectedAssistantId = ref('');
const selectedPlaybookId = ref('');
const importModalOpen = ref(false);
const exportModalOpen = ref(false);
const importText = ref('');
const validationError = ref('');
const isDirty = ref(false);
const viewportRef = ref<HTMLElement | null>(null);
const dragState = ref<DragState | null>(null);
const suppressDirtyTracking = ref(false);

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

const draftEntryNodeKey = ref('');
const draftNodes = ref<PlaybookEditorNode[]>([]);
const draftEdges = ref<PlaybookEdge[]>([]);
const selectedNodeKey = ref('');
const selectedEdgeKey = ref('');
const edgeDraft = reactive({
  targetNodeKey: '',
  routeKey: '',
  label: '',
  defaultEdge: true,
});
const viewport = reactive({
  x: 48,
  y: 36,
});

const currentAssistant = computed(() =>
  props.assistants.find((item) => item.id === selectedAssistantId.value) ?? props.assistants[0] ?? null,
);
const assistantPlaybooks = computed(() => currentAssistant.value?.playbooks ?? []);
const assistantOptions = computed(() => props.assistants.map((item) => ({ label: item.name, value: item.id })));
const playbookOptions = computed(() =>
  assistantPlaybooks.value.map((item) => ({
    label: `${item.name} · ${item.nodes.length} 节点`,
    value: item.id,
  })),
);
const currentPlaybook = computed(() =>
  assistantPlaybooks.value.find((item) => item.id === selectedPlaybookId.value) ?? assistantPlaybooks.value[0] ?? null,
);
const selectedNode = computed(() => draftNodes.value.find((item) => item.nodeKey === selectedNodeKey.value) ?? null);
const selectedEdge = computed(() => draftEdges.value.find((item) => item.edgeKey === selectedEdgeKey.value) ?? null);
const nodeOptions = computed(() => draftNodes.value.map((node) => ({ label: `${node.nodeName} · ${node.nodeKey}`, value: node.nodeKey })));
const selectedNodeOutgoingEdges = computed(() =>
  selectedNode.value ? draftEdges.value.filter((edge) => edge.sourceNodeKey === selectedNode.value?.nodeKey) : [],
);
const edgeTargetOptions = computed(() =>
  draftNodes.value
    .filter((node) => node.nodeKey !== selectedNode.value?.nodeKey)
    .map((node) => ({ label: `${node.nodeName} · ${node.nodeKey}`, value: node.nodeKey })),
);
const graphValidationPreview = computed(() => validateCurrentGraph());
const exportJson = computed(() => {
  try {
    return JSON.stringify(serializeCurrentGraph(), null, 2);
  } catch {
    return '// 当前图中存在无法解析的 config JSON，请先修复后再导出。';
  }
});
const sceneMetrics = computed(() => {
  const maxX = draftNodes.value.length ? Math.max(...draftNodes.value.map((node) => node.layout.x + 300)) : 1480;
  const maxY = draftNodes.value.length ? Math.max(...draftNodes.value.map((node) => node.layout.y + 260)) : 900;
  return {
    width: Math.max(1480, maxX),
    height: Math.max(900, maxY),
  };
});
const renderedEdges = computed(() => {
  const nodes = Object.fromEntries(draftNodes.value.map((node) => [node.nodeKey, node]));
  return draftEdges.value
    .map((edge) => {
      const source = nodes[edge.sourceNodeKey];
      const target = nodes[edge.targetNodeKey];
      if (!source || !target) {
        return null;
      }
      const x1 = source.layout.x + 248;
      const y1 = source.layout.y + 94;
      const x2 = target.layout.x;
      const y2 = target.layout.y + 94;
      const offset = Math.max(80, Math.abs(x2 - x1) * 0.35);
      return {
        edge,
        path: `M ${x1} ${y1} C ${x1 + offset} ${y1}, ${x2 - offset} ${y2}, ${x2} ${y2}`,
        chipX: (x1 + x2) / 2,
        chipY: (y1 + y2) / 2,
      };
    })
    .filter((item): item is NonNullable<typeof item> => item !== null);
});

watch(
  () => props.assistants,
  (assistants) => {
    if (!assistants.length) {
      selectedAssistantId.value = '';
      return;
    }
    if (props.preferredAssistantId && assistants.some((item) => item.id === props.preferredAssistantId)) {
      selectedAssistantId.value = props.preferredAssistantId;
      return;
    }
    if (!assistants.some((item) => item.id === selectedAssistantId.value)) {
      selectedAssistantId.value = assistants[0].id;
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
    if (props.preferredPlaybookId && assistant.playbooks.some((item) => item.id === props.preferredPlaybookId)) {
      selectedPlaybookId.value = props.preferredPlaybookId;
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
    suppressDirtyTracking.value = true;
    validationError.value = '';
    viewport.x = 48;
    viewport.y = 36;
    selectedEdgeKey.value = '';
    if (!playbook) {
      editForm.name = '';
      editForm.description = '';
      editForm.inputSchema = '';
      editForm.resultSchema = '';
      editForm.executionPolicy = { timeoutPolicy: null, retryPolicy: null };
      editForm.allowHumanTask = true;
      editForm.allowExternalInteraction = true;
      draftEntryNodeKey.value = '';
      draftNodes.value = [];
      draftEdges.value = [];
      selectedNodeKey.value = '';
      isDirty.value = false;
      suppressDirtyTracking.value = false;
      return;
    }
    editForm.name = playbook.name;
    editForm.description = playbook.description ?? '';
    editForm.inputSchema = playbook.inputSchema ?? '';
    editForm.resultSchema = playbook.resultSchema ?? '';
    editForm.executionPolicy = { ...playbook.executionPolicy };
    editForm.allowHumanTask = playbook.allowHumanTask;
    editForm.allowExternalInteraction = playbook.allowExternalInteraction;
    const snapshot = toEditorSnapshot(playbook);
    draftEntryNodeKey.value = snapshot.entryNodeKey;
    draftNodes.value = snapshot.nodes.map((node) => ({ ...node, layout: { ...node.layout } }));
    draftEdges.value = snapshot.edges.map((edge) => ({ ...edge }));
    selectedNodeKey.value = snapshot.nodes[0]?.nodeKey ?? '';
    isDirty.value = false;
    suppressDirtyTracking.value = false;
  },
  { immediate: true },
);

watch(selectedNodeKey, (nodeKey) => {
  selectedEdgeKey.value = '';
  if (!nodeKey) {
    edgeDraft.targetNodeKey = '';
    return;
  }
  edgeDraft.targetNodeKey = draftNodes.value.find((node) => node.nodeKey !== nodeKey)?.nodeKey ?? '';
  edgeDraft.routeKey = '';
  edgeDraft.label = '';
  edgeDraft.defaultEdge = true;
});

watch(
  editForm,
  () => {
    if (!suppressDirtyTracking.value) {
      isDirty.value = true;
    }
  },
  { deep: true },
);

watch(
  [draftNodes, draftEdges, draftEntryNodeKey],
  () => {
    if (!suppressDirtyTracking.value) {
      isDirty.value = true;
    }
  },
  { deep: true },
);

watch(draftNodes, (nodes) => {
  if (selectedNodeKey.value && !nodes.some((node) => node.nodeKey === selectedNodeKey.value)) {
    selectedNodeKey.value = '';
  }
  if (draftEntryNodeKey.value && !nodes.some((node) => node.nodeKey === draftEntryNodeKey.value)) {
    draftEntryNodeKey.value = nodes[0]?.nodeKey ?? '';
  }
}, { deep: true });

watch(draftEdges, (edges) => {
  if (selectedEdgeKey.value && !edges.some((edge) => edge.edgeKey === selectedEdgeKey.value)) {
    selectedEdgeKey.value = '';
  }
}, { deep: true });

function normalizeText(value: string) {
  const text = value.trim();
  return text ? text : null;
}

function clearValidationError() {
  validationError.value = '';
}

function selectNode(nodeKey: string) {
  selectedNodeKey.value = nodeKey;
  selectedEdgeKey.value = '';
}

function selectEdge(edgeKey: string) {
  selectedEdgeKey.value = edgeKey;
  selectedNodeKey.value = '';
}

function updateNode(nodeKey: string, updater: (node: PlaybookEditorNode) => PlaybookEditorNode) {
  draftNodes.value = draftNodes.value.map((node) => (node.nodeKey === nodeKey ? updater(node) : node));
  clearValidationError();
}

function updateEdge(edgeKey: string, updater: (edge: PlaybookEdge) => PlaybookEdge) {
  draftEdges.value = draftEdges.value.map((edge) => (edge.edgeKey === edgeKey ? updater(edge) : edge));
  clearValidationError();
}

function renameSelectedNodeKey(value: string) {
  if (!selectedNode.value) {
    return;
  }
  const nextKey = value;
  const previousKey = selectedNode.value.nodeKey;
  if (nextKey === previousKey) {
    return;
  }
  updateNode(previousKey, (node) => ({ ...node, nodeKey: nextKey }));
  draftEdges.value = draftEdges.value.map((edge) => ({
    ...edge,
    sourceNodeKey: edge.sourceNodeKey === previousKey ? nextKey : edge.sourceNodeKey,
    targetNodeKey: edge.targetNodeKey === previousKey ? nextKey : edge.targetNodeKey,
  }));
  if (draftEntryNodeKey.value === previousKey) {
    draftEntryNodeKey.value = nextKey;
  }
  selectedNodeKey.value = nextKey;
}

function renameSelectedEdgeKey(value: string) {
  if (!selectedEdge.value) {
    return;
  }
  const previousKey = selectedEdge.value.edgeKey;
  updateEdge(previousKey, (edge) => ({ ...edge, edgeKey: value }));
  selectedEdgeKey.value = value;
}

function removeSelectedNode() {
  if (!selectedNode.value) {
    return;
  }
  const next = removeNodeAndConnectedEdges(
    {
      entryNodeKey: draftEntryNodeKey.value,
      nodes: draftNodes.value,
      edges: draftEdges.value,
    },
    selectedNode.value.nodeKey,
  );
  draftEntryNodeKey.value = next.entryNodeKey;
  draftNodes.value = next.nodes;
  draftEdges.value = next.edges;
  selectedNodeKey.value = next.nodes[0]?.nodeKey ?? '';
  clearValidationError();
}

function removeSelectedEdge() {
  if (!selectedEdge.value) {
    return;
  }
  draftEdges.value = draftEdges.value.filter((edge) => edge.edgeKey !== selectedEdge.value?.edgeKey);
  selectedEdgeKey.value = '';
  clearValidationError();
}

function setEntryNode(nodeKey: string) {
  draftEntryNodeKey.value = nodeKey;
  clearValidationError();
}

function addNode(nodeType: PlaybookNodeType) {
  const viewportRect = viewportRef.value?.getBoundingClientRect();
  const layout = viewportRect
    ? {
        x: Math.max(40, Math.round((viewportRect.width / 2) - 124 - viewport.x)),
        y: Math.max(40, Math.round((viewportRect.height / 2) - 94 - viewport.y)),
      }
    : {
        x: 120 + (draftNodes.value.length * 24),
        y: 120 + (draftNodes.value.length * 24),
      };
  const node = createNodeDraft(nodeType, draftNodes.value, layout);
  draftNodes.value = [...draftNodes.value, node];
  if (!draftEntryNodeKey.value) {
    draftEntryNodeKey.value = node.nodeKey;
  }
  selectNode(node.nodeKey);
  clearValidationError();
}

function addEdgeFromSelectedNode() {
  if (!selectedNode.value || !edgeDraft.targetNodeKey) {
    return;
  }
  const edge = createEdgeDraft(selectedNode.value.nodeKey, edgeDraft.targetNodeKey, draftEdges.value, {
    routeKey: normalizeText(edgeDraft.routeKey),
    label: normalizeText(edgeDraft.label),
    defaultEdge: edgeDraft.defaultEdge,
  });
  draftEdges.value = [...draftEdges.value, edge];
  selectEdge(edge.edgeKey);
  clearValidationError();
}

function serializeCurrentGraph() {
  return serializeEditorSnapshot({
    entryNodeKey: draftEntryNodeKey.value,
    nodes: draftNodes.value,
    edges: draftEdges.value,
  });
}

function validateCurrentGraph() {
  return validatePlaybookEditorGraph({
    entryNodeKey: draftEntryNodeKey.value,
    nodes: draftNodes.value,
    edges: draftEdges.value,
    allowHumanTask: editForm.allowHumanTask,
    allowExternalInteraction: editForm.allowExternalInteraction,
  });
}

function submitSave() {
  if (!currentPlaybook.value) {
    return;
  }
  const graphError = validateCurrentGraph();
  if (graphError) {
    validationError.value = graphError;
    void message.error(graphError);
    return;
  }
  let graph;
  try {
    graph = serializeCurrentGraph();
  } catch {
    validationError.value = '存在无法解析的节点 config JSON，请先修复。';
    void message.error(validationError.value);
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
      entryNodeKey: graph.entryNodeKey,
      nodes: graph.nodes,
      edges: graph.edges,
    },
  });
  isDirty.value = false;
  clearValidationError();
}

function openImportModal() {
  importText.value = '';
  importModalOpen.value = true;
}

function openListPage(playbookId?: string) {
  void router.push({
    path: pageMeta.playbook.path,
    query: {
      assistantId: selectedAssistantId.value,
      playbookId: playbookId ?? selectedPlaybookId.value,
    },
  });
}

function applyImport() {
  try {
    const snapshot = parseImportedPlaybookGraph(importText.value);
    const graphError = validatePlaybookEditorGraph({
      entryNodeKey: snapshot.entryNodeKey,
      nodes: snapshot.nodes,
      edges: snapshot.edges,
      allowHumanTask: editForm.allowHumanTask,
      allowExternalInteraction: editForm.allowExternalInteraction,
    });
    if (graphError) {
      validationError.value = graphError;
      void message.error(graphError);
      return;
    }
    draftEntryNodeKey.value = snapshot.entryNodeKey;
    draftNodes.value = snapshot.nodes;
    draftEdges.value = snapshot.edges;
    selectedNodeKey.value = snapshot.nodes[0]?.nodeKey ?? '';
    selectedEdgeKey.value = '';
    importModalOpen.value = false;
    clearValidationError();
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : '导入 JSON 失败';
    validationError.value = errorMessage;
    void message.error(errorMessage);
  }
}

function startNodeDrag(event: MouseEvent, node: PlaybookEditorNode) {
  if (!props.canManageGovernance || event.button !== 0) {
    return;
  }
  const viewportRect = viewportRef.value?.getBoundingClientRect();
  if (!viewportRect) {
    return;
  }
  dragState.value = {
    kind: 'node',
    nodeKey: node.nodeKey,
    offsetX: event.clientX - viewportRect.left - viewport.x - node.layout.x,
    offsetY: event.clientY - viewportRect.top - viewport.y - node.layout.y,
  };
  window.addEventListener('mousemove', handleGlobalMouseMove);
  window.addEventListener('mouseup', stopDragging);
}

function startPan(event: MouseEvent) {
  if (event.button !== 0) {
    return;
  }
  const target = event.target as HTMLElement;
  if (target.closest('.graph-node') || target.closest('.graph-edge-chip')) {
    return;
  }
  dragState.value = {
    kind: 'pan',
    startX: event.clientX,
    startY: event.clientY,
    originX: viewport.x,
    originY: viewport.y,
  };
  window.addEventListener('mousemove', handleGlobalMouseMove);
  window.addEventListener('mouseup', stopDragging);
}

function handleGlobalMouseMove(event: MouseEvent) {
  if (!dragState.value) {
    return;
  }
  if (dragState.value.kind === 'pan') {
    viewport.x = dragState.value.originX + (event.clientX - dragState.value.startX);
    viewport.y = dragState.value.originY + (event.clientY - dragState.value.startY);
    return;
  }
  const viewportRect = viewportRef.value?.getBoundingClientRect();
  if (!viewportRect) {
    return;
  }
  const nextX = Math.max(24, Math.round(event.clientX - viewportRect.left - viewport.x - dragState.value.offsetX));
  const nextY = Math.max(24, Math.round(event.clientY - viewportRect.top - viewport.y - dragState.value.offsetY));
  updateNode(dragState.value.nodeKey, (node) => ({
    ...node,
    layout: {
      x: nextX,
      y: nextY,
    },
  }));
}

function stopDragging() {
  dragState.value = null;
  window.removeEventListener('mousemove', handleGlobalMouseMove);
  window.removeEventListener('mouseup', stopDragging);
}

onBeforeUnmount(() => {
  stopDragging();
});
</script>

<template>
  <a-card v-if="currentPlaybook" :title="currentPlaybook.name">
    <template #extra>
      <a-space wrap>
        <a-button @click="openListPage()">返回列表页</a-button>
        <a-button @click="exportModalOpen = true">导出 JSON</a-button>
        <a-button v-if="canManageGovernance" @click="openImportModal">导入 JSON</a-button>
        <a-button v-if="canManageGovernance" type="primary" @click="submitSave">
          {{ isDirty ? '保存 Playbook' : '重新保存' }}
        </a-button>
        <a-button v-if="canManageGovernance" danger ghost @click="emit('deletePlaybook', currentPlaybook.id)">删除 Playbook</a-button>
      </a-space>
    </template>

    <div class="playbook-workbench">
      <div class="playbook-editor-toolbar">
        <a-space wrap size="middle">
          <a-select
            v-model:value="selectedAssistantId"
            class="playbook-editor-toolbar__select"
            :options="assistantOptions"
            placeholder="选择助手"
          />
          <a-select
            v-model:value="selectedPlaybookId"
            class="playbook-editor-toolbar__select playbook-editor-toolbar__select--wide"
            :options="playbookOptions"
            placeholder="选择 Playbook"
          />
        </a-space>
        <a-space wrap>
          <a-tag class="console-accent-tag">{{ currentAssistant?.name ?? currentPlaybook.assistantId }}</a-tag>
          <a-tag class="console-accent-tag">{{ draftNodes.length }} 节点</a-tag>
          <a-tag class="console-accent-tag">{{ draftEdges.length }} 连线</a-tag>
          <a-tag class="console-accent-tag">
            {{ graphValidationPreview ? 'NEEDS CHECK' : 'VALID' }}
          </a-tag>
        </a-space>
      </div>

      <div class="playbook-editor-toolbar playbook-editor-toolbar--caption">
        <a-typography-text type="secondary">
          图编排页仅保留节点、连线与 Inspector 编辑；基础属性、引用关系和操作记录请回列表页查看。
        </a-typography-text>
      </div>

      <div class="playbook-workbench__content">
          <a-alert
            v-if="validationError"
            type="warning"
            show-icon
            :message="validationError"
          />

          <div class="playbook-studio">
            <aside class="playbook-panel playbook-palette">
              <div class="playbook-panel__kicker">节点面板</div>
              <div class="playbook-panel__title">添加节点</div>
              <button
                v-for="item in nodePalette"
                :key="item.type"
                type="button"
                class="playbook-palette__item"
                :disabled="!canManageGovernance"
                @click="addNode(item.type)"
              >
                <div>
                  <strong>{{ item.title }}</strong>
                  <p>{{ item.description }}</p>
                </div>
                <span class="playbook-palette__tag">{{ nodeTypeLabel[item.type] }}</span>
              </button>

              <div class="playbook-panel__section">
                <div class="playbook-panel__title">图状态</div>
                <div class="playbook-palette__stat">入口节点：{{ draftEntryNodeKey || '-' }}</div>
                <div class="playbook-palette__stat">节点总数：{{ draftNodes.length }}</div>
                <div class="playbook-palette__stat">连线总数：{{ draftEdges.length }}</div>
                <div class="playbook-palette__stat">画布偏移：{{ viewport.x }}, {{ viewport.y }}</div>
              </div>
            </aside>

            <section class="playbook-panel playbook-canvas-panel">
              <div class="playbook-canvas__chrome">
                <span class="playbook-canvas__chip">100%</span>
                <span class="playbook-canvas__chip">ENTRY {{ draftEntryNodeKey || '-' }}</span>
                <span class="playbook-canvas__chip">NODES {{ draftNodes.length }} · EDGES {{ draftEdges.length }}</span>
                <span class="playbook-canvas__chip playbook-canvas__chip--accent">
                  {{ graphValidationPreview ? 'CHECK REQUIRED' : 'READY TO SAVE' }}
                </span>
              </div>

              <div
                ref="viewportRef"
                class="graph-editor__viewport"
                :class="{ 'graph-editor--panning': dragState?.kind === 'pan' }"
                @mousedown="startPan"
                @click="selectedNodeKey = ''; selectedEdgeKey = ''"
              >
                <div
                  class="graph-editor graph-editor__scene"
                  :style="{
                    width: `${sceneMetrics.width}px`,
                    height: `${sceneMetrics.height}px`,
                    transform: `translate(${viewport.x}px, ${viewport.y}px)`,
                  }"
                >
                  <svg class="graph-editor__svg" :width="sceneMetrics.width" :height="sceneMetrics.height">
                    <defs>
                      <marker id="playbook-edge-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                        <path d="M 0 0 L 10 5 L 0 10 z" fill="rgba(123, 132, 145, 0.72)" />
                      </marker>
                    </defs>
                    <g v-for="item in renderedEdges" :key="item.edge.edgeKey">
                      <path
                        class="graph-editor__path-hit"
                        :d="item.path"
                        @click.stop="selectEdge(item.edge.edgeKey)"
                      />
                      <path
                        class="graph-editor__path"
                        :class="{ 'graph-editor__path--active': selectedEdgeKey === item.edge.edgeKey }"
                        :d="item.path"
                        marker-end="url(#playbook-edge-arrow)"
                      />
                    </g>
                  </svg>

                  <button
                    v-for="item in renderedEdges"
                    :key="`${item.edge.edgeKey}-chip`"
                    type="button"
                    class="graph-edge-chip"
                    :class="{ 'graph-edge-chip--active': selectedEdgeKey === item.edge.edgeKey }"
                    :style="{ left: `${item.chipX}px`, top: `${item.chipY}px` }"
                    @click.stop="selectEdge(item.edge.edgeKey)"
                  >
                    {{ item.edge.label || item.edge.routeKey || (item.edge.defaultEdge ? 'default' : item.edge.edgeKey) }}
                  </button>

                  <button
                    v-for="node in draftNodes"
                    :key="node.nodeKey"
                    type="button"
                    class="graph-node playbook-node"
                    :class="[
                      `playbook-node--${node.nodeType.toLowerCase()}`,
                      { 'graph-node--active': selectedNodeKey === node.nodeKey },
                    ]"
                    :style="{ left: `${node.layout.x}px`, top: `${node.layout.y}px` }"
                    @mousedown.stop="startNodeDrag($event, node)"
                    @click.stop="selectNode(node.nodeKey)"
                  >
                    <div class="playbook-node__header">
                      <div class="graph-node__eyebrow">{{ nodeTypeLabel[node.nodeType] }} · {{ node.nodeKey }}</div>
                      <a-tag v-if="draftEntryNodeKey === node.nodeKey" class="console-accent-tag">ENTRY</a-tag>
                    </div>
                    <div class="graph-node__title">{{ node.nodeName }}</div>
                    <div class="graph-node__description">{{ node.description || '未填写节点说明。' }}</div>
                    <div class="graph-node__resources">
                      <div v-if="node.nodeType === 'STEP'">{{ node.scriptRef || '未配置脚本' }} · {{ node.scriptVersion || '-' }}</div>
                      <div v-else-if="node.nodeType === 'TOOL_TASK'">{{ node.toolId || '未绑定 Tool' }} · {{ node.toolOperation || '-' }}</div>
                      <div v-else>{{ nodeTypeLabel[node.nodeType] }} 节点</div>
                    </div>
                    <div class="graph-node__actions">
                      <a-button size="small" @click.stop="setEntryNode(node.nodeKey)">设为入口</a-button>
                      <a-button v-if="canManageGovernance" size="small" danger @click.stop="selectNode(node.nodeKey); removeSelectedNode()">删除</a-button>
                    </div>
                  </button>
                </div>
              </div>
            </section>

            <aside class="playbook-panel playbook-inspector">
              <div class="playbook-panel__kicker">Inspector</div>
              <div v-if="selectedNode" class="playbook-inspector__body">
                <div class="playbook-panel__title">{{ selectedNode.nodeName }}</div>
                <a-form layout="vertical">
                  <a-form-item label="节点 Key">
                    <a-input :value="selectedNode.nodeKey" :disabled="!canManageGovernance" @update:value="renameSelectedNodeKey" />
                  </a-form-item>
                  <a-form-item label="节点名称">
                    <a-input
                      :value="selectedNode.nodeName"
                      :disabled="!canManageGovernance"
                      @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, nodeName: $event }))"
                    />
                  </a-form-item>
                  <a-form-item label="节点说明">
                    <a-textarea
                      :value="selectedNode.description ?? ''"
                      :disabled="!canManageGovernance"
                      :rows="3"
                      @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, description: $event }))"
                    />
                  </a-form-item>
                  <a-form-item label="节点类型">
                    <a-input :value="nodeTypeLabel[selectedNode.nodeType]" disabled />
                  </a-form-item>
                  <a-form-item label="是否为入口节点">
                    <a-switch
                      :checked="draftEntryNodeKey === selectedNode.nodeKey"
                      :disabled="!canManageGovernance"
                      @change="setEntryNode(selectedNode.nodeKey)"
                    />
                  </a-form-item>

                  <template v-if="selectedNode.nodeType === 'STEP'">
                    <a-form-item label="scriptRef">
                      <a-input
                        :value="selectedNode.scriptRef ?? ''"
                        :disabled="!canManageGovernance"
                        @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, scriptRef: $event || null }))"
                      />
                    </a-form-item>
                    <a-form-item label="scriptVersion">
                      <a-input
                        :value="selectedNode.scriptVersion ?? ''"
                        :disabled="!canManageGovernance"
                        @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, scriptVersion: $event || null }))"
                      />
                    </a-form-item>
                  </template>

                  <template v-if="selectedNode.nodeType === 'TOOL_TASK'">
                    <a-form-item label="toolId">
                      <a-input
                        :value="selectedNode.toolId ?? ''"
                        :disabled="!canManageGovernance"
                        @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, toolId: $event || null }))"
                      />
                    </a-form-item>
                    <a-form-item label="toolOperation">
                      <a-input
                        :value="selectedNode.toolOperation ?? ''"
                        :disabled="!canManageGovernance"
                        @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, toolOperation: $event || null }))"
                      />
                    </a-form-item>
                  </template>

                  <a-form-item label="layout.x / layout.y">
                    <div class="playbook-inspector__layout-fields">
                      <a-input-number
                        style="width: 100%"
                        :value="selectedNode.layout.x"
                        :disabled="!canManageGovernance"
                        @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, layout: { ...node.layout, x: Number($event ?? 0) } }))"
                      />
                      <a-input-number
                        style="width: 100%"
                        :value="selectedNode.layout.y"
                        :disabled="!canManageGovernance"
                        @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, layout: { ...node.layout, y: Number($event ?? 0) } }))"
                      />
                    </div>
                  </a-form-item>
                  <a-form-item label="config JSON">
                    <a-textarea
                      :value="selectedNode.configText"
                      :disabled="!canManageGovernance"
                      :rows="10"
                      @update:value="updateNode(selectedNode.nodeKey, (node) => ({ ...node, configText: $event }))"
                    />
                  </a-form-item>
                </a-form>

                <div class="playbook-panel__section">
                  <div class="playbook-panel__title">出边</div>
                  <div v-if="selectedNodeOutgoingEdges.length" class="playbook-edge-list">
                    <button
                      v-for="edge in selectedNodeOutgoingEdges"
                      :key="edge.edgeKey"
                      type="button"
                      class="playbook-edge-list__item"
                      :class="{ 'playbook-edge-list__item--active': selectedEdgeKey === edge.edgeKey }"
                      @click="selectEdge(edge.edgeKey)"
                    >
                      <strong>{{ edge.label || edge.routeKey || edge.edgeKey }}</strong>
                      <span>{{ edge.targetNodeKey }}</span>
                    </button>
                  </div>
                  <a-empty v-else description="当前节点还没有出边" />
                </div>

                <div v-if="canManageGovernance" class="playbook-panel__section">
                  <div class="playbook-panel__title">新增出边</div>
                  <a-form layout="vertical">
                    <a-form-item label="目标节点">
                      <a-select v-model:value="edgeDraft.targetNodeKey" :options="edgeTargetOptions" />
                    </a-form-item>
                    <a-form-item label="routeKey">
                      <a-input v-model:value="edgeDraft.routeKey" placeholder="例如：approved / retry" />
                    </a-form-item>
                    <a-form-item label="显示标签">
                      <a-input v-model:value="edgeDraft.label" placeholder="例如：默认分支" />
                    </a-form-item>
                    <a-form-item label="默认边">
                      <a-switch v-model:checked="edgeDraft.defaultEdge" />
                    </a-form-item>
                    <a-button type="primary" block @click="addEdgeFromSelectedNode">新增连线</a-button>
                  </a-form>
                </div>
              </div>

              <div v-else-if="selectedEdge" class="playbook-inspector__body">
                <div class="playbook-panel__title">连线 · {{ selectedEdge.edgeKey }}</div>
                <a-form layout="vertical">
                  <a-form-item label="edgeKey">
                    <a-input :value="selectedEdge.edgeKey" :disabled="!canManageGovernance" @update:value="renameSelectedEdgeKey" />
                  </a-form-item>
                  <a-form-item label="source">
                    <a-select
                      :value="selectedEdge.sourceNodeKey"
                      :disabled="!canManageGovernance"
                      :options="nodeOptions"
                      @update:value="updateEdge(selectedEdge.edgeKey, (edge) => ({ ...edge, sourceNodeKey: $event }))"
                    />
                  </a-form-item>
                  <a-form-item label="target">
                    <a-select
                      :value="selectedEdge.targetNodeKey"
                      :disabled="!canManageGovernance"
                      :options="nodeOptions"
                      @update:value="updateEdge(selectedEdge.edgeKey, (edge) => ({ ...edge, targetNodeKey: $event }))"
                    />
                  </a-form-item>
                  <a-form-item label="routeKey">
                    <a-input
                      :value="selectedEdge.routeKey ?? ''"
                      :disabled="!canManageGovernance"
                      @update:value="updateEdge(selectedEdge.edgeKey, (edge) => ({ ...edge, routeKey: $event || null }))"
                    />
                  </a-form-item>
                  <a-form-item label="显示标签">
                    <a-input
                      :value="selectedEdge.label ?? ''"
                      :disabled="!canManageGovernance"
                      @update:value="updateEdge(selectedEdge.edgeKey, (edge) => ({ ...edge, label: $event || null }))"
                    />
                  </a-form-item>
                  <a-form-item label="默认边">
                    <a-switch
                      :checked="selectedEdge.defaultEdge"
                      :disabled="!canManageGovernance"
                      @change="updateEdge(selectedEdge.edgeKey, (edge) => ({ ...edge, defaultEdge: Boolean($event) }))"
                    />
                  </a-form-item>
                </a-form>
                <a-button v-if="canManageGovernance" danger block @click="removeSelectedEdge">删除连线</a-button>
              </div>

              <div v-else class="playbook-inspector__placeholder">
                点击节点或连线查看属性。
              </div>
            </aside>
          </div>
        </div>
      </div>
  </a-card>

  <a-empty v-else description="当前助手下还没有 Playbook" />

  <a-modal
    :open="importModalOpen"
    title="导入 Playbook JSON"
    :footer="null"
    width="780px"
    @cancel="importModalOpen = false"
  >
    <a-space direction="vertical" style="width: 100%" size="middle">
      <a-alert
        type="info"
        show-icon
        message="导入内容需包含 entryNodeKey、nodes、edges"
        description="节点 config 会按当前图编辑器规则解析，layout 缺失时会自动补默认位置。"
      />
      <a-textarea v-model:value="importText" :rows="18" placeholder='{"entryNodeKey":"start","nodes":[],"edges":[]}' />
      <div class="create-modal__actions">
        <a-button @click="importModalOpen = false">取消</a-button>
        <a-button type="primary" @click="applyImport">导入图定义</a-button>
      </div>
    </a-space>
  </a-modal>

  <a-modal
    :open="exportModalOpen"
    title="导出 Playbook JSON"
    :footer="null"
    width="780px"
    @cancel="exportModalOpen = false"
  >
    <a-space direction="vertical" style="width: 100%" size="middle">
      <a-alert
        type="info"
        show-icon
        message="导出内容可作为图定义备份或导入基线"
        description="导出的 JSON 仅包含 entryNodeKey、nodes、edges。"
      />
      <a-textarea :value="exportJson" :rows="18" readonly />
      <div class="create-modal__actions">
        <a-button type="primary" @click="exportModalOpen = false">关闭</a-button>
      </div>
    </a-space>
  </a-modal>
</template>

<style scoped>
.playbook-workbench {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.playbook-workbench__content {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.playbook-editor-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.playbook-editor-toolbar--caption {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.playbook-editor-toolbar__select {
  width: 220px;
}

.playbook-editor-toolbar__select--wide {
  width: 320px;
}

.playbook-studio {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 320px;
  gap: 16px;
  align-items: start;
}

.playbook-panel {
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: rgba(255, 255, 255, 0.62);
  padding: 16px;
}

.playbook-panel__kicker {
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.playbook-panel__title {
  margin-top: 6px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 700;
}

.playbook-panel__section {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}

.playbook-palette {
  display: flex;
  flex-direction: column;
}

.playbook-palette__item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: rgba(252, 251, 248, 0.86);
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.18s ease,
    transform 0.18s ease,
    box-shadow 0.18s ease;
}

.playbook-palette__item:hover {
  border-color: var(--line-strong);
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}

.playbook-palette__item:disabled {
  cursor: not-allowed;
  opacity: 0.58;
  transform: none;
  box-shadow: none;
}

.playbook-palette__item strong {
  display: block;
  margin-bottom: 4px;
  color: var(--ink);
}

.playbook-palette__item p {
  margin: 0;
  color: var(--ink-soft);
  font-size: var(--text-secondary);
  line-height: 1.5;
}

.playbook-palette__tag,
.playbook-canvas__chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 9px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(252, 251, 248, 0.94);
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.playbook-palette__stat {
  margin-top: 8px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.playbook-canvas-panel {
  padding: 12px;
}

.playbook-canvas__chrome {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.playbook-canvas__chip--accent {
  border-color: var(--accent-line);
  background: color-mix(in srgb, var(--accent-tint) 68%, white);
  color: var(--accent-ink);
}

.playbook-node {
  border-top-width: 4px;
}

.playbook-node--step {
  border-top-color: var(--accent);
}

.playbook-node--tool_task {
  border-top-color: var(--ok);
}

.playbook-node--human_task {
  border-top-color: var(--warn);
}

.playbook-node--external_interaction {
  border-top-color: var(--ink-soft);
}

.playbook-node--end {
  border-top-color: var(--ink);
}

.playbook-node__header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.playbook-edge-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.playbook-edge-list__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: rgba(252, 251, 248, 0.9);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.playbook-edge-list__item--active {
  border-color: var(--accent-line);
  background: color-mix(in srgb, var(--accent-tint) 68%, white);
}

.playbook-edge-list__item strong {
  color: var(--ink);
}

.playbook-edge-list__item span {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: var(--text-caption);
}

.playbook-inspector {
  min-height: 640px;
}

.playbook-inspector__body {
  margin-top: 8px;
}

.playbook-inspector__layout-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.playbook-inspector__placeholder {
  display: grid;
  place-items: center;
  min-height: 560px;
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: var(--text-secondary);
  text-align: center;
}

@media (max-width: 1440px) {
  .playbook-studio {
    grid-template-columns: 200px minmax(0, 1fr) 300px;
  }
}

@media (max-width: 1200px) {
  .playbook-editor-toolbar {
    align-items: flex-start;
  }

  .playbook-editor-toolbar__select,
  .playbook-editor-toolbar__select--wide {
    width: min(100%, 320px);
  }

  .playbook-studio {
    grid-template-columns: 1fr;
  }

  .playbook-inspector {
    min-height: 0;
  }

  .playbook-inspector__placeholder {
    min-height: 120px;
  }
}
</style>
