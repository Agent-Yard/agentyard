<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { api } from '../services/api';
import type { PlatformAggregateType } from '../types';
import { createObjectHistoryLoader, createObjectHistoryState } from './objectHistoryLoader';

const props = withDefaults(defineProps<{
  aggregateType: PlatformAggregateType;
  objectId: string | null | undefined;
  reloadKey: string | number;
  title?: string;
}>(), {
  title: '操作历史',
});

const state = reactive(createObjectHistoryState());
const expandedIds = ref<string[]>([]);
const { load } = createObjectHistoryLoader(state, api.listPlatformEvents);

function labelFromEventType(eventType: string) {
  return eventType
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function payloadSummary(payload: Record<string, unknown>) {
  const parts: string[] = [];
  if (typeof payload.name === 'string' && payload.name) {
    parts.push(payload.name);
  }
  if (typeof payload.version === 'string' && payload.version) {
    parts.push(`v${payload.version}`);
  }
  if (typeof payload.status === 'string' && payload.status) {
    parts.push(payload.status);
  }
  if (typeof payload.sessionEventType === 'string' && payload.sessionEventType) {
    parts.push(payload.sessionEventType);
  }
  if (typeof payload.currentStatus === 'string' && payload.currentStatus) {
    parts.push(`-> ${payload.currentStatus}`);
  }
  if (typeof payload.deletedObjectName === 'string' && payload.deletedObjectName) {
    parts.push(`删除 ${payload.deletedObjectName}`);
  }
  if (typeof payload.snapshotId === 'string' && payload.snapshotId) {
    parts.push(`snapshot ${payload.snapshotId}`);
  }
  return parts.join(' · ') || JSON.stringify(payload);
}

function toggleExpanded(eventId: string) {
  expandedIds.value = expandedIds.value.includes(eventId)
    ? expandedIds.value.filter((id) => id !== eventId)
    : [...expandedIds.value, eventId];
}

watch(
  () => [props.aggregateType, props.objectId, props.reloadKey] as const,
  () => {
    expandedIds.value = [];
    void load({ aggregateType: props.aggregateType, objectId: props.objectId });
  },
  { immediate: true },
);
</script>

<template>
  <a-card :title="title">
    <a-alert v-if="state.errorMessage" type="error" show-icon :message="state.errorMessage" style="margin-bottom: 16px" />

    <a-skeleton v-if="state.loading" active :paragraph="{ rows: 3 }" />

    <template v-else>
      <a-empty v-if="!state.events.length" description="暂无操作历史" />

      <a-list v-else :data-source="state.events">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta :title="labelFromEventType(item.eventType)" :description="payloadSummary(item.payload)">
              <template #description>
                <div class="history-meta">
                  {{ item.occurredAt }} · {{ item.actorId || 'system' }}
                </div>
                <div>{{ payloadSummary(item.payload) }}</div>
                <a-button type="link" size="small" @click="toggleExpanded(item.id)">
                  {{ expandedIds.includes(item.id) ? '收起详情' : '查看详情' }}
                </a-button>
                <pre v-if="expandedIds.includes(item.id)" class="history-json">{{ JSON.stringify(item.payload, null, 2) }}</pre>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>

      <a-button
        v-if="state.nextCursor"
        block
        :loading="state.loadingMore"
        @click="load({ aggregateType: props.aggregateType, objectId: props.objectId }, state.nextCursor || undefined)"
      >
        加载更多
      </a-button>
    </template>
  </a-card>
</template>

<style scoped>
.history-meta {
  color: #6b7280;
  font-size: 12px;
  margin-bottom: 4px;
}

.history-json {
  background: #f7f7f8;
  border-radius: 8px;
  margin-top: 8px;
  padding: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
