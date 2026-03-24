import type { OrchestrationEdge, OrchestrationNode } from '../types';

export function validateOrchestrationGraph(nodes: OrchestrationNode[], edges: OrchestrationEdge[]): string {
  const startNodes = nodes.filter((item) => item.nodeType === 'START');
  const endNodes = nodes.filter((item) => item.nodeType === 'END');
  if (startNodes.length !== 1) {
    return '编排图必须且只能有一个 START 节点。';
  }
  if (endNodes.length !== 1) {
    return '编排图必须且只能有一个 END 节点。';
  }

  const nodeKeys = new Set(nodes.map((item) => item.nodeKey));
  for (const edge of edges) {
    if (!nodeKeys.has(edge.sourceNodeKey) || !nodeKeys.has(edge.targetNodeKey)) {
      return `存在引用无效节点的边：${edge.edgeKey}`;
    }
  }

  for (const node of nodes) {
    const outgoing = edges.filter((edge) => edge.sourceNodeKey === node.nodeKey);
    if (node.nodeType === 'AGENT' && !node.agentId) {
      return `智能体节点 ${node.nodeName} 必须绑定 agentId。`;
    }
    if (node.nodeType === 'HUMAN') {
      if (!node.humanNode?.title || !node.humanNode.instruction || !node.humanNode.expectedAction || !node.humanNode.resumeRouteKey) {
        return `人工节点 ${node.nodeName} 的人工待办配置不完整。`;
      }
    }
    if (node.nodeType !== 'END' && outgoing.length === 0) {
      return `节点 ${node.nodeName} 没有出口边。`;
    }
  }

  const reachable = new Set<string>();
  const visit = (nodeKey: string) => {
    if (reachable.has(nodeKey)) {
      return;
    }
    reachable.add(nodeKey);
    for (const edge of edges.filter((item) => item.sourceNodeKey === nodeKey)) {
      visit(edge.targetNodeKey);
    }
  };
  visit(startNodes[0].nodeKey);
  const unreachable = nodes.filter((node) => !reachable.has(node.nodeKey));
  if (unreachable.length) {
    return `存在不可达节点：${unreachable.map((item) => item.nodeName).join(' / ')}`;
  }

  return '';
}
