<script setup lang="ts">
import type { DeletionImpactPreview, ObjectReferenceRelation } from '../types';
import {
  cascadeActionColor,
  cascadeActionLabel,
  cascadeContext,
  impactColor,
  impactLabel,
  modeLabel,
  objectTypeLabel,
  relationContext,
  relationLabel,
} from '../utils/referencePresentation';

defineProps<{
  open: boolean;
  preview: DeletionImpactPreview | null;
  confirming: boolean;
}>();

const emit = defineEmits<{
  close: [];
  confirm: [];
}>();

function summaryMessage(preview: DeletionImpactPreview) {
  return preview.canDelete
    ? `${objectTypeLabel(preview.objectType)}删除后将立即生效，请确认受影响对象和级联回收项。`
    : `${objectTypeLabel(preview.objectType)}当前存在阻断项，处理完阻断关系后才能删除。`;
}

function summaryType(preview: DeletionImpactPreview) {
  return preview.canDelete ? 'warning' : 'error';
}

function sectionTitle(relations: ObjectReferenceRelation[], emptyText: string, populatedTitle: string) {
  return relations.length ? populatedTitle : emptyText;
}
</script>

<template>
  <a-modal
    :open="open"
    :title="preview ? `删除影响预览 · ${preview.objectName}` : '删除影响预览'"
    width="760px"
    :mask-closable="!confirming"
    @cancel="emit('close')"
  >
    <a-space direction="vertical" size="large" style="width: 100%">
      <a-skeleton v-if="!preview" active :paragraph="{ rows: 6 }" />

      <template v-else>
        <a-alert
          :type="summaryType(preview)"
          show-icon
          :message="summaryMessage(preview)"
          :description="`阻断 ${preview.blockers.length} 项 · 影响提示 ${preview.advisories.length} 项 · 级联回收 ${preview.cascadeDeletes.length} 项`"
        />

        <a-card size="small" :title="sectionTitle(preview.blockers, '没有阻断项', '阻断删除的对象')">
          <a-empty v-if="!preview.blockers.length" description="当前没有阻断删除的下游对象" />
          <a-list v-else :data-source="preview.blockers" size="small">
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
        </a-card>

        <a-card size="small" :title="sectionTitle(preview.advisories, '没有额外影响提示', '受影响的快照 / 编排 / 绑定关系')">
          <a-empty v-if="!preview.advisories.length" description="当前没有需要额外关注的影响提示" />
          <a-list v-else :data-source="preview.advisories" size="small">
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
        </a-card>

        <a-card size="small" title="自动级联回收清单">
          <a-empty v-if="!preview.cascadeDeletes.length" description="本次删除不会自动回收其他对象" />
          <a-list v-else :data-source="preview.cascadeDeletes" size="small">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-space direction="vertical" style="width: 100%">
                  <a-space wrap>
                    <a-typography-text strong>{{ item.targetName }}</a-typography-text>
                    <a-tag :color="cascadeActionColor(item)">{{ cascadeActionLabel(item) }}</a-tag>
                    <a-tag color="blue">{{ relationLabel(item.relationKind) }}</a-tag>
                  </a-space>
                  <a-typography-text type="secondary">{{ cascadeContext(item) }}</a-typography-text>
                  <a-typography-text>{{ item.description }}</a-typography-text>
                </a-space>
              </a-list-item>
            </template>
          </a-list>
        </a-card>
      </template>
    </a-space>

    <template #footer>
      <a-space>
        <a-button :disabled="confirming" @click="emit('close')">{{ preview?.canDelete ? '取消' : '关闭' }}</a-button>
        <a-button
          v-if="preview?.canDelete"
          type="primary"
          danger
          :loading="confirming"
          @click="emit('confirm')"
        >
          确认删除
        </a-button>
      </a-space>
    </template>
  </a-modal>
</template>
