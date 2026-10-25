<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { collaborationApi as api } from '../services/collaboration';
import { api as runtimeApi } from '../services/api';
import TaskContextPanel from '../components/TaskContextPanel.vue';
import CollaborationParticipants from '../components/CollaborationParticipants.vue';
import type { BusinessTask, CollaborationActor, TaskCommandKind, TaskDetail, TaskDecision, TaskContextEntry, TaskEvent, TaskStatus } from '../../../../packages/contracts/src/task';
import type { AgentSessionDetail, AgentExecutionStatus } from '../types';
const route=useRoute(); const router=useRouter();
const agentStates=ref<Record<string,AgentExecutionStatus>>({});
const activeTab=ref('context');
const executionLabels:Record<string,string>={IDLE:'当前无执行',QUEUED:'等待执行',RUNNING:'正在执行',WAITING:'等待外部信息',BLOCKED:'遇到障碍',FAILED:'执行失败',CANCELLED:'已停止'};
async function loadAgentStates(){if(!detail.value)return;const taskId=detail.value.task.id;const values=await Promise.all(detail.value.sessions.map(link=>runtimeApi.agentStatus(link.sessionId,taskId)));if(detail.value?.task.id===taskId)agentStates.value=Object.fromEntries(values.map(value=>[value.sessionId,value]));}
const tasks=ref<BusinessTask[]>([]); const detail=ref<TaskDetail>(); const actors=ref<CollaborationActor[]>([]); const me=ref<CollaborationActor>();
const entries=ref<TaskContextEntry[]>([]); const contextCursor=ref<string|null>(); const events=ref<TaskEvent[]>([]);
const nextCursor=ref<string|null>(); const busy=ref(false); const error=ref(''); const changed=ref(false); const search=ref(''); const status=ref<TaskStatus>();
const decisionItems=ref<TaskDecision[]>([]); const decisionCursor=ref<string|null>();
const comment=ref(''); const instance=ref<AgentSessionDetail>(); const instanceOpen=ref(false); const editing=ref(false); const title=ref(''); const goal=ref('');
const statuses:Record<TaskStatus,string>={OPEN:'已建立',ACTIVE:'进行中',WAITING:'等待信息',PAUSED:'已暂停',SUBMITTED:'待确认',COMPLETED:'已完成',CANCELLED:'已取消'};
const decisions:Record<string,string>={SUBMIT:'提交成果',CONFIRM:'确认完成',REQUEST_CHANGES:'请求修改',CONFIRM_STAGE:'确认阶段交付',TRANSFER_CONFIRMATION:'转交确认责任',PAUSE:'暂停',RESUME:'继续',CANCEL:'取消'};
const visible=computed(()=>tasks.value.filter(t=>!search.value||`${t.title} ${t.goal}`.toLowerCase().includes(search.value.toLowerCase())));
const actorName=(id:string|null)=>actors.value.find(a=>a.id===id)?.displayName??id??'—';
async function run(action:()=>Promise<void>){busy.value=true;error.value='';try{await action();}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
async function loadTasks(more=false){const query=new URLSearchParams({limit:'50'});if(status.value)query.set('status',status.value);if(more&&nextCursor.value)query.set('cursor',nextCursor.value);const page=await api.tasks(query.toString());tasks.value=more?[...tasks.value,...page.items]:page.items;nextCursor.value=page.nextCursor;}
async function loadDetail(id:string){const [value,context,history,decisionPage]=await Promise.all([api.detail(id),api.context(id),api.events(id),api.decisions(id)]);decisionItems.value=decisionPage.items;decisionCursor.value=decisionPage.nextCursor;detail.value=value;tasks.value=tasks.value.map(task=>task.id===id?value.task:task);entries.value=context.items;contextCursor.value=context.nextCursor;events.value=history;changed.value=false;if(activeTab.value==='instances')await loadAgentStates();}
async function select(id:string){await router.replace({query:{taskId:id}});await loadDetail(id);}
function command(kind:TaskCommandKind,data:Record<string,unknown>,done?:()=>void){void run(async()=>{if(!detail.value)return;const id=detail.value.task.id;await api.command(id,{requestId:crypto.randomUUID(),expectedRevision:detail.value.task.revision,kind,data});await Promise.all([loadDetail(id),loadTasks()]);done?.();});}
function decide(kind:TaskCommandKind){command(kind,{contentRevision:detail.value?.task.contentRevision,comment:comment.value},()=>{comment.value='';});}
let timer:ReturnType<typeof setInterval>|undefined;
onMounted(()=>run(async()=>{[me.value,actors.value]=await Promise.all([api.me(),api.actors()]);await loadTasks();if(typeof route.query.taskId==='string')await loadDetail(route.query.taskId);timer=setInterval(async()=>{if(detail.value&&!busy.value){try{const value=await api.detail(detail.value.task.id);if(value.task.revision!==detail.value.task.revision)changed.value=true;if(activeTab.value==='instances')await loadAgentStates();}catch{}}},6000);}));
onUnmounted(()=>{if(timer)clearInterval(timer);});
watch(status,()=>run(()=>loadTasks()));watch(()=>route.query.taskId,id=>{if(typeof id==='string'&&id!==detail.value?.task.id)void run(()=>loadDetail(id));});
</script>
<template>
  <a-alert v-if="error" type="error" :message="error" show-icon closable style="margin-bottom:16px" @close="error=''" />
  <a-alert v-if="changed" type="info" message="这项工作有新进展" style="margin-bottom:16px"><template #action><a-button @click="run(async()=>{if(detail)await loadDetail(detail.task.id);})">查看最新进展</a-button></template></a-alert>
  <div class="task-workspace"><aside aria-label="工作列表"><a-input v-model:value="search" placeholder="搜索工作" allow-clear/><a-select v-model:value="status" allow-clear placeholder="所有状态" :options="Object.entries(statuses).map(([value,label])=>({value,label}))"/><a-button :loading="busy" @click="run(()=>loadTasks())">刷新</a-button><button v-for="task in visible" :key="task.id" class="task-item" :class="{selected:detail?.task.id===task.id}" @click="run(()=>select(task.id))"><strong>{{ task.title }}</strong><span>{{ statuses[task.status] }}</span><small v-if="task.stage" :title="task.stage">{{task.stage}}</small></button><a-button v-if="nextCursor" @click="run(()=>loadTasks(true))">加载更多</a-button></aside>
  <section v-if="detail" class="task-detail" aria-label="工作详情"><a-card :title="detail.task.title"><template #extra><a-tag>{{ statuses[detail.task.status] }}</a-tag></template><p class="goal">{{ detail.task.goal }}</p><p v-if="detail.task.stage">当前阶段：{{ detail.task.stage }}</p><p class="muted">发起人：{{ actorName(detail.task.initiatedBy) }} · 确认责任人：{{ actorName(detail.task.confirmerId) }}</p><ul v-if="detail.task.policy.acceptanceCriteria.length"><li v-for="criterion in detail.task.policy.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ul><a-space wrap><router-link to="/console/reception"><a-button type="primary">回到交流中继续</a-button></router-link><a-button v-if="detail.permissions.includes('MANAGE')" @click="title=detail.task.title;goal=detail.task.goal;editing=true">调整目标</a-button></a-space></a-card>
    <a-card v-if="detail.task.status==='SUBMITTED'&&me?.id===detail.task.confirmerId" title="成果等待你的确认"><p>请查看本次成果，也可以直接回到原交流中告诉 Agent 你的决定。</p><a-textarea v-model:value="comment" placeholder="意见或需要继续修改的地方" :rows="2"/><div class="decision-actions"><a-button type="primary" :loading="busy" @click="decide('CONFIRM')">确认完成</a-button><a-button @click="decide('REQUEST_CHANGES')">请求修改</a-button><a-button @click="decide('CONFIRM_STAGE')">确认阶段交付</a-button></div></a-card>
    <a-tabs v-model:activeKey="activeTab" @change="(key:string)=>{if(key==='instances')void loadAgentStates().catch(e=>{error=String(e);});}"><a-tab-pane key="context" tab="共享资料与成果"><TaskContextPanel :detail="detail" :entries="entries" :busy="busy" @command="command"/><a-button v-if="contextCursor" @click="run(async()=>{if(detail&&contextCursor){const page=await api.context(detail.task.id,contextCursor);entries.push(...page.items);contextCursor=page.nextCursor;}})">加载更多资料</a-button></a-tab-pane>
    <a-tab-pane key="participants" tab="参与者"><CollaborationParticipants :actors="actors" :roles="detail.task.policy.roles" :participants="detail.participants" :editable="detail.permissions.includes('MANAGE')" :busy="busy" @save="(actorId,roles)=>command(roles.length?'SET_PARTICIPANT':'REMOVE_PARTICIPANT',{actorId,roles})"/></a-tab-pane>
    <a-tab-pane key="relations" tab="相关工作"><a-list :data-source="detail.relations"><template #renderItem="{item}"><a-list-item><a-tag>{{ {CHILD:'子工作',DEPENDS_ON:'依赖',RELATED:'相关'}[item.kind as 'CHILD'|'DEPENDS_ON'|'RELATED'] }}</a-tag><a @click="run(()=>select(item.relatedTaskId))">{{ tasks.find(t=>t.id===item.relatedTaskId)?.title??item.relatedTaskId }}</a></a-list-item></template></a-list><p class="muted">关系表达工作结构。是否已经完成，由承担责任的参与者判断。</p></a-tab-pane>
    <a-tab-pane key="decisions" tab="交付与决定"><a-list :data-source="decisionItems"><template #renderItem="{item}"><a-list-item><div><strong>{{ decisions[item.kind] || item.kind }}</strong> · {{ actorName(item.decidedBy) }}<p>{{ item.comment }}</p><small>成果版本 {{ item.contentRevision }} · {{ new Date(item.createdAt).toLocaleString() }}<span v-if="item.decidedBy!==item.executedBy"> · 由 {{ actorName(item.executedBy) }} 代办</span></small></div></a-list-item></template></a-list><a-button v-if="decisionCursor" @click="run(async()=>{if(detail&&decisionCursor){const page=await api.decisions(detail.task.id,decisionCursor);decisionItems.push(...page.items);decisionCursor=page.nextCursor;}})">查看更早的决定</a-button></a-tab-pane>
    <a-tab-pane key="instances" tab="参与的 Agent"><a-list :data-source="detail.sessions"><template #renderItem="{item}"><a-list-item><div><strong>{{ actorName(item.actorId) }}</strong><p v-if="agentStates[item.sessionId]" class="muted">{{agentStates[item.sessionId].lifecycle==='PAUSED'?'实例已暂停':agentStates[item.sessionId].lifecycle==='CLOSED'?'实例已结束':'实例持续保留'}} · {{executionLabels[agentStates[item.sessionId].execution]}}</p></div><a-button v-if="agentStates[item.sessionId]?.historyReadable" @click="run(async()=>{instance=await runtimeApi.agentSession(item.sessionId);instanceOpen=true;})">查看运行记录</a-button><span v-else class="muted">私有上下文</span></a-list-item></template></a-list><p class="muted">每个 Agent 保留自己的上下文；参与同一工作不会自动公开私有记录。</p></a-tab-pane>
    <a-tab-pane key="events" tab="活动记录"><a-list :data-source="events"><template #renderItem="{item}"><a-list-item>{{ actorName(item.actorId) }} · {{ item.kind }} · {{ new Date(item.createdAt).toLocaleString() }}</a-list-item></template></a-list></a-tab-pane></a-tabs>
  </section><a-empty v-else description="工作会随着交流建立。选择已有工作查看进展与成果。"><router-link to="/console/reception"><a-button type="primary">与 Agent 交流</a-button></router-link></a-empty></div>
  <a-modal v-model:open="editing" title="调整工作目标" :confirm-loading="busy" @ok="()=>{command('UPDATE',{title,goal,stage:detail?.task.stage,attributes:detail?.task.attributes,policy:detail?.task.policy});editing=false;}"><a-form layout="vertical"><a-form-item label="标题"><a-input v-model:value="title"/></a-form-item><a-form-item label="目标"><a-textarea v-model:value="goal" :rows="5"/></a-form-item></a-form></a-modal>
  <a-drawer v-model:open="instanceOpen" :title="instance?.session.name" :width="'min(640px, 100vw)'"><template v-if="instance"><a-tag>{{ instance.session.status }}</a-tag><h3>执行记录</h3><a-list :data-source="instance.activations"><template #renderItem="{item}"><a-list-item>{{ item.status }} · {{ new Date(item.updatedAt).toLocaleString() }}<a-alert v-if="item.failureReason" :message="item.failureReason" type="error"/></a-list-item></template></a-list><h3>流程工具</h3><a-list :data-source="instance.toolRuns"><template #renderItem="{item}"><a-list-item><div>{{ item.status }}<p v-if="item.waiting.prompt">{{ item.waiting.prompt }}</p><p v-if="item.failureReason">{{ item.failureReason }}</p></div></a-list-item></template></a-list><h3>跟进安排</h3><a-list :data-source="instance.schedules"><template #renderItem="{item}"><a-list-item>{{ item.instruction }} · {{ new Date(item.dueAt).toLocaleString() }} · {{ item.active?'有效':'已停止' }}</a-list-item></template></a-list><h3>事件订阅</h3><a-list :data-source="instance.subscriptions"><template #renderItem="{item}"><a-list-item>{{item.scopeKind}} · {{item.scopeId}} · {{item.eventKinds.length?item.eventKinds.join(', '):'全部变化'}} · {{item.active?'有效':'已停止'}}</a-list-item></template></a-list></template></a-drawer>
</template>
<style scoped>
.task-workspace { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 20px; flex: 1; min-width: 0; min-height: 0; }
.task-workspace aside { display: flex; flex-direction: column; gap: 10px; min-height: 0; overflow: auto; padding: 2px; }
.task-workspace aside > * { flex-shrink: 0; }
.task-item { display: flex; flex-direction: column; gap: 6px; text-align: left; padding: 14px; border: 1px solid var(--line); background: var(--bg-raised-strong); border-radius: 10px; cursor: pointer; min-width: 0; overflow-wrap: anywhere; }
.task-item.selected { border-color: var(--accent); background: #fcf3e8; }
.task-item span, .muted { color: var(--ink-faint); font-size: 12px; }
.task-item small { color: var(--ink-soft); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.goal { white-space: pre-wrap; overflow-wrap: anywhere; }
.task-detail { display: flex; flex-direction: column; gap: 20px; min-width: 0; min-height: 0; overflow: auto; padding: 2px; }
.task-detail > * { flex-shrink: 0; }
.task-detail :deep(.ant-card-head-title) { white-space: normal; overflow-wrap: anywhere; }
.task-detail :deep(.ant-list-item) { gap: 12px; flex-wrap: wrap; }
.task-detail :deep(.ant-tabs-content-holder) { min-width: 0; }
.decision-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
@media (max-width: 1100px) {
  .task-workspace { grid-template-columns: minmax(0, 1fr); flex: none; }
  .task-workspace aside { max-height: 240px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-auto-rows: max-content; align-items: start; align-content: start; }
  .task-workspace aside > .ant-input-affix-wrapper { grid-column: 1 / -1; }
  .task-detail { overflow: visible; }
}
@media (max-width: 640px) {
  .task-workspace { gap: 16px; }
  .task-workspace aside { max-height: 210px; }
  .task-item { padding: 10px; font-size: 13px; }
}
</style>
