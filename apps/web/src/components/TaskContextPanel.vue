<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { collaborationApi } from '../services/collaboration';
import ContextContent from './ContextContent.vue';
import type { ContextKind, ContextState, TaskCommandKind, TaskContextEntry, TaskDetail } from '../../../../packages/contracts/src/task';
const props = defineProps<{ detail: TaskDetail; entries: TaskContextEntry[]; busy: boolean }>();
const emit = defineEmits<{ command: [kind: TaskCommandKind, data: Record<string, unknown>, done?: () => void] }>();
const kinds: Record<ContextKind, string> = { FACT: '事实与证据', HYPOTHESIS: '假设', PROPOSAL: '提案', DECISION: '决策', QUESTION: '待解问题', NOTE: '工作记录', SUMMARY: '摘要', ARTIFACT: '成果' };
const states: Record<ContextState, string> = { PROPOSED: '待确认', CONFIRMED: '已确认', RESOLVED: '已解决', REJECTED: '已否决', SUPERSEDED: '已替代' };
const filter = ref<ContextKind>();
const displayed = computed(() => props.entries.filter(e => !filter.value || e.kind === filter.value));
const open = ref(false); const editing = ref<TaskContextEntry>(); const history = ref<TaskContextEntry[]>(); const error = ref('');
const form = reactive({ kind: 'NOTE' as ContextKind, title: '', text: '', uri: '', visibleToRoles: [] as string[], entryIds: [] as string[] });
function edit(entry?: TaskContextEntry) {
  editing.value = entry; error.value = '';
  Object.assign(form, { kind: entry?.kind ?? 'NOTE', title: entry?.title ?? '', text: String(entry?.body.markdown ?? entry?.body.text ?? ''), uri: String(entry?.body.uri ?? ''), visibleToRoles: [...entry?.visibleToRoles ?? []], entryIds: [...(entry?.source.entryIds as string[] | undefined) ?? []] });
  open.value = true;
}
function save() {
  if (!form.title.trim()) { error.value = '请输入标题'; return; }
  const body = { ...editing.value?.body, [editing.value?.body.markdown !== undefined ? 'markdown' : 'text']: form.text, ...(form.uri ? { uri: form.uri } : {}) };
  emit('command', 'PUT_CONTEXT', { ...(editing.value ? { entryId: editing.value.id, entryRevision: editing.value.revision } : {}), kind: form.kind, title: form.title, body, visibleToRoles: form.visibleToRoles, source: { ...editing.value?.source, entryIds: form.entryIds } }, () => { open.value = false; });
}
async function versions(entry: TaskContextEntry) {
  try { history.value = await collaborationApi.versions(props.detail.task.id, entry.id); } catch(e) { error.value = String(e); }
}
function setState(entry: TaskContextEntry, state: ContextState) { emit('command', 'SET_CONTEXT_STATE', { entryId: entry.id, entryRevision: entry.revision, state }); }
</script>
<template>
  <section class="task-context-panel" aria-label="共享资料与成果">
  <div class="context-toolbar">
    <a-select v-model:value="filter" class="context-filter" allow-clear aria-label="筛选上下文类型" placeholder="所有上下文" :options="Object.entries(kinds).map(([value,label]) => ({value,label}))" />
    <a-button v-if="detail.permissions.includes('CONTRIBUTE')" type="primary" @click="edit()">添加贡献</a-button>
    <span class="context-count">{{ displayed.length }} 条{{ filter ? '匹配' : '已加载' }} · 资料在参与此工作的会话间共享</span>
  </div>
  <a-alert v-if="error" type="error" :message="error" closable @close="error=''" />
  <a-empty v-if="!displayed.length" description="添加证据、问题或第一份工作成果，开始协作" />
  <div class="context-grid">
    <a-card v-for="entry in displayed" :key="entry.id" class="context-card" size="small" :title="entry.title">
      <div class="context-meta"><a-tag>{{ kinds[entry.kind] }}</a-tag><a-tag>{{ states[entry.state] }}</a-tag><small>版本 {{ entry.revision }}</small></div>
      <ContextContent :body="entry.body" />
      <small class="context-visibility">{{ entry.visibleToRoles.length ? `可见角色：${entry.visibleToRoles.join('、')}` : '任务全体参与者可见' }}</small>
      <details v-if="Object.keys(entry.source).length" class="source"><summary>查看来源</summary><pre>{{ JSON.stringify(entry.source, null, 2) }}</pre></details>
      <div class="context-actions">
        <a-button size="small" @click="versions(entry)">历史版本</a-button>
        <a-button v-if="detail.permissions.includes('CONTRIBUTE')" size="small" @click="edit(entry)">修订</a-button>
        <a-select v-if="detail.permissions.includes('DECIDE')" :value="entry.state" style="width: 115px" :options="Object.entries(states).map(([value,label])=>({value,label}))" @change="(value: string) => setState(entry, value as ContextState)" />
      </div>
    </a-card>
  </div>
  </section>
  <a-modal v-model:open="open" :title="editing ? '修订上下文' : '添加贡献'" :confirm-loading="busy" width="760px" @ok="save">
    <a-form layout="vertical">
      <a-form-item label="类型"><a-select v-model:value="form.kind" :disabled="!!editing" :options="Object.entries(kinds).map(([value,label])=>({value,label}))" /></a-form-item>
      <a-form-item label="标题"><a-input v-model:value="form.title" /></a-form-item>
      <a-form-item label="内容"><a-textarea v-model:value="form.text" :rows="8" placeholder="记录结论、证据、假设和待处理事项" /></a-form-item>
      <a-form-item v-if="form.kind==='ARTIFACT'" label="成果位置"><a-input v-model:value="form.uri" placeholder="文件地址、文档链接或外部系统引用" /></a-form-item>
      <a-form-item label="可见角色（留空代表任务全体参与者）"><a-select v-model:value="form.visibleToRoles" mode="multiple" :disabled="!!editing" :options="detail.task.policy.roles.map(r=>({value:r.key,label:r.name}))" /></a-form-item>
      <a-form-item label="来源上下文"><a-select v-model:value="form.entryIds" mode="multiple" :options="entries.filter(e=>e.id!==editing?.id).map(e=>({value:e.id,label:e.title}))" /></a-form-item>
      <p>修订会创建新版本，默认重新进入待确认状态。来源受限时，派生内容必须保持相同或更小的可见范围。</p>
    </a-form>
  </a-modal>
  <a-modal :open="!!history" title="上下文历史" :footer="null" width="800px" @cancel="history=undefined">
    <div class="context-grid">
    <a-card v-for="version in history" :key="version.revision" :title="`版本 ${version.revision} · ${version.updatedAt}`" size="small"><ContextContent :body="version.body" /><a-tag>{{ states[version.state] }}</a-tag></a-card>
    </div>
  </a-modal>
</template>
<style scoped>
.task-context-panel, .context-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; min-width: 0; }
.context-toolbar, .context-meta, .context-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; min-width: 0; }
.context-filter { width: 170px; max-width: 100%; }
.context-count { flex: 1 1 240px; font-size: 12px; line-height: 1.6; color: var(--ink-soft); }
.context-card :deep(.ant-card-body) { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; }
.context-card :deep(.context-prose) { margin: 0; }
.context-meta :deep(.ant-tag) { margin: 0; }
.context-meta small, .context-visibility { color: var(--ink-soft); }
.context-actions { padding-top: 14px; border-top: 1px solid var(--line-faint); }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.source { color: var(--ink-soft); font-size: 12px; overflow-wrap: anywhere; }
.source summary { cursor: pointer; }
.source pre { margin: 10px 0 0; }
</style>
