import type { Playbook, PlaybookEdge, PlaybookNode, PlaybookNodeLayout, PlaybookNodeType } from '../types';

export interface PlaybookEditorNode extends PlaybookNode {
  configText: string;
}

export interface PlaybookEditorSnapshot {
  entryNodeKey: string;
  nodes: PlaybookEditorNode[];
  edges: PlaybookEdge[];
}

const PLAYBOOK_NODE_LABEL: Record<PlaybookNodeType, string> = {
  STEP: '步骤',
  TOOL_TASK: '工具任务',
  HUMAN_TASK: '人工节点',
  EXTERNAL_INTERACTION: '站外交互',
  END: '结束',
};

function defaultNodeLayout(index: number): PlaybookNodeLayout {
  return {
    x: 96 + ((index % 3) * 288),
    y: 108 + (Math.floor(index / 3) * 216),
  };
}

function defaultStepConfig(scriptRef = 'playbook.step', scriptVersion = 'v1') {
  return {
    scriptVersions: {
      [scriptVersion]: {
        runtime: 'python',
        code: "result = {'statePatch': {}, 'routeKey': None}",
      },
    },
    scriptRef,
  };
}

function normalizeNodeLayout(layout: PlaybookNodeLayout | null | undefined, index: number): PlaybookNodeLayout {
  if (!layout) {
    return defaultNodeLayout(index);
  }
  return {
    x: Number.isFinite(layout.x) ? layout.x : 0,
    y: Number.isFinite(layout.y) ? layout.y : 0,
  };
}

function normalizeNodeConfigText(config: Record<string, unknown> | null | undefined) {
  return JSON.stringify(config ?? {}, null, 2);
}

function slugForNodeType(nodeType: PlaybookNodeType) {
  switch (nodeType) {
    case 'STEP':
      return 'step';
    case 'TOOL_TASK':
      return 'tool';
    case 'HUMAN_TASK':
      return 'human';
    case 'EXTERNAL_INTERACTION':
      return 'external';
    case 'END':
      return 'end';
  }
}

function nextUniqueKey(base: string, existing: string[]) {
  if (!existing.includes(base)) {
    return base;
  }
  let index = 2;
  while (existing.includes(`${base}_${index}`)) {
    index += 1;
  }
  return `${base}_${index}`;
}

export function createStarterPlaybookGraph(): PlaybookEditorSnapshot {
  const startNode: PlaybookEditorNode = {
    nodeKey: 'start',
    nodeName: '开始步骤',
    nodeType: 'STEP',
    description: '',
    scriptRef: 'playbook.start',
    scriptVersion: 'v1',
    toolId: null,
    toolOperation: null,
    config: defaultStepConfig('playbook.start'),
    configText: normalizeNodeConfigText(defaultStepConfig('playbook.start')),
    layout: { x: 120, y: 140 },
  };
  const finishNode: PlaybookEditorNode = {
    nodeKey: 'finish',
    nodeName: '结束',
    nodeType: 'END',
    description: '',
    scriptRef: null,
    scriptVersion: null,
    toolId: null,
    toolOperation: null,
    config: {},
    configText: '{}',
    layout: { x: 480, y: 140 },
  };
  return {
    entryNodeKey: startNode.nodeKey,
    nodes: [startNode, finishNode],
    edges: [
      {
        edgeKey: 'start_to_finish',
        sourceNodeKey: 'start',
        targetNodeKey: 'finish',
        routeKey: null,
        label: null,
        defaultEdge: true,
      },
    ],
  };
}

export function toEditorSnapshot(playbook: Pick<Playbook, 'entryNodeKey' | 'nodes' | 'edges'>): PlaybookEditorSnapshot {
  return {
    entryNodeKey: playbook.entryNodeKey,
    nodes: playbook.nodes.map((node, index) => ({
      ...node,
      layout: normalizeNodeLayout(node.layout, index),
      configText: normalizeNodeConfigText(node.config),
    })),
    edges: playbook.edges.map((edge) => ({ ...edge })),
  };
}

