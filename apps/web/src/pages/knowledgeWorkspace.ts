import type { KnowledgeImportJob, KnowledgeIndexSnapshot } from '../types';

export function isKnowledgeImportJobActive(status: KnowledgeImportJob['status']): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

export function isKnowledgeSnapshotActive(status: KnowledgeIndexSnapshot['status']): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

export function hasActiveKnowledgeOperations(
  importJobs: KnowledgeImportJob[],
  snapshots: KnowledgeIndexSnapshot[],
): boolean {
  return importJobs.some((item) => isKnowledgeImportJobActive(item.status))
    || snapshots.some((item) => isKnowledgeSnapshotActive(item.status));
}

export function knowledgeStatusColor(status: string): string {
  if (status === 'READY' || status === 'SUCCEEDED' || status === 'IMPORTED') {
    return 'green';
  }
  if (status === 'FAILED') {
    return 'red';
  }
  if (status === 'QUEUED' || status === 'RUNNING' || status === 'IMPORTING') {
    return 'blue';
  }
  return 'default';
}

export function knowledgeSourceLabel(sourceType: KnowledgeImportJob['sourceType'] | 'FILE_UPLOAD' | 'URL'): string {
  return sourceType === 'URL' ? 'URL' : '文件上传';
}
