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
    if (!edge.routeKey.trim()) {
      return `边 ${edge.edgeKey} 必须设置 routeKey。`;
    }
    if (edge.defaultEdge && edge.routeKey !== 'default') {
      return `默认边 ${edge.edgeKey} 必须使用 routeKey=default。`;
    }
    if (!edge.defaultEdge && edge.routeKey === 'default') {
      return `非默认边 ${edge.edgeKey} 不能使用 routeKey=default。`;
    }
  }

  for (const node of nodes) {
    const outgoing = edges.filter((edge) => edge.sourceNodeKey === node.nodeKey);
    if (node.nodeType === 'START') {
      if (outgoing.length !== 1) {
        return 'START 节点必须且只能有一条出口边。';
      }
      const [startEdge] = outgoing;
      if (!startEdge.defaultEdge || startEdge.routeKey !== 'default') {
        return 'START 节点的出口边必须是 routeKey=default 的默认边。';
      }
    }
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
    const routeKeys = new Set<string>();
    for (const edge of outgoing) {
      if (routeKeys.has(edge.routeKey)) {
        return `节点 ${node.nodeName} 存在重复 routeKey：${edge.routeKey}`;
      }
      routeKeys.add(edge.routeKey);
    }
    const defaultCount = outgoing.filter((edge) => edge.defaultEdge).length;
    if (defaultCount > 1) {
      return `节点 ${node.nodeName} 存在多条默认边。`;
    }
    if (outgoing.length > 1 && defaultCount === 0) {
      return `分支节点 ${node.nodeName} 必须配置一条默认边。`;
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
