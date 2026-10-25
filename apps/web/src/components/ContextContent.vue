<script setup lang="ts">
import { computed } from 'vue';
import { renderMarkdown } from '../utils/markdown';
const props = defineProps<{ body: Record<string, unknown> }>();
const prose = computed(() => typeof props.body.markdown === 'string' ? props.body.markdown : typeof props.body.text === 'string' ? props.body.text : '');
const fields = computed(() => Object.fromEntries(Object.entries(props.body).filter(([key]) => !['text', 'markdown', 'uri'].includes(key))));
</script>
<template>
  <div v-if="prose" class="context-prose formatted-content" v-html="renderMarkdown(prose)" />
  <p v-if="body.uri" class="context-uri">成果位置：{{ body.uri }}</p>
  <details v-if="Object.keys(fields).length" :open="!prose"><summary>结构化资料</summary><pre>{{ JSON.stringify(fields, null, 2) }}</pre></details>
</template>
<style scoped>
.context-prose{margin:16px 0;min-width:0}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--bg-sunk);padding:12px}.context-uri{overflow-wrap:anywhere}summary{cursor:pointer;color:var(--ink-faint);font-size:12px}
</style>
