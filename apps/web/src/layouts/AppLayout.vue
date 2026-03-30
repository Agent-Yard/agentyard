<script setup lang="ts">
import type { UserSession } from '../types';
import { menuItems, type SectionKey } from '../config/navigation';

defineProps<{
  session: UserSession;
  currentSectionLabel: string;
  currentPageLabel: string;
  currentPageSubtitle: string;
  selectedKeys: string[];
  openKeys: SectionKey[];
}>();

const emit = defineEmits<{
  menuClick: [info: { key: string | number }];
  openChange: [keys: string[]];
}>();
</script>

<template>
  <a-layout style="min-height: 100vh">
    <a-layout-sider :width="240" theme="light" style="border-right: 1px solid #f0f0f0">
      <div class="brand-block">
        <a-typography-title :level="3">Lynxus</a-typography-title>
        <a-typography-text type="secondary">Orchestrate Enterprise Agents</a-typography-text>
      </div>
      <a-menu
        mode="inline"
        :selected-keys="selectedKeys"
        :open-keys="openKeys"
        :items="menuItems"
        @click="emit('menuClick', $event as { key: string | number })"
        @openChange="emit('openChange', $event as string[])"
      />
    </a-layout-sider>

    <a-layout>
      <a-layout-header class="app-header">
        <div class="app-header__title-group">
          <a-typography-title :level="4" class="app-header__title">企业级智能体中台</a-typography-title>
          <a-typography-text class="app-header__eyebrow">
            {{ currentSectionLabel }} / {{ currentPageLabel }}
          </a-typography-text>
          <a-typography-text type="secondary" class="app-header__subtitle">
            {{ currentPageSubtitle }}
          </a-typography-text>
        </div>

        <a-typography-text type="secondary">
          {{ session.displayName }} / {{ session.currentRole }}
        </a-typography-text>
      </a-layout-header>

      <a-layout-content class="app-content">
        <slot />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>
