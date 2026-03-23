<script setup lang="ts">
import { computed } from 'vue';
import type { Scenario } from '../types';

const props = defineProps<{
  scenarios: Scenario[];
}>();

const current = computed(() => props.scenarios[0]);
</script>

<template>
  <a-card v-if="current">
    <a-descriptions :column="2" :title="current.name">
      <a-descriptions-item label="场景目标">{{ current.goal }}</a-descriptions-item>
      <a-descriptions-item label="版本">
        <a-tag :color="current.version.status === 'PUBLISHED' ? 'green' : 'gold'">
          {{ current.version.version }}
        </a-tag>
      </a-descriptions-item>
    </a-descriptions>
  </a-card>

  <a-card title="全部场景">
    <a-list :data-source="scenarios">
      <template #renderItem="{ item }">
        <a-list-item>
          <a-list-item-meta :title="item.name" :description="item.goal" />
          <a-tag>{{ item.version.status }}</a-tag>
        </a-list-item>
      </template>
    </a-list>
  </a-card>
</template>
