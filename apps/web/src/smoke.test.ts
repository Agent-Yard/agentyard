import { describe, expect, it } from 'vitest';
import { mockCatalogSummary } from './services/mock';

describe('lynxus web smoke', () => {
  it('contains seeded scenario data', () => {
    expect(mockCatalogSummary.scenarios[0]?.name).toBe('智能客服协同处理');
    expect(mockCatalogSummary.orchestrations[0]?.nodes.some((node) => node.nodeType === 'HUMAN')).toBe(true);
    expect(mockCatalogSummary.resourceCenter.totalResources).toBeGreaterThan(0);
  });
});
