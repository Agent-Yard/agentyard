import type { DeletionCascadeItem, ObjectReferenceRelation, ReferenceObjectType } from '../types';

const relationLabels: Record<string, string> = {
  DOMAIN_SCENARIO: '域内场景',
  DOMAIN_RESOURCE: '域内资源',
  DOMAIN_KNOWLEDGE_BASE: '域内知识库',
  SCENARIO_ASSISTANT: '场景助手',
  ASSISTANT_AGENT: '助手智能体',
  ASSISTANT_PLAYBOOK: '助手 Playbook',
  ASSISTANT_PRIVATE_RESOURCE: '助手私有资源',
  ASSISTANT_PRIVATE_KNOWLEDGE_BASE: '助手私有知识库',
  ASSISTANT_RELEASE: '助手发布快照',
  PLAYBOOK_ASSISTANT: '所属助手',
  PLAYBOOK_AGENT_ENABLED: '智能体启用',
  PLAYBOOK_RELEASE_FROZEN: '发布冻结 Playbook',
  AGENT_OVERRIDE_MODEL: '模型覆盖',
  AGENT_SKILL_ENABLED: '启用技能',
  AGENT_TOOL_ENABLED: '启用工具',
  AGENT_OVERRIDE_KNOWLEDGE_BASE: '知识库覆盖',
  AGENT_RELEASE_FROZEN: '发布冻结',
  ASSISTANT_DEFAULT_MODEL: '助手默认模型',
  RELEASE_FROZEN: '发布冻结资源',
  ASSISTANT_DEFAULT_KNOWLEDGE_BASE: '助手默认知识库',
  RELEASE_ASSISTANT_KNOWLEDGE: '助手发布冻结知识',
  RELEASE_AGENT_KNOWLEDGE: '智能体发布冻结知识',
  KNOWLEDGE_BASE_EFFECTIVE_RELEASE: '当前生效发布',
  RESOURCE_VERSION: '资源版本',
  KNOWLEDGE_BASE_DRAFT_RELEASE: '知识草稿发布',
};

const targetTypeLabels: Record<string, string> = {
  DOMAIN: '业务域',
  SCENARIO: '业务场景',
  ASSISTANT: '助手',
  PLAYBOOK: 'Playbook',
  AGENT: '智能体',
  RESOURCE: '资源',
  RESOURCE_VERSION: '资源版本',
  KNOWLEDGE_BASE: '知识库',
  ASSISTANT_RELEASE: '助手发布',
  ASSISTANT_RELEASE_AGENT: '发布内智能体',
  KNOWLEDGE_RELEASE: '知识发布',
};

const objectTypeLabels: Record<ReferenceObjectType, string> = {
  DOMAIN: '业务域',
  SCENARIO: '业务场景',
  ASSISTANT: '助手',
  PLAYBOOK: 'Playbook',
  AGENT: '智能体',
  RESOURCE: '资源',
  KNOWLEDGE_BASE: '知识库',
};

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
