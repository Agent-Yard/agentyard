<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type {
  Assistant,
  AssistantOrchestration,
  OrchestrationEdge,
  OrchestrationNode,
  Resource,
  UpdateOrchestrationPayload,
} from '../types';

const NODE_WIDTH = 240;
const NODE_HEIGHT = 188;
const NODE_GAP = 120;
const CANVAS_PADDING_X = 40;
const CANVAS_PADDING_Y = 88;
const CANVAS_HEIGHT = 380;

const props = defineProps<{
  assistants: Assistant[];
  orchestrations: AssistantOrchestration[];
  resources: Resource[];
}>();

const emit = defineEmits<{
  saveOrchestration: [payload: { assistantId: string; data: UpdateOrchestrationPayload }];
}>();

const selectedAssistantId = ref('');
const executionMode = ref('SEQUENTIAL_GRAPH');
const workingNodes = ref<OrchestrationNode[]>([]);
const workingEdges = ref<OrchestrationEdge[]>([]);
const selectedNodeId = ref('');
const selectedEdgeId = ref('');
const draggedNodeId = ref('');
const connectMode = ref(false);
const pendingSourceNodeId = ref('');
const zoom = ref(1);
const panX = ref(0);
const panY = ref(0);
const isPanning = ref(false);

const current = computed(() =>
  props.orchestrations.find((item) => item.assistantId === selectedAssistantId.value) ?? props.orchestrations[0],
);

const selectedNode = computed(() =>
  workingNodes.value.find((item) => item.nodeId === selectedNodeId.value) ?? workingNodes.value[0],
);

const selectedEdge = computed(() =>
  workingEdges.value.find((item) => item.edgeId === selectedEdgeId.value) ?? workingEdges.value[0],
);

const canvasWidth = computed(() =>
  Math.max(
    960,
    CANVAS_PADDING_X * 2 + workingNodes.value.length * NODE_WIDTH + Math.max(0, workingNodes.value.length - 1) * NODE_GAP,
  ),
);

const canvasStyle = computed(() => ({
  transform: `translate(${panX.value}px, ${panY.value}px) scale(${zoom.value})`,
}));

const nodePositions = computed(() =>
  workingNodes.value.map((node, index) => ({
    nodeId: node.nodeId,
    left: CANVAS_PADDING_X + index * (NODE_WIDTH + NODE_GAP),
    top: CANVAS_PADDING_Y,
  })),
);

const connectorModels = computed(() =>
  workingEdges.value.flatMap((edge, index) => {
    const fromIndex = workingNodes.value.findIndex((item) => item.nodeId === edge.fromNodeId);
    const toIndex = workingNodes.value.findIndex((item) => item.nodeId === edge.toNodeId);
    if (fromIndex < 0 || toIndex < 0) {
      return [];
    }

    const fromLeft = CANVAS_PADDING_X + fromIndex * (NODE_WIDTH + NODE_GAP);
    const toLeft = CANVAS_PADDING_X + toIndex * (NODE_WIDTH + NODE_GAP);
    const startX = fromLeft + NODE_WIDTH;
    const endX = toLeft;
    const centerY = CANVAS_PADDING_Y + NODE_HEIGHT / 2;

    if (toIndex > fromIndex) {
      const bend = Math.max(80, (endX - startX) / 2);
      return [{
        edgeId: edge.edgeId,
        d: `M ${startX} ${centerY} C ${startX + bend} ${centerY} ${endX - bend} ${centerY} ${endX} ${centerY}`,
        labelX: (startX + endX) / 2,
        labelY: centerY - 22 - (index % 2) * 10,
      }];
    }

    const arcHeight = 90 + Math.abs(toIndex - fromIndex) * 26;
    const topY = centerY - arcHeight;
    const midX = (startX + endX) / 2;
    return [{
      edgeId: edge.edgeId,
      d: `M ${startX} ${centerY} C ${startX + 90} ${centerY} ${midX + 40} ${topY} ${midX} ${topY} C ${midX - 40} ${topY} ${endX - 90} ${centerY} ${endX} ${centerY}`,
      labelX: midX,
      labelY: topY - 14,
    }];
  }),
);

watch(
  () => props.orchestrations,
  (items) => {
    if (!items.length) {
      selectedAssistantId.value = '';
      return;
    }

    if (!items.some((item) => item.assistantId === selectedAssistantId.value)) {
      selectedAssistantId.value = items[0].assistantId;
    }
  },
  { immediate: true },
);

