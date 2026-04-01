import { describe, expect, it } from 'vitest';
import type { OrchestrationEdge, OrchestrationNode } from '../types';
import { validateOrchestrationGraph } from './graph';

function baseNodes(): OrchestrationNode[] {
  return [
    { nodeKey: 'start', nodeName: '开始', nodeType: 'START', description: '入口', agentId: null, humanNode: null },
    { nodeKey: 'route', nodeName: '路由', nodeType: 'AGENT', description: '路由问题', agentId: 'agent-router', humanNode: null },
    {
      nodeKey: 'human-review',
      nodeName: '人工复核',
      nodeType: 'HUMAN',
      description: '人工处理',
      agentId: null,
      humanNode: {
        title: '人工待办',
        instruction: '请人工处理',
        expectedAction: '填写处理意见并恢复流程',
        resumeRouteKey: 'default',
      },
    },
    { nodeKey: 'end', nodeName: '结束', nodeType: 'END', description: '出口', agentId: null, humanNode: null },
  ];
}

function baseEdges(): OrchestrationEdge[] {
  return [
    { edgeKey: 'edge-start-route', sourceNodeKey: 'start', targetNodeKey: 'route', routeKey: 'default', label: '进入路由', defaultEdge: true },
    { edgeKey: 'edge-route-human', sourceNodeKey: 'route', targetNodeKey: 'human-review', routeKey: 'human_handoff', label: '转人工', defaultEdge: false },
    { edgeKey: 'edge-human-end', sourceNodeKey: 'human-review', targetNodeKey: 'end', routeKey: 'default', label: '人工完成', defaultEdge: true },
  ];
}

describe('validateOrchestrationGraph', () => {
  it('accepts a valid multi-agent graph', () => {
    expect(validateOrchestrationGraph(baseNodes(), baseEdges())).toBe('');
  });

  it('rejects unreachable nodes', () => {
    const nodes = baseNodes().concat({
      nodeKey: 'faq',
      nodeName: 'FAQ',
      nodeType: 'AGENT',
      description: '孤立节点',
      agentId: 'agent-faq',
      humanNode: null,
    });
    const edges = baseEdges().concat({
      edgeKey: 'edge-faq-end',
      sourceNodeKey: 'faq',
      targetNodeKey: 'end',
      routeKey: 'default',
      label: '孤立收口',
      defaultEdge: true,
    });

    expect(validateOrchestrationGraph(nodes, edges)).toContain('不可达节点');
  });

  it('rejects human node without action config', () => {
    const nodes = baseNodes().map((node) =>
      node.nodeKey === 'human-review'
        ? { ...node, humanNode: { title: '', instruction: '', expectedAction: '', resumeRouteKey: '' } }
        : node,
    );

    expect(validateOrchestrationGraph(nodes, baseEdges())).toContain('人工节点');
  });

  it('rejects a default edge without routeKey=default', () => {
    const edges = baseEdges().map((edge) =>
      edge.edgeKey === 'edge-human-end' ? { ...edge, routeKey: 'confirmed' } : edge,
    );

    expect(validateOrchestrationGraph(baseNodes(), edges)).toContain('必须使用 routeKey=default');
  });

  it('rejects start node with multiple outgoing edges', () => {
    const edges = baseEdges().concat({
      edgeKey: 'edge-start-extra',
      sourceNodeKey: 'start',
      targetNodeKey: 'end',
      routeKey: 'another',
      label: '额外分支',
      defaultEdge: false,
    });

    expect(validateOrchestrationGraph(baseNodes(), edges)).toContain('START 节点必须且只能有一条出口边');
  });

  it('rejects start node without default route', () => {
    const edges = baseEdges().map((edge) =>
      edge.edgeKey === 'edge-start-route' ? { ...edge, routeKey: 'sales', defaultEdge: false } : edge,
    );

    expect(validateOrchestrationGraph(baseNodes(), edges)).toContain('START 节点的出口边必须是 routeKey=default 的默认边');
  });
});
