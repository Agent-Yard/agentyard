<script setup lang="ts">
import type { BusinessDomain, Scenario } from '../types';

defineProps<{
  domains: BusinessDomain[];
  scenarios: Scenario[];
}>();
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card><a-statistic title="业务域数" :value="domains.length" /></a-card>
    </a-col>
    <a-col :span="8">
      <a-card><a-statistic title="场景数" :value="scenarios.length" /></a-card>
    </a-col>
    <a-col :span="8">
      <a-card><a-statistic title="资源总数" :value="domains.flatMap((item) => item.resources).length" /></a-card>
    </a-col>
  </a-row>

  <a-card title="域列表">
    <a-list :data-source="domains">
      <template #renderItem="{ item }">
        <a-list-item>
          <a-list-item-meta :title="item.name" :description="item.description" />
          <a-tag color="blue">{{ item.resources.length }} 资源</a-tag>
        </a-list-item>
      </template>
    </a-list>
  </a-card>
</template>
