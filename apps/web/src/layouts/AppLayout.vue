<script setup lang="ts">
import { computed, provide, shallowRef } from 'vue';
import { pageHeadActionsKey, type PageHeadActionsRegistration } from '../composables/pageHeadActions';
import type { UserSession } from '../types';
import { menuItems } from '../config/navigation';
import { resolveDeployEnv } from '../config/deployEnv';

const props = defineProps<{
  session: UserSession;
  currentSectionLabel: string;
  currentPageLabel: string;
  currentPageSubtitle: string;
  selectedKeys: string[];
  openKeys: string[];
  governanceWritable: boolean;
}>();

const emit = defineEmits<{
  menuClick: [info: { key: string | number }];
  openChange: [keys: string[]];
  logout: [];
}>();

const filteredSections = computed(() => (
  props.governanceWritable
    ? menuItems
    : menuItems
));

const navigationSections = computed(() =>
  filteredSections.value.map((section, index) => ({
    ...section,
    sequence: String(index + 1).padStart(2, '0'),
  }))
);

const activePageKey = computed(() => props.selectedKeys[0] ?? '');
const activeSectionKey = computed(() =>
  navigationSections.value.find((section) => section.children.some((item) => item.key === activePageKey.value))?.key ?? null,
);

const userInitials = computed(() => {
  const compactName = props.session.displayName.replace(/\s+/g, '');
  return compactName.slice(0, 2).toUpperCase();
});

const environmentLabel = computed(() => resolveDeployEnv(import.meta.env.VITE_DEPLOY_ENV, import.meta.env.MODE));
const pageHeadActions = shallowRef<PageHeadActionsRegistration | null>(null);
const pageHeadActionsComponent = computed(() => (
  pageHeadActions.value
    ? { render: pageHeadActions.value.render }
    : null
));

provide(pageHeadActionsKey, pageHeadActions);
</script>

<template>
  <div class="console-shell">
    <header class="console-topbar">
      <div class="console-topbar__brand">
        <div class="console-brand-mark" aria-hidden="true">
          <span class="console-brand-mark__frame"></span>
          <span class="console-brand-mark__stem"></span>
          <span class="console-brand-mark__dot"></span>
        </div>
        <div class="console-topbar__brand-copy">
          <span class="console-topbar__brand-en">AGENTYARD</span>
        </div>
      </div>

      <div class="console-topbar__crumbs">
        <span class="console-topbar__tag">{{ session.currentRole }}</span>
        <span class="console-topbar__separator">/</span>
        <span>{{ currentSectionLabel }}</span>
        <span class="console-topbar__separator">›</span>
        <span class="console-topbar__current">{{ currentPageLabel }}</span>
      </div>

      <div class="console-topbar__tools">
        <div class="console-envtag">
          <span class="console-envtag__dot"></span>
          {{ environmentLabel }} · {{ governanceWritable ? 'writable' : 'readonly' }}
        </div>
        <button type="button" class="console-command-button">
          <span class="console-command-button__icon" aria-hidden="true"></span>
          <span>搜索 / 指令</span>
          <span class="console-command-button__kbd">/</span>
        </button>
      </div>

      <div class="console-topbar__account">
        <a-dropdown
          placement="bottomRight"
          :trigger="['hover', 'click']"
          overlay-class-name="console-account-dropdown"
        >
          <button type="button" class="console-topbar__account-trigger">
            <span class="console-topbar__avatar">{{ userInitials }}</span>
            <span class="console-topbar__user-copy">
              <strong>{{ session.displayName }}</strong>
              <span>{{ session.currentRole }}</span>
            </span>
            <span class="console-topbar__account-caret">▾</span>
          </button>

          <template #overlay>
            <div class="console-account-dropdown__panel">
              <div class="console-account-dropdown__meta">
                <strong>{{ session.displayName }}</strong>
                <span>{{ session.currentRole }}</span>
              </div>
              <button type="button" class="console-account-dropdown__item" @click="emit('logout')">退出登录</button>
            </div>
          </template>
        </a-dropdown>
      </div>
    </header>

    <aside class="console-sidebar">
      <div class="console-sidebar__search">
        <button type="button" class="console-sidebar__search-button">
          搜索对象、ID 或操作…
        </button>
      </div>

      <nav v-for="section in navigationSections" :key="section.key" class="console-sidebar__section">
        <div class="console-sidebar__section-head">
          <span class="console-sidebar__section-num">{{ section.sequence }}</span>
          <span>{{ section.label }}</span>
          <span class="console-sidebar__section-line"></span>
        </div>

        <button
          v-for="item in section.children"
          :key="item.key"
          type="button"
          class="console-sidebar__item"
          :class="{ 'console-sidebar__item--active': activePageKey === item.key }"
          @click="emit('menuClick', { key: item.key })"
        >
          <span>{{ item.label }}</span>
          <span v-if="activePageKey === item.key" class="console-sidebar__item-chip">当前</span>
        </button>
      </nav>

      <div class="console-sidebar__foot">
        <div>agentyard console</div>
        <div>{{ activeSectionKey || 'console' }} · {{ environmentLabel }}</div>
        <div>{{ session.userId }}</div>
      </div>
    </aside>

    <main class="console-main">
      <section class="console-page-head">
        <div class="console-page-head__kicker">
          <span class="console-page-head__index">{{ activeSectionKey || 'root' }}</span>
          <span class="console-page-head__section">{{ currentSectionLabel }}</span>
          <span class="console-page-head__line"></span>
        </div>

        <div class="console-page-head__row">
          <div class="console-page-head__copy">
            <h1>{{ currentPageLabel }}</h1>
            <p>{{ currentPageSubtitle }}</p>
          </div>

          <div class="console-page-head__actions">
            <div v-if="pageHeadActionsComponent" class="console-page-head__action-slot">
              <component :is="pageHeadActionsComponent" />
            </div>
          </div>
        </div>
      </section>

      <section class="console-page-body">
        <slot />
      </section>
    </main>
  </div>
</template>
