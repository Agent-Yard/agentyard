import { describe, expect, it } from 'vitest';
import {
  hasActiveKnowledgeOperations,
  isKnowledgeImportJobActive,
  isKnowledgeSnapshotActive,
  knowledgeSourceLabel,
  knowledgeStatusColor,
} from './knowledgeWorkspace';

describe('knowledgeWorkspace helpers', () => {
  it('treats queued and running knowledge jobs as active operations', () => {
    expect(isKnowledgeImportJobActive('QUEUED')).toBe(true);
    expect(isKnowledgeImportJobActive('RUNNING')).toBe(true);
    expect(isKnowledgeImportJobActive('FAILED')).toBe(false);
    expect(isKnowledgeSnapshotActive('QUEUED')).toBe(true);
    expect(isKnowledgeSnapshotActive('RUNNING')).toBe(true);
    expect(isKnowledgeSnapshotActive('READY')).toBe(false);
  });

  it('detects active operations across import jobs and snapshots', () => {
    expect(hasActiveKnowledgeOperations(
      [{ status: 'FAILED' }, { status: 'RUNNING' }] as any,
      [{ status: 'READY' }] as any,
    )).toBe(true);

    expect(hasActiveKnowledgeOperations(
      [{ status: 'FAILED' }] as any,
      [{ status: 'READY' }, { status: 'FAILED' }] as any,
    )).toBe(false);
  });

  it('maps source labels and status colors for the workspace', () => {
    expect(knowledgeSourceLabel('FILE_UPLOAD')).toBe('文件上传');
    expect(knowledgeSourceLabel('URL')).toBe('URL');
    expect(knowledgeStatusColor('SUCCEEDED')).toBe('green');
    expect(knowledgeStatusColor('READY')).toBe('green');
    expect(knowledgeStatusColor('FAILED')).toBe('red');
    expect(knowledgeStatusColor('RUNNING')).toBe('blue');
  });
});
