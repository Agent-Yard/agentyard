<script setup lang="ts">
withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    kicker: string;
    width?: number | string;
  }>(),
  {
    width: 480,
  },
);

const emit = defineEmits<{
  close: [];
}>();
</script>

<template>
  <a-drawer
    :open="open"
    :title="title"
    :width="width"
    destroy-on-close
    @close="emit('close')"
  >
    <template v-if="$slots.extra" #extra>
      <slot name="extra" />
    </template>
    <div class="create-drawer">
      <div class="create-drawer__kicker">{{ kicker }}</div>
      <div class="create-drawer__body">
        <slot />
      </div>
    </div>
  </a-drawer>
</template>
