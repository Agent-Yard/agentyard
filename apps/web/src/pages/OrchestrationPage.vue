<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { validateOrchestrationGraph } from '../orchestration/graph';
import type {
  Assistant,
  AssistantOrchestration,
  OrchestrationEdge,
  OrchestrationNode,
  OrchestrationNodeType,
  Resource,
  UpdateOrchestrationPayload,
} from '../types';

const props = defineProps<{
  assistants: Assistant[];
  orchestrations: AssistantOrchestration[];
  resources: Resource[];
}>();

const emit = defineEmits<{
  saveOrchestration: [payload: { assistantId: string; data: UpdateOrchestrationPayload }];
}>();

const selectedAssistantId = ref('');
const executionMode = ref('GRAPH');
const workingNodes = ref<OrchestrationNode[]>([]);
const workingEdges = ref<OrchestrationEdge[]>([]);
const selectedNodeKey = ref('');
const selectedEdgeKey = ref('');
const validationError = ref('');

const nodeTypeOptions: Array<{ label: string; value: OrchestrationNodeType }> = [
  { label: '开始节点', value: 'START' },
  { label: '智能体节点', value: 'AGENT' },
  { label: '人工节点', value: 'HUMAN' },
  { label: '结束节点', value: 'END' },
];

const current = computed(() =>
  props.orchestrations.find((item) => item.assistantId === selectedAssistantId.value) ?? props.orchestrations[0],
);

const currentAssistant = computed(() =>
  props.assistants.find((item) => item.id === selectedAssistantId.value) ?? props.assistants[0],
);

const assistantAgents = computed(() => currentAssistant.value?.agents ?? []);
const selectedNode = computed(() => workingNodes.value.find((item) => item.nodeKey === selectedNodeKey.value) ?? null);
const selectedEdge = computed(() => workingEdges.value.find((item) => item.edgeKey === selectedEdgeKey.value) ?? null);

const graphRows = computed(() =>
  workingNodes.value.map((node) => ({
    ...node,
    outgoing: workingEdges.value.filter((edge) => edge.sourceNodeKey === node.nodeKey),
    incoming: workingEdges.value.filter((edge) => edge.targetNodeKey === node.nodeKey),
  })),
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
      selectedNodeKey.value = '';
      selectedEdgeKey.value = '';
      return;
    }
    executionMode.value = value.executionMode;
    workingNodes.value = value.nodes.map((node) => ({
      ...node,
      agentId: node.agentId ?? null,
      humanNode: node.humanNode ? { ...node.humanNode } : null,
    }));
    workingEdges.value = value.edges.map((edge) => ({ ...edge }));
    selectedNodeKey.value = value.nodes[0]?.nodeKey ?? '';
    selectedEdgeKey.value = value.edges[0]?.edgeKey ?? '';
    validationError.value = '';
  },
  { immediate: true },
);

watch(
  workingNodes,
  (nodes) => {
    if (selectedNodeKey.value && !nodes.some((item) => item.nodeKey === selectedNodeKey.value)) {
      selectedNodeKey.value = nodes[0]?.nodeKey ?? '';
    }
  },
  { deep: true },
);

watch(
  workingEdges,
  (edges) => {
    if (selectedEdgeKey.value && !edges.some((item) => item.edgeKey === selectedEdgeKey.value)) {
      selectedEdgeKey.value = edges[0]?.edgeKey ?? '';
    }
  },
  { deep: true },
);

function nodeLabel(nodeKey: string) {
  const node = workingNodes.value.find((item) => item.nodeKey === nodeKey);
  return node?.nodeName ?? nodeKey;
}

function resourceNamesForAgent(agentId: string | null) {
  if (!agentId) {
    return '无';
  }
  const agent = assistantAgents.value.find((item) => item.id === agentId);
  if (!agent) {
    return '无';
  }
  const names = agent.bindings.map((binding) => props.resources.find((item) => item.id === binding.resourceId)?.name ?? binding.resourceId);
  return names.length ? names.join(' / ') : '无';
}

