<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '../services/api';
import { collaborationApi } from '../services/collaboration';
import { renderMarkdown } from '../utils/markdown';
import type { Reception, CollaborationMessage, SendMessage } from '../types';
import type { BusinessTask } from '../../../../packages/contracts/src/task';
const props = defineProps<{ canManageGovernance?: boolean }>();
const route = useRoute(); const reception = ref<Reception>(); const tasks = ref<BusinessTask[]>([]);
const loading = ref(true); const sending = ref(false); const text = ref(''); const error = ref('');
const refreshError = ref('');
const workExpanded = ref(false);
const replyingTo = ref<CollaborationMessage>();
const missingEntry = ref(false); const connection = ref('正在连接'); const transcript = ref<HTMLElement>();
const historyCursor=ref<string|null>(null); const loadingHistory = ref(false); const pendingRequest = ref<SendMessage>();
let stream: EventSource | undefined; let timer: ReturnType<typeof setInterval> | undefined; let refreshing = false; let stopped = false;
const messages = computed(() => [...(reception.value?.messages ?? [])].sort((a,b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id)));
const activation = computed(() => reception.value?.latestActivation);
const activationLabel = computed(() => activation.value?.completion==='BLOCKED'?'遇到需要处理的障碍':activation.value?.completion==='WAITING'?'等待信息或协作结果':({QUEUED:'已收到，等待处理',RUNNING:'正在处理，必要时会自动继续',SUCCEEDED:'已完成本次处理',FAILED:'自动继续后仍未能完成',CANCELLED:'本次处理已停止'}[activation.value?.status ?? 'SUCCEEDED']));
const human = (item: CollaborationMessage) => item.senderId === reception.value?.humanParticipantId;
function participantName(id:string) { return reception.value?.participants.find(item=>item.id===id)?.displayName ?? (id===reception.value?.humanParticipantId?'你':id===reception.value?.session.participantId?reception.value.session.name:'参与者'); }
function replySource(item:CollaborationMessage) { const source=messages.value.find(previous=>previous.id===item.replyToMessageId);return source?`回复 ${participantName(source.senderId)} 的消息`:'承接相关工作更新'; }
function replyTo(item:CollaborationMessage) { replyingTo.value=item;pendingRequest.value=undefined; }
function messageText(item: CollaborationMessage) {
  if (typeof item.body.text === 'string') return item.body.text;
  const blocks = item.body.blocks;
  return Array.isArray(blocks) ? blocks.map(block => typeof block.text === 'string' ? block.text : typeof block.markdown === 'string' ? block.markdown : typeof block.content === 'string' ? block.content : block.type === 'IMAGE' ? `[图片：${block.alt || '查看原图'}](${block.url})` : block.type === 'CARD' ? (typeof block.data?.title === 'string' ? block.data.title : '收到一张信息卡片') : '').join('\n\n') : '';
}
async function apply(value: Reception) {
  if (stopped) return;
  const closeToBottom = !transcript.value || transcript.value.scrollHeight - transcript.value.scrollTop - transcript.value.clientHeight < 100;
  const merged = new Map((reception.value?.messages ?? []).map(item => [item.id, item]));
  const people = new Map((reception.value?.participants ?? []).map(item => [item.id, item]));
  value.participants.forEach(item => people.set(item.id,item));
  value.messages.forEach(item => merged.set(item.id, item)); reception.value = { ...value, messages: [...merged.values()], participants:[...people.values()] };
  if (closeToBottom) { await nextTick(); transcript.value?.scrollTo({ top: transcript.value.scrollHeight, behavior: 'smooth' }); }
}
async function refresh() {
  if (!reception.value || refreshing || stopped) return; refreshing = true;
  try { await apply(await api.receptionDetail(reception.value.session.id)); tasks.value = await collaborationApi.sessionTasks(reception.value.session.id); refreshError.value=''; }
  catch (e) { refreshError.value = e instanceof Error ? e.message : String(e); }
  finally { refreshing = false; }
}
async function connect() {
  loading.value = true; error.value = ''; missingEntry.value = false;
  try {
    const entries = await api.receptionEntries();
    if (!entries.length) { missingEntry.value = true; return; }
    const entryId = typeof route.query.entryId === 'string' ? route.query.entryId : undefined;
    const initial=await api.reception(entryId); historyCursor.value=initial.previousCursor; await apply(initial);
    if (stopped || !reception.value) return;
    stream?.close(); stream = api.openReceptionStream(reception.value.session.id, value => { connection.value = '实时连接'; void apply(value); }, () => { connection.value = '自动同步中'; });
    if (!timer) timer = setInterval(() => { void refresh(); }, 3000);
    await refresh();
  } catch (e) { error.value = e instanceof Error ? e.message : String(e); }
  finally { loading.value = false; await nextTick(); transcript.value?.scrollTo({top:transcript.value.scrollHeight}); }
}
async function send() {
  if (!reception.value || sending.value || !text.value.trim()) return;
  sending.value = true; error.value = '';
  const content = text.value.trim();
  const recipientId=replyingTo.value?.senderId ?? reception.value.session.participantId;
  if (!pendingRequest.value || pendingRequest.value.body.text !== content || pendingRequest.value.recipientId !== recipientId || pendingRequest.value.replyToMessageId !== replyingTo.value?.id) pendingRequest.value = { requestId: crypto.randomUUID(), recipientId, taskId:replyingTo.value?.taskId, replyToMessageId:replyingTo.value?.id, kind: 'MESSAGE', body: { text: content } };
  try {
    const message = await api.sendMessage(pendingRequest.value);
    await apply({ ...reception.value, messages: [message] }); text.value = ''; pendingRequest.value = undefined; replyingTo.value=undefined;
    await nextTick(); transcript.value?.scrollTo({ top: transcript.value.scrollHeight, behavior: 'smooth' });
    await refresh();
  } catch (e) { error.value = e instanceof Error ? e.message : String(e); }
  finally { sending.value = false; }
}
async function control(status: 'ACTIVE'|'PAUSED') {
  if (!reception.value) return;
  try { await api.controlAgent(reception.value.session.id,status); await refresh(); }
  catch(e) { error.value=e instanceof Error?e.message:String(e); }
}
async function older() {
  if (!reception.value || !historyCursor.value) return; loadingHistory.value = true;
  try { const value = await api.receptionDetail(reception.value.session.id, {before:historyCursor.value}); historyCursor.value=value.previousCursor; await apply(value); }
  catch (e) { error.value = e instanceof Error ? e.message : String(e); } finally { loadingHistory.value = false; }
}
onMounted(connect);
onBeforeUnmount(() => { stopped = true; stream?.close(); if (timer) clearInterval(timer); });
</script>
<template>
  <a-spin v-if="loading" size="large" />
  <a-result v-else-if="missingEntry" title="接待服务尚未配置" sub-title="管理员配置好模型、角色和接待入口后，你就可以直接在这里交流。">
    <template #extra><router-link v-if="props.canManageGovernance" to="/console/role-templates"><a-button type="primary">配置角色与入口</a-button></router-link><a-button @click="connect">重新检查</a-button></template>
  </a-result>
  <div v-else class="reception-grid">
    <section class="conversation">
      <div class="conversation-heading"><div><strong>{{ reception?.session.name || '接待 Agent' }}</strong><span class="heading-subtitle">持续理解、组织和跟进你的工作</span></div><a-space><a-tag>{{ connection }}</a-tag><a-button v-if="reception?.session.status==='ACTIVE'" size="small" @click="control('PAUSED')">暂停接待</a-button></a-space></div>
      <a-alert v-if="error || refreshError" type="error" :message="error || refreshError" show-icon closable @close="error='';refreshError=''"><template #action><a-button @click="reception?refresh():connect()">重试</a-button></template></a-alert>
      <a-alert v-if="reception?.session.status === 'PAUSED'" type="info" message="当前 Agent 已暂停" description="恢复后会继续处理已有工作。"><template #action><a-button @click="control('ACTIVE')">继续</a-button></template></a-alert>
      <div ref="transcript" class="messages" aria-live="polite" aria-label="交流记录">
        <a-button v-if="historyCursor" type="link" :loading="loadingHistory" @click="older">查看更早的交流</a-button>
        <div v-if="!messages.length" class="welcome"><h2>有什么需要一起完成的？</h2><p>可以交代一个目标、提供资料，或询问已有工作的进展。</p></div>
        <article v-for="item in messages" :key="item.id" class="message" :class="{human:human(item)}">
          <div class="message-byline"><strong>{{ participantName(item.senderId) }}<span v-if="human(item) && item.recipientId!==reception?.session.participantId"> → {{participantName(item.recipientId)}}</span></strong><time>{{ new Date(item.createdAt).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}) }}</time></div>
          <div v-if="item.replyToMessageId" class="reply-context">{{replySource(item)}}</div><div class="markdown-body formatted-content" v-html="renderMarkdown(messageText(item))" />
          <a-button v-if="!human(item)" size="small" type="link" @click="replyTo(item)">回复</a-button>
          <router-link v-if="item.taskId" :to="{path:'/console/tasks',query:{taskId:item.taskId}}" class="work-link">查看相关工作 →</router-link>
        </article>
      </div>
      <div v-if="activation && (activation.status !== 'SUCCEEDED' || activation.completion==='BLOCKED')" class="activation-state"><a-alert :type="activation.status==='FAILED'?'error':'info'" show-icon :message="activationLabel" :description="activation.status==='FAILED' ? (activation.failureReason || '可以继续发送消息说明希望如何推进，已有工作记录会保留。') : activation.status==='CANCELLED' ? '可以继续发送消息，或与参与者确认后续安排。' : undefined" /></div>
      <form class="composer" @submit.prevent="send"><div v-if="replyingTo" class="replying"><span>回复 {{participantName(replyingTo.senderId)}}<small v-if="replyingTo.taskId"> · 关联工作将保留</small></span><a-button size="small" type="text" @click="replyingTo=undefined;pendingRequest=undefined">取消回复</a-button></div><a-textarea v-model:value="text" :rows="3" :placeholder="replyingTo?`回复 ${participantName(replyingTo.senderId)}…`:'说说你的目标、补充信息，或继续之前的工作…'" aria-label="发送给 Agent 的消息" @keydown="(event: KeyboardEvent) => { if(event.key==='Enter' && (event.ctrlKey || event.metaKey)){ event.preventDefault(); void send(); } }" /><div><span>Ctrl / ⌘ + Enter 发送</span><a-button html-type="submit" type="primary" :loading="sending" :disabled="!text.trim() || !reception">发送</a-button></div></form>
    </section>
    <aside class="work-sidebar"><button class="work-toggle" type="button" :aria-expanded="workExpanded" aria-controls="reception-work-list" @click="workExpanded=!workExpanded">正在推进的工作 · {{tasks.length}} <span>{{workExpanded?'收起':'展开'}}</span></button><div id="reception-work-list" class="work-list" :class="{expanded:workExpanded}"><h3>正在推进的工作</h3><p>这里会出现 Agent 已建立并与你共享的目标和进展。</p><router-link v-for="task in tasks" :key="task.id" :to="{path:'/console/tasks',query:{taskId:task.id}}" class="work-card"><strong>{{ task.title }}</strong><span>{{ task.stage || ({OPEN:'已建立',ACTIVE:'进行中',WAITING:'等待信息',PAUSED:'已暂停',SUBMITTED:'待你确认',COMPLETED:'已完成',CANCELLED:'已取消'}[task.status]) }}</span><small>{{ task.goal }}</small></router-link><a-empty v-if="!tasks.length" :image="undefined" description="可以直接从交流开始" /></div></aside>
  </div>