watch(
  current,
  (value) => {
    if (!value) {
      workingNodes.value = [];
      workingEdges.value = [];
      selectedNodeId.value = '';
      selectedEdgeId.value = '';
      pendingSourceNodeId.value = '';
      return;
    }

    executionMode.value = value.executionMode;
    workingNodes.value = value.nodes.map((node) => ({ ...node, resourceIds: [...node.resourceIds] }));
    workingEdges.value = value.edges.map((edge) => ({ ...edge }));
    selectedNodeId.value = value.nodes[0]?.nodeId ?? '';
    selectedEdgeId.value = value.edges[0]?.edgeId ?? '';
    connectMode.value = false;
    pendingSourceNodeId.value = '';
    zoom.value = 1;
    panX.value = 0;
    panY.value = 0;
  },
  { immediate: true },
);

watch(
  workingNodes,
  (nodes) => {
    if (!nodes.some((item) => item.nodeId === selectedNodeId.value)) {
      selectedNodeId.value = nodes[0]?.nodeId ?? '';
    }
  },
  { deep: true },
);

watch(
  workingEdges,
  (edges) => {
    if (!edges.some((item) => item.edgeId === selectedEdgeId.value)) {
      selectedEdgeId.value = edges[0]?.edgeId ?? '';
    }
  },
  { deep: true },
);

function resetToLinearFlow() {
  workingEdges.value = workingNodes.value.slice(0, -1).map((node, index) => {
    const nextNode = workingNodes.value[index + 1];
    return {
      edgeId: `edge-${node.nodeId}-${nextNode.nodeId}`,
      fromNodeId: node.nodeId,
      toNodeId: nextNode.nodeId,
      condition: index === workingNodes.value.length - 2 ? '升级判定或结束' : '标准编排流转',
      handoffPolicy: index === workingNodes.value.length - 2 ? 'conditional-handoff' : 'direct-handoff',
    };
  });
  selectedEdgeId.value = workingEdges.value[0]?.edgeId ?? '';
}

function moveNode(nodeId: string, direction: -1 | 1) {
  const index = workingNodes.value.findIndex((item) => item.nodeId === nodeId);
  const targetIndex = index + direction;
  if (index < 0 || targetIndex < 0 || targetIndex >= workingNodes.value.length) {
    return;
  }

  const nodes = [...workingNodes.value];
  const [node] = nodes.splice(index, 1);
  nodes.splice(targetIndex, 0, node);
  workingNodes.value = nodes;
}

function reorderNode(dragNodeId: string, targetNodeId: string) {
  if (!dragNodeId || dragNodeId === targetNodeId) {
    return;
  }

  const sourceIndex = workingNodes.value.findIndex((item) => item.nodeId === dragNodeId);
  const targetIndex = workingNodes.value.findIndex((item) => item.nodeId === targetNodeId);
  if (sourceIndex < 0 || targetIndex < 0) {
    return;
  }

  const nodes = [...workingNodes.value];
  const [node] = nodes.splice(sourceIndex, 1);
  nodes.splice(targetIndex, 0, node);
  workingNodes.value = nodes;
}

function handleNodeClick(nodeId: string) {
  if (connectMode.value) {
    if (!pendingSourceNodeId.value) {
      pendingSourceNodeId.value = nodeId;
      selectedNodeId.value = nodeId;
      return;
    }

    if (pendingSourceNodeId.value === nodeId) {
      pendingSourceNodeId.value = '';
      return;
    }

    addEdge(pendingSourceNodeId.value, nodeId);
    pendingSourceNodeId.value = '';
    connectMode.value = false;
    return;
  }

  selectedNodeId.value = nodeId;
}

function addEdge(fromNodeId: string, toNodeId: string) {
  const exists = workingEdges.value.some((item) => item.fromNodeId === fromNodeId && item.toNodeId === toNodeId);
  if (exists) {
    return;
  }

  const created: OrchestrationEdge = {
    edgeId: `edge-${fromNodeId}-${toNodeId}-${Math.random().toString(16).slice(2, 6)}`,
    fromNodeId,
    toNodeId,
    condition: '新增分支条件',
    handoffPolicy: 'conditional-handoff',
  };

  workingEdges.value = [...workingEdges.value, created];
  selectedEdgeId.value = created.edgeId;
}

function deleteSelectedEdge() {
  if (!selectedEdge.value) {
    return;
  }

  workingEdges.value = workingEdges.value.filter((item) => item.edgeId !== selectedEdge.value?.edgeId);
}

function selectEdge(edgeId: string) {
  selectedEdgeId.value = edgeId;
}

function toggleConnectMode() {
  connectMode.value = !connectMode.value;
  pendingSourceNodeId.value = '';
}

function zoomIn() {
  zoom.value = Math.min(1.8, Number((zoom.value + 0.1).toFixed(2)));
}

function zoomOut() {
  zoom.value = Math.max(0.6, Number((zoom.value - 0.1).toFixed(2)));
}

