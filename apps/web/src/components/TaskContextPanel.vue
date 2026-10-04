<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { collaborationApi } from '../services/collaboration';
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
  Object.assign(form, { kind: entry?.kind ?? 'NOTE', title: entry?.title ?? '', text: String(entry?.body.text ?? ''), uri: String(entry?.body.uri ?? ''), visibleToRoles: [...entry?.visibleToRoles ?? []], entryIds: [...(entry?.source.entryIds as string[] | undefined) ?? []] });
  open.value = true;
}
function save() {
  if (!form.title.trim()) { error.value = '请输入标题'; return; }
  const body = { ...editing.value?.body, text: form.text, ...(form.uri ? { uri: form.uri } : {}) };
  emit('command', 'PUT_CONTEXT', { ...(editing.value ? { entryId: editing.value.id, entryRevision: editing.value.revision } : {}), kind: form.kind, title: form.title, body, visibleToRoles: form.visibleToRoles, source: { ...editing.value?.source, entryIds: form.entryIds } }, () => { open.value = false; });
}
async function versions(entry: TaskContextEntry) {
  try { history.value = await collaborationApi.versions(props.detail.task.id, entry.id); } catch(e) { error.value = String(e); }
}
function setState(entry: TaskContextEntry, state: ContextState) { emit('command', 'SET_CONTEXT_STATE', { entryId: entry.id, entryRevision: entry.revision, state }); }
</script>
<template>
  <a-space class="toolbar" wrap>
    <a-select v-model:value="filter" allow-clear placeholder="所有上下文" style="width: 170px" :options="Object.entries(kinds).map(([value,label]) => ({value,label}))" />
    <a-button v-if="detail.permissions.includes('CONTRIBUTE')" type="primary" @click="edit()">添加贡献</a-button>
    <span>{{ entries.length }} 条已加载；任务上下文在各个会话间持续保留</span>
  </a-space>
  <a-alert v-if="error" type="error" :message="error" closable @close="error=''" />
  <a-empty v-if="!displayed.length" description="添加证据、问题或第一份工作成果，开始协作" />
  <div class="context-grid">
    <a-card v-for="entry in displayed" :key="entry.id" size="small" :title="entry.title">
      <a-space wrap><a-tag>{{ kinds[entry.kind] }}</a-tag><a-tag>{{ states[entry.state] }}</a-tag><small>版本 {{ entry.revision }}</small></a-space>
      <p class="body">{{ entry.body.text ?? JSON.stringify(entry.body, null, 2) }}</p>
      <p v-if="entry.body.uri" class="body">成果位置：{{ entry.body.uri }}</p>
      <small>{{ entry.visibleToRoles.length ? `可见角色：${entry.visibleToRoles.join('、')}` : '任务全体参与者可见' }}</small>
      <p v-if="Object.keys(entry.source).length" class="source">来源：{{ JSON.stringify(entry.source) }}</p>
      <a-space wrap>
        <a-button size="small" @click="versions(entry)">历史版本</a-button>
        <a-button v-if="detail.permissions.includes('CONTRIBUTE')" size="small" @click="edit(entry)">修订</a-button>
        <a-select v-if="detail.permissions.includes('DECIDE')" :value="entry.state" style="width: 115px" :options="Object.entries(states).map(([value,label])=>({value,label}))" @change="(value: string) => setState(entry, value as ContextState)" />
      </a-space>
    </a-card>
  </div>
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
    <a-card v-for="version in history" :key="version.revision" :title="`版本 ${version.revision} · ${version.updatedAt}`" size="small"><pre>{{ JSON.stringify(version.body, null, 2) }}</pre><a-tag>{{ states[version.state] }}</a-tag></a-card>
  </a-modal>
</template>
<style scoped>
.toolbar { margin-bottom: 16px; }.context-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(310px,1fr)); gap: 16px; }.body,pre { white-space: pre-wrap; overflow-wrap: anywhere; }.source { color: #777; font-size: 12px; overflow-wrap: anywhere; }
</style>