function addNode(nodeType: OrchestrationNodeType) {
  const key = `node-${Math.random().toString(16).slice(2, 8)}`;
  const fallbackAgentId = nodeType === 'AGENT' ? assistantAgents.value[0]?.id ?? null : null;
  const created: OrchestrationNode = {
    nodeKey: key,
    nodeName: nodeType === 'AGENT'
      ? '新智能体节点'
      : nodeType === 'HUMAN'
        ? '新人工节点'
        : nodeType === 'START'
          ? '新开始节点'
          : '新结束节点',
    nodeType,
    description: '请补充节点说明。',
    agentId: fallbackAgentId,
    humanNode: nodeType === 'HUMAN'
      ? {
          title: '人工待办',
          instruction: '请人工处理这个节点。',
          expectedAction: 'CONFIRM',
          resumeRouteKey: 'confirmed',
        }
      : null,
  };
  workingNodes.value = [...workingNodes.value, created];
  selectedNodeKey.value = created.nodeKey;
}

function addEdge() {
  if (workingNodes.value.length < 2) {
    return;
  }
  const created: OrchestrationEdge = {
    edgeKey: `edge-${Math.random().toString(16).slice(2, 8)}`,
    sourceNodeKey: workingNodes.value[0]?.nodeKey ?? '',
    targetNodeKey: workingNodes.value[1]?.nodeKey ?? '',
    routeKey: null,
    label: '新分支',
    defaultEdge: false,
  };
  workingEdges.value = [...workingEdges.value, created];
  selectedEdgeKey.value = created.edgeKey;
}

function deleteSelectedNode() {
  if (!selectedNode.value) {
    return;
  }
  const removingKey = selectedNode.value.nodeKey;
  workingNodes.value = workingNodes.value.filter((item) => item.nodeKey !== removingKey);
  workingEdges.value = workingEdges.value.filter((item) => item.sourceNodeKey !== removingKey && item.targetNodeKey !== removingKey);
}

function deleteSelectedEdge() {
  if (!selectedEdge.value) {
    return;
  }
  workingEdges.value = workingEdges.value.filter((item) => item.edgeKey !== selectedEdge.value?.edgeKey);
}

