import { describe, expect, it } from 'vitest';
import { mockCatalogSummary } from './services/mock';

describe('lynxus web smoke', () => {
  it('contains seeded scenario data', () => {
    expect(mockCatalogSummary.scenarios[0]?.name).toBe('知识问答升级处理');
    expect(mockCatalogSummary.orchestrations[0]?.nodes.length).toBeGreaterThan(0);
    expect(mockCatalogSummary.resourceCenter.totalResources).toBeGreaterThan(0);
  });
});