export function serializeEditorSnapshot(snapshot: PlaybookEditorSnapshot): {
  entryNodeKey: string;
  nodes: PlaybookNode[];
  edges: PlaybookEdge[];
} {
  return {
    entryNodeKey: snapshot.entryNodeKey,
    nodes: snapshot.nodes.map((node) => ({
      nodeKey: node.nodeKey,
      nodeName: node.nodeName,
      nodeType: node.nodeType,
      description: node.description,
      scriptRef: node.scriptRef,
      scriptVersion: node.scriptVersion,
      toolId: node.toolId,
      toolOperation: node.toolOperation,
      config: JSON.parse(node.configText || '{}') as Record<string, unknown>,
      layout: {
        x: Math.round(node.layout.x),
        y: Math.round(node.layout.y),
      },
    })),
    edges: snapshot.edges.map((edge) => ({
      edgeKey: edge.edgeKey,
      sourceNodeKey: edge.sourceNodeKey,
      targetNodeKey: edge.targetNodeKey,
      routeKey: normalizeNullable(edge.routeKey),
      label: normalizeNullable(edge.label),
      defaultEdge: edge.defaultEdge,
    })),
  };
}

export function createNodeDraft(
  nodeType: PlaybookNodeType,
  existingNodes: Array<Pick<PlaybookNode, 'nodeKey'>>,
  layout: PlaybookNodeLayout,
): PlaybookEditorNode {
  const nodeKey = nextUniqueKey(slugForNodeType(nodeType), existingNodes.map((item) => item.nodeKey));
  const nodeName = `${PLAYBOOK_NODE_LABEL[nodeType]} ${existingNodes.length + 1}`;

  switch (nodeType) {
    case 'STEP': {
      const config = defaultStepConfig();
      return {
        nodeKey,
        nodeName,
        nodeType,
        description: '',
        scriptRef: 'playbook.step',
        scriptVersion: 'v1',
        toolId: null,
        toolOperation: null,
        config,
        configText: normalizeNodeConfigText(config),
        layout,
      };
    }
    case 'TOOL_TASK':
      return {
        nodeKey,
        nodeName,
        nodeType,
        description: '',
        scriptRef: null,
        scriptVersion: null,
        toolId: null,
        toolOperation: null,
        config: {},
        configText: '{}',
        layout,
      };
    case 'HUMAN_TASK':
    case 'EXTERNAL_INTERACTION':
    case 'END':
      return {
        nodeKey,
        nodeName,
        nodeType,
        description: '',
        scriptRef: null,
        scriptVersion: null,
        toolId: null,
        toolOperation: null,
        config: {},
        configText: '{}',
        layout,
      };
  }
}

export function createEdgeDraft(
  sourceNodeKey: string,
  targetNodeKey: string,
  edges: PlaybookEdge[],
  patch?: Partial<PlaybookEdge>,
): PlaybookEdge {
  const edgeKey = nextUniqueKey(`${sourceNodeKey}_to_${targetNodeKey}`, edges.map((item) => item.edgeKey));
  return {
    edgeKey,
    sourceNodeKey,
    targetNodeKey,
    routeKey: normalizeNullable(patch?.routeKey ?? null),
    label: normalizeNullable(patch?.label ?? null),
    defaultEdge: patch?.defaultEdge ?? true,
  };
}

export function removeNodeAndConnectedEdges(snapshot: PlaybookEditorSnapshot, nodeKey: string): PlaybookEditorSnapshot {
  const nodes = snapshot.nodes.filter((node) => node.nodeKey !== nodeKey);
  const edges = snapshot.edges.filter((edge) => edge.sourceNodeKey !== nodeKey && edge.targetNodeKey !== nodeKey);
  const entryNodeKey = snapshot.entryNodeKey === nodeKey ? (nodes[0]?.nodeKey ?? '') : snapshot.entryNodeKey;
  return {
    entryNodeKey,
    nodes,
    edges,
  };
}