function submitSave() {
  if (!current.value) {
    return;
  }
  validationError.value = validateOrchestrationGraph(workingNodes.value, workingEdges.value);
  if (validationError.value) {
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
              <a-input v-model:value="executionMode" />
            </a-form-item>
          </a-form>
          <a-space wrap>
            <a-button v-for="option in nodeTypeOptions" :key="option.value" @click="addNode(option.value)">
              新增{{ option.label }}
            </a-button>
            <a-button @click="addEdge">新增边</a-button>
          </a-space>
          <a-alert
            v-if="validationError"
            type="error"
            show-icon
            :message="validationError"
          />
          <a-button type="primary" @click="submitSave">保存编排</a-button>
        </a-space>
      </a-card>

      <a-card title="节点列表">
        <a-list :data-source="workingNodes">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedNodeKey === item.nodeKey }"
              @click="selectedNodeKey = item.nodeKey"
            >
              <a-list-item-meta
                :title="`${item.nodeName} · ${item.nodeType}`"
                :description="item.description"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>

      <a-card title="边列表">
        <a-list :data-source="workingEdges">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedEdgeKey === item.edgeKey }"
              @click="selectedEdgeKey = item.edgeKey"
            >
              <a-list-item-meta
                :title="`${nodeLabel(item.sourceNodeKey)} -> ${nodeLabel(item.targetNodeKey)}`"
                :description="`${item.label}${item.routeKey ? ` · route=${item.routeKey}` : ''}${item.defaultEdge ? ' · 默认边' : ''}`"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="17">
      <a-space direction="vertical" style="width: 100%" size="large">
        <a-card v-if="current" :title="current.assistantName">
          <a-table
            :pagination="false"
            :data-source="graphRows"
            row-key="nodeKey"
            size="small"
          >
            <a-table-column title="节点" key="nodeName">
              <template #default="{ record }">
                <a-space direction="vertical" size="small">
                  <strong>{{ record.nodeName }}</strong>
                  <a-tag>{{ record.nodeType }}</a-tag>
                </a-space>
              </template>
            </a-table-column>
            <a-table-column title="说明" data-index="description" key="description" />
            <a-table-column title="资源 / 人工配置" key="config">
              <template #default="{ record }">
                <span v-if="record.nodeType === 'AGENT'">
                  {{ resourceNamesForAgent(record.agentId) }}
                </span>
                <span v-else-if="record.nodeType === 'HUMAN'">
                  {{ record.humanNode?.title }} / {{ record.humanNode?.expectedAction }}
                </span>
                <span v-else>系统节点</span>
              </template>
            </a-table-column>
            <a-table-column title="出口边" key="outgoing">
              <template #default="{ record }">
                <a-space wrap>
                  <a-tag v-for="edge in record.outgoing" :key="edge.edgeKey">
                    {{ edge.label }} -> {{ nodeLabel(edge.targetNodeKey) }}
                  </a-tag>
                </a-space>
              </template>
            </a-table-column>
          </a-table>
        </a-card>

        <a-row :gutter="[16, 16]">
          <a-col :span="12">
            <a-card v-if="selectedNode" title="节点属性">
              <template #extra>
                <a-button danger size="small" @click="deleteSelectedNode">删除节点</a-button>
              </template>
              <a-form layout="vertical">
                <a-form-item label="节点 Key">
                  <a-input v-model:value="selectedNode.nodeKey" />
                </a-form-item>
                <a-form-item label="节点名称">
                  <a-input v-model:value="selectedNode.nodeName" />
                </a-form-item>
                <a-form-item label="节点类型">
                  <a-select v-model:value="selectedNode.nodeType" :options="nodeTypeOptions" />
                </a-form-item>
                <a-form-item label="说明">
                  <a-textarea v-model:value="selectedNode.description" :rows="4" />
                </a-form-item>
                <a-form-item v-if="selectedNode.nodeType === 'AGENT'" label="绑定智能体">
                  <a-select
                    v-model:value="selectedNode.agentId"
                    :options="assistantAgents.map((item) => ({ label: `${item.name} · ${item.role}`, value: item.id }))"
                  />
                </a-form-item>
                <template v-if="selectedNode.nodeType === 'HUMAN' && selectedNode.humanNode">
                  <a-form-item label="人工待办标题">
                    <a-input v-model:value="selectedNode.humanNode.title" />
                  </a-form-item>
                  <a-form-item label="人工说明">
                    <a-textarea v-model:value="selectedNode.humanNode.instruction" :rows="3" />
                  </a-form-item>
                  <a-row :gutter="[16, 16]">
                    <a-col :span="12">
                      <a-form-item label="预期动作">
                        <a-input v-model:value="selectedNode.humanNode.expectedAction" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item label="恢复 Route Key">
                        <a-input v-model:value="selectedNode.humanNode.resumeRouteKey" />
                      </a-form-item>
                    </a-col>
                  </a-row>
                </template>
              </a-form>
            </a-card>
          </a-col>

          <a-col :span="12">
            <a-card v-if="selectedEdge" title="边属性">
              <template #extra>
                <a-button danger size="small" @click="deleteSelectedEdge">删除边</a-button>
              </template>
              <a-form layout="vertical">
                <a-form-item label="边 Key">
                  <a-input v-model:value="selectedEdge.edgeKey" />
                </a-form-item>
                <a-form-item label="源节点">
                  <a-select
                    v-model:value="selectedEdge.sourceNodeKey"
                    :options="workingNodes.map((item) => ({ label: item.nodeName, value: item.nodeKey }))"
                  />
                </a-form-item>
                <a-form-item label="目标节点">
                  <a-select
                    v-model:value="selectedEdge.targetNodeKey"
                    :options="workingNodes.map((item) => ({ label: item.nodeName, value: item.nodeKey }))"
                  />
                </a-form-item>
                <a-form-item label="边标签">
                  <a-input v-model:value="selectedEdge.label" />
                </a-form-item>
                <a-form-item label="Route Key">
                  <a-input v-model:value="selectedEdge.routeKey" placeholder="例如 faq / after_sales / confirmed" />
                </a-form-item>
                <a-form-item label="默认边">
                  <a-switch v-model:checked="selectedEdge.defaultEdge" />
                </a-form-item>
              </a-form>
            </a-card>
          </a-col>
        </a-row>
      </a-space>
    </a-col>
  </a-row>
</template>
