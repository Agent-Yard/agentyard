import { describe, expect, it } from 'vitest';
import { pagePathByKey, resolvePageKeyFromPath } from './navigation';

describe('navigation routing helpers', () => {
  it('maps page keys to stable console paths', () => {
    expect(pagePathByKey.domain).toBe('/console/domains');
    expect(pagePathByKey.runtime).toBe('/console/runtime');
    expect(pagePathByKey['resource-create']).toBe('/console/resources/new');
  });

  it('resolves page keys from console paths', () => {
    expect(resolvePageKeyFromPath('/console/domains')).toBe('domain');
    expect(resolvePageKeyFromPath('/console/resources/new')).toBe('resource-create');
    expect(resolvePageKeyFromPath('/console/runtime/')).toBe('runtime');
  });

  it('falls back to the default page for unknown paths', () => {
    expect(resolvePageKeyFromPath('/unknown')).toBe('domain');
  });
});
