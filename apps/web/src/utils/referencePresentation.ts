import type { DeletionCascadeItem, ObjectReferenceRelation, ReferenceObjectType } from '../types';

const relationLabels: Record<string,string> = {ROLE_MODEL:'角色模型',ROLE_PRIVACY_MODEL:'隐私模型',ROLE_SKILL:'角色技能',ROLE_TOOL:'角色工具',ROLE_PLAYBOOK:'角色流程',ROLE_KNOWLEDGE:'角色知识',ENTRY_TEMPLATE:'接待入口模板',PUBLISHED_VERSION:'已发布版本',RESOURCE_VERSION:'资源版本',KNOWLEDGE_RELEASE:'知识版本'};
const targetTypeLabels: Record<string,string> = {ROLE_TEMPLATE:'角色模板',ENTRY:'入口',PLAYBOOK:'Playbook',RESOURCE:'资源',RESOURCE_VERSION:'资源版本',KNOWLEDGE_BASE:'知识库',KNOWLEDGE_RELEASE:'知识版本'};
const objectTypeLabels: Record<ReferenceObjectType,string> = {ROLE_TEMPLATE:'角色模板',PLAYBOOK:'Playbook',RESOURCE:'资源',KNOWLEDGE_BASE:'知识库'};

export function impactColor(relation: ObjectReferenceRelation) {
  return relation.impactLevel === 'BLOCKS_DELETION' ? 'red' : 'default';
}

export function impactLabel(relation: ObjectReferenceRelation) {
  return relation.impactLevel === 'BLOCKS_DELETION' ? '阻断删除' : '影响提示';
}

export function modeLabel(relation: ObjectReferenceRelation) {
  return relation.relationMode === 'DIRECT' ? '直接关系' : '间接关系';
}

export function relationLabel(relationKind: string) {
  return relationLabels[relationKind] ?? relationKind;
}

export function targetTypeLabel(targetType: string) {
  return targetTypeLabels[targetType] ?? targetType;
}

export function objectTypeLabel(objectType: ReferenceObjectType) {
  return objectTypeLabels[objectType] ?? objectType;
}

export function referenceContext(context: {
  targetType: string;
  releaseVersion?: string | null;
  resourceVersion?: string | null;
  knowledgeReleaseVersion?: string | null;
}) {
  const parts: string[] = [targetTypeLabel(context.targetType)];
  if (context.releaseVersion) {
    parts.push(`发布版本 ${context.releaseVersion}`);
  }
  if (context.resourceVersion) {
    parts.push(`资源版本 ${context.resourceVersion}`);
  }
  if (context.knowledgeReleaseVersion) {
    parts.push(`知识版本 ${context.knowledgeReleaseVersion}`);
  }
  return parts.join(' · ');
}

export function relationContext(relation: ObjectReferenceRelation) {
  return referenceContext(relation);
}

export function cascadeActionLabel(item: DeletionCascadeItem) {
  return item.action === 'REMOVE' ? '自动回收' : '会被删除';
}

export function cascadeActionColor(item: DeletionCascadeItem) {
  return item.action === 'REMOVE' ? 'orange' : 'red';
}

export function cascadeContext(item: DeletionCascadeItem) {
  return referenceContext(item);
}
