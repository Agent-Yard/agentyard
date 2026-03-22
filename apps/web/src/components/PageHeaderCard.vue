<script setup lang="ts">
defineProps<{
  title: string;
  subtitle: string;
}>();
</script>

<template>
  <a-card :bordered="false">
    <div class="page-header-card">
      <a-typography-title :level="3" style="margin: 0">{{ title }}</a-typography-title>
      <a-typography-text type="secondary">{{ subtitle }}</a-typography-text>
      <slot />
    </div>
  </a-card>
</template>

<style scoped>
.page-header-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
