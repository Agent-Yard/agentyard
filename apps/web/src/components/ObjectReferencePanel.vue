<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api } from '../services/api';
import type { ObjectReferenceAnalysis, ReferenceObjectType } from '../types';
import {
  impactColor,
  impactLabel,
  modeLabel,
  relationContext,
  relationLabel,
} from '../utils/referencePresentation';

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