</template>
<style scoped>
.reception-grid{display:grid;grid-template-columns:minmax(0,1fr) 264px;gap:20px;height:100%;min-height:0}
.conversation{display:flex;flex-direction:column;min-width:0;min-height:0;background:var(--bg-raised-strong);border:1px solid var(--line);border-radius:14px;overflow:hidden}
.conversation-heading{padding:16px 20px;border-bottom:1px solid var(--line-faint);display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;flex-shrink:0}
.heading-subtitle{display:block;font-size:12px;color:var(--ink-faint);margin-top:4px}
.messages{flex:1;min-height:160px;overflow:auto;overscroll-behavior:contain;padding:20px;display:flex;flex-direction:column;gap:20px}
.welcome{margin:auto;text-align:center;color:var(--ink-soft)}.welcome h2{font-size:24px;color:var(--ink)}
.message{width:fit-content;max-width:100%;align-self:flex-start;background:#f7f6f3;padding:16px 20px;border-radius:12px;min-width:0;flex-shrink:0;overflow-wrap:anywhere}
.message.human{max-width:88%;align-self:flex-end;background:#f7ecdf}
.message-byline{display:flex;gap:16px;justify-content:space-between;font-size:12px;margin-bottom:10px;color:var(--ink-faint)}.message-byline strong{color:var(--ink-soft);min-width:0}.message-byline time{white-space:nowrap}
.work-link{font-size:12px;margin-left:8px}.reply-context{font-size:12px;color:var(--ink-faint);border-left:2px solid var(--line-strong);padding-left:10px;margin-bottom:10px}.replying{padding-bottom:8px}
.activation-state{padding:0 20px 12px;flex-shrink:0}.composer{padding:12px 20px;border-top:1px solid var(--line-faint);flex-shrink:0;background:var(--bg-raised-strong)}.composer>div{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:8px}.composer span{font-size:12px;color:var(--ink-faint)}
.work-sidebar{min-width:0;overflow:auto}.work-list>p{color:var(--ink-faint);font-size:13px}.work-toggle{display:none}.work-card{display:flex;flex-direction:column;gap:8px;padding:14px;border:1px solid var(--line);border-radius:10px;background:var(--bg-raised);margin:12px 0;color:var(--ink);overflow-wrap:anywhere}.work-card span{font-size:12px;color:var(--accent-ink)}.work-card small{color:var(--ink-soft);display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
@media(max-width:1200px){.reception-grid{grid-template-columns:minmax(0,1fr);grid-template-rows:minmax(0,1fr) auto;gap:10px}.work-sidebar{max-height:30vh}.work-toggle{display:flex;justify-content:space-between;width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--bg-raised);cursor:pointer}.work-list{display:none}.work-list.expanded{display:block}.work-list h3{display:none}}
@media(max-width:640px){.messages{padding:12px;gap:14px}.message{padding:12px}.message.human{max-width:94%}.conversation-heading,.composer{padding:12px}.heading-subtitle{display:none}.activation-state{padding:0 12px 10px}}
</style>