export function validatePlaybookEditorGraph(params: {
  entryNodeKey: string;
  nodes: PlaybookEditorNode[];
  edges: PlaybookEdge[];
  allowHumanTask: boolean;
  allowExternalInteraction: boolean;
}): string | null {
  if (!params.nodes.length) {
    return 'Playbook 必须至少包含一个节点。';
  }

  const nodeKeys = new Set<string>();
  const edgeKeys = new Set<string>();
  const parsedConfigs = new Map<string, Record<string, unknown>>();

  for (const node of params.nodes) {
    if (!node.nodeKey.trim()) {
      return '节点 Key 不能为空。';
    }
    if (!node.nodeName.trim()) {
      return `节点 ${node.nodeKey} 名称不能为空。`;
    }
    if (nodeKeys.has(node.nodeKey)) {
      return `节点 Key 重复：${node.nodeKey}`;
    }
    nodeKeys.add(node.nodeKey);

    try {
      parsedConfigs.set(node.nodeKey, JSON.parse(node.configText || '{}') as Record<string, unknown>);
    } catch {
      return `节点 ${node.nodeKey} 的 config JSON 无法解析。`;
    }

    if (node.nodeType === 'STEP') {
      if (!node.scriptRef?.trim() || !node.scriptVersion?.trim()) {
        return `STEP 节点 ${node.nodeKey} 必须定义 scriptRef 和 scriptVersion。`;
      }
      const config = parsedConfigs.get(node.nodeKey) ?? {};
      const versions = config.scriptVersions;
      if (!versions || typeof versions !== 'object') {
        return `STEP 节点 ${node.nodeKey} 必须定义 config.scriptVersions。`;
      }
      const versionConfig = (versions as Record<string, unknown>)[node.scriptVersion];
      if (!versionConfig || typeof versionConfig !== 'object') {
        return `STEP 节点 ${node.nodeKey} 的 scriptVersion 未在 config.scriptVersions 中定义。`;
      }
      const code = (versionConfig as Record<string, unknown>).code;
      if (typeof code !== 'string' || !code.trim()) {
        return `STEP 节点 ${node.nodeKey} 的版本脚本必须定义非空 code。`;
      }
    }

    if (node.nodeType === 'TOOL_TASK' && (!node.toolId?.trim() || !node.toolOperation?.trim())) {
      return `TOOL_TASK 节点 ${node.nodeKey} 必须定义 toolId 和 toolOperation。`;
    }

    if (!params.allowHumanTask && node.nodeType === 'HUMAN_TASK') {
      return '当前 Playbook 已禁用人工节点，但图中仍存在 HUMAN_TASK 节点。';
    }

    if (!params.allowExternalInteraction && node.nodeType === 'EXTERNAL_INTERACTION') {
      return '当前 Playbook 已禁用站外交互节点，但图中仍存在 EXTERNAL_INTERACTION 节点。';
    }
  }

  if (!nodeKeys.has(params.entryNodeKey)) {
    return '入口节点必须引用现有节点。';
  }

  for (const edge of params.edges) {
    if (!edge.edgeKey.trim()) {
      return '边 Key 不能为空。';
    }
    if (edgeKeys.has(edge.edgeKey)) {
      return `边 Key 重复：${edge.edgeKey}`;
    }
    edgeKeys.add(edge.edgeKey);
    if (!nodeKeys.has(edge.sourceNodeKey) || !nodeKeys.has(edge.targetNodeKey)) {
      return `边 ${edge.edgeKey} 必须引用现有节点。`;
    }
  }

  return null;
}

export function parseImportedPlaybookGraph(raw: string): PlaybookEditorSnapshot {
  const parsed = JSON.parse(raw) as Partial<PlaybookEditorSnapshot>;
  if (!parsed || typeof parsed !== 'object') {
    throw new Error('导入内容必须是 JSON 对象。');
  }
  if (!Array.isArray(parsed.nodes) || !Array.isArray(parsed.edges) || typeof parsed.entryNodeKey !== 'string') {
    throw new Error('导入内容必须包含 entryNodeKey、nodes、edges。');
  }
  return {
    entryNodeKey: parsed.entryNodeKey,
    nodes: parsed.nodes.map((node, index) => {
      const typedNode = node as PlaybookNode;
      return {
        ...typedNode,
        description: typedNode.description ?? '',
        scriptRef: typedNode.scriptRef ?? null,
        scriptVersion: typedNode.scriptVersion ?? null,
        toolId: typedNode.toolId ?? null,
        toolOperation: typedNode.toolOperation ?? null,
        config: typedNode.config ?? {},
        configText: normalizeNodeConfigText(typedNode.config),
        layout: normalizeNodeLayout(typedNode.layout, index),
      };
    }),
    edges: parsed.edges.map((edge) => ({
      ...(edge as PlaybookEdge),
      routeKey: normalizeNullable((edge as PlaybookEdge).routeKey),
      label: normalizeNullable((edge as PlaybookEdge).label),
    })),
  };
}

export function normalizeNullable(value: string | null | undefined) {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}
