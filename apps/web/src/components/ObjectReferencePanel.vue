<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api } from '../services/api';
import type { ObjectReferenceAnalysis, ObjectReferenceRelation, ReferenceObjectType } from '../types';

const props = withDefaults(defineProps<{
  objectType: ReferenceObjectType;
  objectId?: string | null;
  title?: string;
  reloadKey?: number | string;
}>(), {
  title: '引用分析',
  objectId: null,
  reloadKey: 0,
});

const analysis = ref<ObjectReferenceAnalysis | null>(null);
const loading = ref(false);
const error = ref('');
let requestToken = 0;

const blockerCount = computed(() =>
  analysis.value?.relations.filter((item) => item.impactLevel === 'BLOCKS_DELETION').length ?? 0,
);
const advisoryCount = computed(() =>
  analysis.value?.relations.filter((item) => item.impactLevel === 'ADVISORY').length ?? 0,
);

watch(
  () => [props.objectType, props.objectId, props.reloadKey] as const,
  async ([objectType, objectId]) => {
    requestToken += 1;
    const currentToken = requestToken;
    if (!objectId) {
      analysis.value = null;
      error.value = '';
      return;
    }
    loading.value = true;
    error.value = '';
    try {
      const next = await api.getObjectReferenceAnalysis(objectType, objectId);
      if (currentToken !== requestToken) {
        return;
      }
      analysis.value = next;
    } catch (loadError) {
      if (currentToken !== requestToken) {
        return;
      }
      analysis.value = null;
      error.value = loadError instanceof Error ? loadError.message : '加载引用分析失败';
    } finally {
      if (currentToken === requestToken) {
        loading.value = false;
      }
    }
  },
  { immediate: true },
);

function impactColor(relation: ObjectReferenceRelation) {
  return relation.impactLevel === 'BLOCKS_DELETION' ? 'red' : 'default';
}

function impactLabel(relation: ObjectReferenceRelation) {
  return relation.impactLevel === 'BLOCKS_DELETION' ? '阻断删除' : '影响提示';
}

function modeLabel(relation: ObjectReferenceRelation) {
  return relation.relationMode === 'DIRECT' ? '直接关系' : '间接关系';
}

function relationLabel(relationKind: string) {
  const labels: Record<string, string> = {
    DOMAIN_SCENARIO: '域内场景',
    DOMAIN_RESOURCE: '域内资源',
    DOMAIN_KNOWLEDGE_BASE: '域内知识库',
    SCENARIO_ASSISTANT: '场景助手',
    ASSISTANT_AGENT: '助手智能体',
    ASSISTANT_PRIVATE_RESOURCE: '助手私有资源',
    ASSISTANT_PRIVATE_KNOWLEDGE_BASE: '助手私有知识库',
    ASSISTANT_ORCHESTRATION: '助手编排',
    ASSISTANT_RELEASE: '助手发布快照',
    AGENT_OVERRIDE_MODEL: '模型覆盖',
    AGENT_SKILL_ENABLED: '启用技能',
    AGENT_TOOL_ENABLED: '启用工具',
    AGENT_OVERRIDE_KNOWLEDGE_BASE: '知识库覆盖',
    AGENT_ORCHESTRATION_NODE: '编排节点',
    AGENT_RELEASE_FROZEN: '发布冻结',
    ASSISTANT_DEFAULT_MODEL: '助手默认模型',
    RELEASE_FROZEN: '发布冻结资源',
    ASSISTANT_DEFAULT_KNOWLEDGE_BASE: '助手默认知识库',
    RELEASE_ASSISTANT_KNOWLEDGE: '助手发布冻结知识',
    RELEASE_AGENT_KNOWLEDGE: '智能体发布冻结知识',
    KNOWLEDGE_BASE_EFFECTIVE_RELEASE: '当前生效发布',
  };
  return labels[relationKind] ?? relationKind;
}

function targetTypeLabel(targetType: string) {
  const labels: Record<string, string> = {
    DOMAIN: '业务域',
    SCENARIO: '业务场景',
    ASSISTANT: '助手',
    AGENT: '智能体',
    RESOURCE: '资源',
    KNOWLEDGE_BASE: '知识库',
    ASSISTANT_RELEASE: '助手发布',
    ASSISTANT_RELEASE_AGENT: '发布内智能体',
    KNOWLEDGE_RELEASE: '知识发布',
    ORCHESTRATION: '编排',
    ORCHESTRATION_NODE: '编排节点',
  };
  return labels[targetType] ?? targetType;
}

function relationContext(relation: ObjectReferenceRelation) {
  const context: string[] = [targetTypeLabel(relation.targetType)];
  if (relation.releaseVersion) {
    context.push(`发布版本 ${relation.releaseVersion}`);
  }
  if (relation.resourceVersion) {
    context.push(`资源版本 ${relation.resourceVersion}`);
  }
  if (relation.knowledgeReleaseVersion) {
    context.push(`知识版本 ${relation.knowledgeReleaseVersion}`);
  }
  return context.join(' · ');
}
</script>

<template>
  <a-card size="small" :title="title" :loading="loading">
    <template #extra>
      <a-space v-if="analysis">
        <a-tag color="red">{{ blockerCount }} 阻断</a-tag>
        <a-tag>{{ advisoryCount }} 提示</a-tag>
      </a-space>
    </template>

    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-empty v-else-if="analysis && !analysis.relations.length" description="当前没有下游引用或治理影响" />
    <a-list v-else-if="analysis" :data-source="analysis.relations" size="small">
      <template #renderItem="{ item }">
        <a-list-item>
          <a-space direction="vertical" style="width: 100%">
            <a-space wrap>
              <a-typography-text strong>{{ item.targetName }}</a-typography-text>
              <a-tag :color="impactColor(item)">{{ impactLabel(item) }}</a-tag>
              <a-tag>{{ modeLabel(item) }}</a-tag>
              <a-tag color="blue">{{ relationLabel(item.relationKind) }}</a-tag>
            </a-space>
            <a-typography-text type="secondary">{{ relationContext(item) }}</a-typography-text>
          </a-space>
        </a-list-item>
      </template>
    </a-list>
    <a-skeleton v-else active :paragraph="{ rows: 3 }" />
  </a-card>
</template>
