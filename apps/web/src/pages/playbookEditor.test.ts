import { describe, expect, it } from 'vitest';
import {
  createNodeDraft,
  createStarterPlaybookGraph,
  parseImportedPlaybookGraph,
  removeNodeAndConnectedEdges,
  serializeEditorSnapshot,
  toEditorSnapshot,
  validatePlaybookEditorGraph,
} from './playbookEditor';

describe('playbookEditor helpers', () => {
  it('round-trips playbook graph snapshots with layout', () => {
    const starter = createStarterPlaybookGraph();
    const serialized = serializeEditorSnapshot(starter);
    const roundTrip = toEditorSnapshot(serialized as any);

    expect(roundTrip.entryNodeKey).toBe('start');
    expect(roundTrip.nodes[0].layout).toEqual({ x: 120, y: 140 });
    expect(roundTrip.edges[0].edgeKey).toBe('start_to_finish');
  });

  it('creates unique nodes with default layout and type defaults', () => {
    const starter = createStarterPlaybookGraph();
    const node = createNodeDraft('STEP', starter.nodes, { x: 720, y: 220 });

    expect(node.nodeKey).toBe('step');
    expect(node.layout).toEqual({ x: 720, y: 220 });
    expect(node.scriptRef).toBe('playbook.step');
    expect(node.scriptVersion).toBe('v1');
  });

  it('removes connected edges when deleting a node', () => {
    const starter = createStarterPlaybookGraph();
    const snapshot = {
      ...starter,
      nodes: [
        ...starter.nodes,
        createNodeDraft('END', starter.nodes, { x: 860, y: 140 }),
      ],
      edges: [
        ...starter.edges,
        {
          edgeKey: 'finish_to_end',
          sourceNodeKey: 'finish',
          targetNodeKey: 'end',
          routeKey: null,
          label: null,
          defaultEdge: true,
        },
      ],
    };

    const trimmed = removeNodeAndConnectedEdges(snapshot, 'finish');

    expect(trimmed.nodes.map((node) => node.nodeKey)).toEqual(['start', 'end']);
    expect(trimmed.edges).toHaveLength(0);
  });

  it('rejects invalid graph definitions before save', () => {
    const starter = createStarterPlaybookGraph();
    starter.nodes[0].scriptRef = null;

    expect(validatePlaybookEditorGraph({
      entryNodeKey: starter.entryNodeKey,
      nodes: starter.nodes,
      edges: starter.edges,
      allowHumanTask: true,
      allowExternalInteraction: true,
    })).toContain('STEP 节点 start');
  });

  it('parses imported graph JSON with missing layout fallback', () => {
    const imported = parseImportedPlaybookGraph(JSON.stringify({
      entryNodeKey: 'start',
      nodes: [
        {
          nodeKey: 'start',
          nodeName: '开始',
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
      ],
      edges: [],
    }));

    expect(imported.nodes[0].layout).toEqual({ x: 96, y: 108 });
  });
});
