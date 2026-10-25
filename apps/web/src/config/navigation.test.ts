import {describe,it,expect} from 'vitest';
import {pageMeta,resolvePageKeyFromPath} from './navigation';
describe('continuous reception navigation',()=>{it('defaults to reception and separates configuration',()=>{expect(resolvePageKeyFromPath('/console')).toBe('reception');expect(pageMeta.reception.section).toBe('work');expect(pageMeta.entries.section).toBe('configuration');});it('provides no domain or assistant hierarchy',()=>{expect(Object.keys(pageMeta)).not.toContain('assistant');expect(Object.keys(pageMeta)).not.toContain('domain');});});
