import { describe, expect, it } from 'vitest';
import { pagePathByKey, resolvePageKeyFromPath } from './navigation';

describe('navigation routing helpers', () => {
  it('maps page keys to stable console paths', () => {
    expect(pagePathByKey.domain).toBe('/console/domains');
    expect(pagePathByKey['knowledge-library']).toBe('/console/knowledge');
    expect(pagePathByKey['resource-library']).toBe('/console/resources');
    expect(pagePathByKey['playbook-editor']).toBe('/console/playbooks/editor');
    expect(pagePathByKey.runtime).toBe('/console/runtime');
  });

  it('resolves page keys from console paths', () => {
    expect(resolvePageKeyFromPath('/console/domains')).toBe('domain');
    expect(resolvePageKeyFromPath('/console/knowledge')).toBe('knowledge-library');
    expect(resolvePageKeyFromPath('/console/resources')).toBe('resource-library');
    expect(resolvePageKeyFromPath('/console/playbooks/editor')).toBe('playbook-editor');
    expect(resolvePageKeyFromPath('/console/runtime/')).toBe('runtime');
  });

  it('falls back to the default page for unknown paths', () => {
    expect(resolvePageKeyFromPath('/unknown')).toBe('domain');
  });
});