function resetViewport() {
  zoom.value = 1;
  panX.value = 0;
  panY.value = 0;
}

function handleWheel(event: WheelEvent) {
  event.preventDefault();
  if (event.deltaY > 0) {
    zoomOut();
    return;
  }
  zoomIn();
}

let startPanX = 0;
let startPanY = 0;
let originPanX = 0;
let originPanY = 0;

function handleViewportMouseDown(event: MouseEvent) {
  const target = event.target as HTMLElement | null;
  if (target?.closest('.graph-node') || target?.closest('.graph-edge-chip')) {
    return;
  }

  isPanning.value = true;
  startPanX = event.clientX;
  startPanY = event.clientY;
  originPanX = panX.value;
  originPanY = panY.value;
}

function handleWindowMouseMove(event: MouseEvent) {
  if (!isPanning.value) {
    return;
  }

  panX.value = originPanX + event.clientX - startPanX;
  panY.value = originPanY + event.clientY - startPanY;
}

function stopPanning() {
  isPanning.value = false;
}

onMounted(() => {
  window.addEventListener('mousemove', handleWindowMouseMove);
  window.addEventListener('mouseup', stopPanning);
});

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', handleWindowMouseMove);
  window.removeEventListener('mouseup', stopPanning);
});

function submitSave() {
  if (!current.value) {
    return;
  }

  emit('saveOrchestration', {
    assistantId: current.value.assistantId,
    data: {
      executionMode: executionMode.value,
      nodes: workingNodes.value,
      edges: workingEdges.value,
    },
  });
}

function nodeResourceNames(resourceIds: string[]) {
  return resourceIds
    .map((resourceId) => props.resources.find((item) => item.id === resourceId)?.name ?? resourceId)
    .join(' / ');
}

function nodeById(nodeId: string) {
  return workingNodes.value.find((item) => item.nodeId === nodeId);
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="7">
      <a-card title="编排上下文">
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="selectedAssistantId"
            :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
          />

          <a-form layout="vertical">
            <a-form-item label="执行模式">
              <a-select
                v-model:value="executionMode"
                :options="[
                  { label: '顺序图', value: 'SEQUENTIAL_GRAPH' },
                  { label: '阶段并行', value: 'PARALLEL_STAGES' },
                ]"
              />
            </a-form-item>
          </a-form>

          <a-space wrap>
            <a-button :type="connectMode ? 'primary' : 'default'" @click="toggleConnectMode">
              {{ connectMode ? '退出连线模式' : '新增分支连线' }}
            </a-button>
            <a-button @click="resetToLinearFlow">重建主链</a-button>
          </a-space>

          <a-space wrap>
            <a-button @click="zoomOut">缩小</a-button>
            <a-button @click="resetViewport">重置视角</a-button>
            <a-button @click="zoomIn">放大</a-button>
          </a-space>

          <a-button type="primary" @click="submitSave">保存编排</a-button>
        </a-space>
      </a-card>

      <a-card title="节点目录">
        <a-list :data-source="workingNodes">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedNodeId === item.nodeId }"
              draggable="true"
              @click="handleNodeClick(item.nodeId)"
              @dragstart="draggedNodeId = item.nodeId"
              @dragover.prevent
              @drop.prevent="reorderNode(draggedNodeId, item.nodeId)"
              @dragend="draggedNodeId = ''"
            >
              <a-list-item-meta
                :title="item.nodeName"
                :description="pendingSourceNodeId === item.nodeId ? '等待选择目标节点' : item.description"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>

      <a-card title="连线目录">
        <a-list :data-source="workingEdges">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedEdgeId === item.edgeId }"
              @click="selectEdge(item.edgeId)"
            >
              <a-list-item-meta
                :title="`${nodeById(item.fromNodeId)?.nodeName ?? item.fromNodeId} -> ${nodeById(item.toNodeId)?.nodeName ?? item.toNodeId}`"
                :description="item.condition"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="17">
      <a-space direction="vertical" style="width: 100%" size="large">
        <a-card v-if="current" :title="current.assistantName">
          <template #extra>
            <a-space>
              <a-tag color="blue">{{ executionMode }}</a-tag>
              <a-tag v-if="connectMode" color="gold">
                {{ pendingSourceNodeId ? '请选择目标节点' : '请选择源节点' }}
              </a-tag>
            </a-space>
          </template>

          <div
            class="graph-editor"
            :class="{ 'graph-editor--panning': isPanning }"
            @wheel="handleWheel"
            @mousedown="handleViewportMouseDown"
          >
            <div class="graph-editor__viewport">
              <div
                class="graph-editor__scene"
                :style="[canvasStyle, { width: `${canvasWidth}px`, height: `${CANVAS_HEIGHT}px` }]"
              >
                <svg class="graph-editor__svg" :width="canvasWidth" :height="CANVAS_HEIGHT">
                  <g v-for="connector in connectorModels" :key="connector.edgeId">
                    <path
                      class="graph-editor__path-hit"
                      :d="connector.d"
                      fill="none"
                      @click="selectEdge(connector.edgeId)"
                    />
                    <path
                      class="graph-editor__path"
                      :class="{ 'graph-editor__path--active': selectedEdgeId === connector.edgeId }"
                      :d="connector.d"
                      fill="none"
                    />
                  </g>
                </svg>

                <div
                  v-for="node in workingNodes"
                  :key="node.nodeId"
                  class="graph-node"
                  :class="{
                    'graph-node--active': selectedNodeId === node.nodeId,
                    'graph-node--pending': pendingSourceNodeId === node.nodeId,
                  }"
                  :style="{
                    left: `${nodePositions.find((item) => item.nodeId === node.nodeId)?.left ?? 0}px`,
                    top: `${nodePositions.find((item) => item.nodeId === node.nodeId)?.top ?? 0}px`,
                  }"
                  draggable="true"
                  @click="handleNodeClick(node.nodeId)"
                  @dragstart="draggedNodeId = node.nodeId"
                  @dragover.prevent
                  @drop.prevent="reorderNode(draggedNodeId, node.nodeId)"
                  @dragend="draggedNodeId = ''"
                >
                  <span class="graph-node__eyebrow">智能体节点</span>
                  <strong class="graph-node__title">{{ node.nodeName }}</strong>
                  <span class="graph-node__description">{{ node.description }}</span>
                  <span class="graph-node__resources">
                    {{ node.resourceIds.length ? nodeResourceNames(node.resourceIds) : '无直接资源绑定' }}
                  </span>
                  <span class="graph-node__actions">
                    <a-button size="small" @click.stop="moveNode(node.nodeId, -1)">左移</a-button>
                    <a-button size="small" @click.stop="moveNode(node.nodeId, 1)">右移</a-button>
                  </span>
                </div>

                <button
                  v-for="connector in connectorModels"
                  :key="`${connector.edgeId}-label`"
                  class="graph-edge-chip"
                  :class="{ 'graph-edge-chip--active': selectedEdgeId === connector.edgeId }"
                  :style="{ left: `${connector.labelX}px`, top: `${connector.labelY}px` }"
                  type="button"
                  @click="selectEdge(connector.edgeId)"
                >
                  {{ workingEdges.find((item) => item.edgeId === connector.edgeId)?.handoffPolicy }}
                </button>
              </div>
            </div>
          </div>
        </a-card>

        <a-row :gutter="[16, 16]">
          <a-col :span="12">
            <a-card v-if="selectedNode" title="节点属性">
              <a-form layout="vertical">
                <a-form-item label="节点名称">
                  <a-input :value="selectedNode.nodeName" disabled />
                </a-form-item>
                <a-form-item label="节点说明">
                  <a-textarea v-model:value="selectedNode.description" :rows="4" />
                </a-form-item>
                <a-form-item label="绑定资源">
                  <a-input
                    :value="selectedNode.resourceIds.length ? nodeResourceNames(selectedNode.resourceIds) : '无直接资源绑定'"
                    disabled
                  />
                </a-form-item>
              </a-form>
            </a-card>
          </a-col>

          <a-col :span="12">
            <a-card v-if="selectedEdge" title="连线属性">
              <template #extra>
                <a-button danger size="small" @click="deleteSelectedEdge">删除连线</a-button>
              </template>

              <a-form layout="vertical">
                <a-form-item label="交接路径">
                  <a-input
                    :value="`${nodeById(selectedEdge.fromNodeId)?.nodeName ?? selectedEdge.fromNodeId} -> ${nodeById(selectedEdge.toNodeId)?.nodeName ?? selectedEdge.toNodeId}`"
                    disabled
                  />
                </a-form-item>
                <a-form-item label="流转条件">
                  <a-input v-model:value="selectedEdge.condition" placeholder="例如：命中升级意图" />
                </a-form-item>
                <a-form-item label="交接策略">
                  <a-select
                    v-model:value="selectedEdge.handoffPolicy"
                    :options="[
                      { label: '直接交接', value: 'direct-handoff' },
                      { label: '条件交接', value: 'conditional-handoff' },
                      { label: '人工确认', value: 'manual-gate' },
                    ]"
                  />
                </a-form-item>
              </a-form>
            </a-card>
          </a-col>
        </a-row>
      </a-space>
    </a-col>
  </a-row>
</template>
