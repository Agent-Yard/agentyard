<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { collaborationApi as api } from '../services/collaboration';
import TaskContextPanel from '../components/TaskContextPanel.vue';
import CollaborationParticipants from '../components/CollaborationParticipants.vue';
import CollaborationRoleEditor from '../components/CollaborationRoleEditor.vue';
import { defaultCollaborationRoles, type BusinessTask, type CollaborationActor, type CollaborationOrganization, type TaskCommandKind, type TaskContextEntry, type TaskDetail, type TaskEvent, type TaskExecution, type TaskPermission, type TaskRelationKind, type TaskStatus } from '../../../../packages/contracts/src/task';
import type { Agent, Assistant, SessionRuntimeSession } from '../types';

const props=defineProps<{ assistants: Assistant[]; agents: Agent[]; sessions: SessionRuntimeSession[] }>();
const route=useRoute();const router=useRouter();
const tasks=ref<BusinessTask[]>([]);const nextCursor=ref<string|null>(null);const actors=ref<CollaborationActor[]>([]);const organizations=ref<CollaborationOrganization[]>([]);
const detail=ref<TaskDetail>();const entries=ref<TaskContextEntry[]>([]);const contextCursor=ref<string|null>(null);const events=ref<TaskEvent[]>([]);
const error=ref('');const busy=ref(false);const changed=ref(false);const filter=ref<TaskStatus>();const organizationFilter=ref<string>();const search=ref('');
const modal=ref<'create'|'edit'|'child'|'execution'|'resume'|null>(null);const waitingExecution=ref<TaskExecution>();
const form=reactive({title:'',goal:'',organizationId:undefined as string|undefined,stage:'',attributes:'{}',criteria:'',requiredApprovals:0,allowSelfReview:false,roles:defaultCollaborationRoles()});
const execution=reactive({actorId:undefined as string|undefined,releaseId:undefined as string|undefined,playbookId:undefined as string|undefined,instruction:'',input:'{}',contextEntryIds:[] as string[]});
const resume=reactive({text:'',routeKey:'',extra:'{}'});
const relation=reactive({relatedTaskId:undefined as string|undefined,kind:'CHILD' as TaskRelationKind});const sessionId=ref<string>();const newAgentId=ref<string>();const reviewComment=ref('');
const statuses:Record<TaskStatus,string>={OPEN:'待开展',ACTIVE:'进行中',WAITING:'等待条件',IN_REVIEW:'待验收',COMPLETED:'已完成',CANCELLED:'已取消'};
const isClosed=computed(()=>!!detail.value && ['COMPLETED','CANCELLED'].includes(detail.value.task.status));
const can=(permission:TaskPermission)=>!isClosed.value && !!detail.value?.permissions.includes(permission);
const visibleTasks=computed(()=>tasks.value.filter(t=>`${t.title} ${t.goal}`.toLowerCase().includes(search.value.toLowerCase())));
const agentActors=computed(()=>actors.value.filter(a=>a.kind==='AGENT' && detail.value?.participants.some(p=>p.actorId===a.id)));
const releases=computed(()=>props.assistants.flatMap(a=>a.releases).filter(r=>r.status==='PUBLISHED' && r.agents.some(a=>a.agentId===actors.value.find(actor=>actor.id===execution.actorId)?.subjectId)));
const playbooks=computed(()=>{const release=releases.value.find(r=>r.id===execution.releaseId);const agent=release?.agents.find(a=>a.agentId===actors.value.find(actor=>actor.id===execution.actorId)?.subjectId);return release?.playbooks.filter(p=>agent?.playbookIds.includes(p.id))??[];});
const actorName=(id:string)=>actors.value.find(a=>a.id===id)?.displayName??id;
const taskName=(id:string)=>tasks.value.find(t=>t.id===id)?.title??id;
const pretty=(value:unknown)=>JSON.stringify(value,null,2);
function parse(value:string):Record<string,unknown>{const parsed=JSON.parse(value);if(!parsed || Array.isArray(parsed) || typeof parsed!=='object') throw new Error('结构化输入必须是 JSON 对象');return parsed;}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await action();}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
async function loadTasks(more=false){const query=new URLSearchParams({limit:'100'});if(filter.value)query.set('status',filter.value);if(organizationFilter.value)query.set('organizationId',organizationFilter.value);if(more&&nextCursor.value)query.set('cursor',nextCursor.value);const page=await api.tasks(query.toString());tasks.value=more?[...tasks.value,...page.items]:page.items;nextCursor.value=page.nextCursor;}
async function loadDetail(id:string){const [loaded,context,history]=await Promise.all([api.detail(id),api.context(id),api.events(id)]);detail.value=loaded;entries.value=context.items;contextCursor.value=context.nextCursor;events.value=history;changed.value=false;}
async function select(id:string){await router.replace({query:{...route.query,taskId:id}});await loadDetail(id);}
async function command(kind:TaskCommandKind,data:Record<string,unknown>,done?:()=>void){await run(async()=>{if(!detail.value)return;const id=detail.value.task.id;const result=await api.command(id,{requestId:crypto.randomUUID(),expectedRevision:detail.value.task.revision,kind,data});done?.();await loadTasks();await loadDetail(id);if(kind==='CREATE_CHILD'&&result.objectId)await select(result.objectId);});}
function edit(mode:'create'|'edit'|'child'){
  const task=mode==='edit'?detail.value?.task:undefined;
  Object.assign(form,{title:task?.title??'',goal:task?.goal??'',organizationId:task?.organizationId??undefined,stage:task?.stage??'',attributes:pretty(task?.attributes??{}),criteria:task?.policy.acceptanceCriteria.join('\n')??'',requiredApprovals:task?.policy.requiredApprovals??0,allowSelfReview:task?.policy.allowSelfReview??false,roles:task?JSON.parse(JSON.stringify(task.policy.roles)):defaultCollaborationRoles()});modal.value=mode;
}
async function saveTask(){
  try{
    const body={title:form.title,goal:form.goal,organizationId:form.organizationId??null,stage:form.stage,attributes:parse(form.attributes),policy:{roles:form.roles,requiredApprovals:form.requiredApprovals,allowSelfReview:form.allowSelfReview,acceptanceCriteria:form.criteria.split('\n').map(s=>s.trim()).filter(Boolean)}};
    if(modal.value==='create')await run(async()=>{const task=await api.create(body);modal.value=null;await loadTasks();await select(task.id);});
    else await command(modal.value==='child'?'CREATE_CHILD':'UPDATE',body,()=>{modal.value=null;});
  }catch(e){error.value=String(e);}
}
function launch(){Object.assign(execution,{actorId:undefined,releaseId:undefined,playbookId:undefined,instruction:'',input:'{}',contextEntryIds:entries.value.map(e=>e.id)});modal.value='execution';}
async function execute(){try{await command('START_EXECUTION',{...execution,input:parse(execution.input),playbookId:execution.playbookId??null},()=>{modal.value=null;});}catch(e){error.value=String(e);}}
function respond(item:TaskExecution){waitingExecution.value=item;Object.assign(resume,{text:'',routeKey:'',extra:'{}'});modal.value='resume';}
async function sendResponse(){try{await command('RESUME_EXECUTION',{executionId:waitingExecution.value?.id,waitingId:waitingExecution.value?.waiting.id,payload:{...parse(resume.extra),text:resume.text,...(resume.routeKey?{routeKey:resume.routeKey}:{})}},()=>{modal.value=null;});}catch(e){error.value=String(e);}}
async function publishResult(item:TaskExecution){
  const ids=(item.input.contextEntryIds??[]) as string[];const sources=((item.input.contextEntries??[]) as TaskContextEntry[]).filter(e=>e.visibleToRoles.length);let audience=sources[0]?.visibleToRoles??[];
  for(const entry of sources.slice(1))audience=audience.filter(role=>entry.visibleToRoles.includes(role));
  if(sources.length&&!audience.length){error.value='执行输入的可见范围没有交集，请分别整理成果';return;}
  await command('PUT_CONTEXT',{kind:'ARTIFACT',title:`执行成果 · ${actorName(item.actorId)}`,body:{text:pretty(item.result)},visibleToRoles:audience,source:{executionId:item.id,entryIds:ids}});
}
let timer:ReturnType<typeof setInterval>|undefined;
onMounted(()=>run(async()=>{await api.me();[actors.value,organizations.value]=await Promise.all([api.actors(),api.organizations()]);await loadTasks();if(typeof route.query.taskId==='string')await loadDetail(route.query.taskId);timer=setInterval(async()=>{if(detail.value&&!busy.value){try{const current=await api.detail(detail.value.task.id);if(current.task.revision!==detail.value.task.revision)changed.value=true;}catch{ /* Explicit refresh reports access changes. */ }}},10000);}));
onUnmounted(()=>{if(timer)clearInterval(timer);});
watch([filter,organizationFilter],()=>run(()=>loadTasks()));
watch(()=>route.query.taskId,(id)=>{if(typeof id==='string'&&id!==detail.value?.task.id&&!busy.value)void run(()=>loadDetail(id));});
watch(()=>execution.actorId,()=>{execution.releaseId=undefined;execution.playbookId=undefined;});
</script>
<template>
  <a-alert v-if="error" :message="error" type="error" show-icon closable style="margin-bottom:16px" @close="error=''" />
  <a-alert v-if="changed" type="info" show-icon message="其他参与者更新了这个任务。刷新后再提交，当前表单会保留。" style="margin-bottom:16px"><template #action><a-button @click="run(async()=>{if(detail)await loadDetail(detail.task.id);})">刷新任务</a-button></template></a-alert>
  <div class="workspace">
    <aside>
      <a-space wrap><a-button type="primary" @click="edit('create')">创建任务</a-button><a-button :loading="busy" @click="run(async()=>{await loadTasks();if(detail)await loadDetail(detail.task.id);})">刷新</a-button></a-space>
      <a-input v-model:value="search" placeholder="搜索已加载的任务" allow-clear />
      <a-select v-model:value="filter" allow-clear placeholder="所有状态" :options="Object.entries(statuses).map(([value,label])=>({value,label}))" />
      <a-select v-model:value="organizationFilter" allow-clear placeholder="所有组织" :options="organizations.map(o=>({value:o.id,label:o.name}))" />
      <button v-for="task in visibleTasks" :key="task.id" class="task-item" :class="{selected:detail?.task.id===task.id}" @click="run(()=>select(task.id))"><strong>{{ task.title }}</strong><span>{{ statuses[task.status] }} · {{ task.stage || '未设阶段' }}</span></button>
      <a-button v-if="nextCursor" @click="run(()=>loadTasks(true))">加载更多</a-button>
    </aside>
    <main v-if="detail">
      <a-card><template #title><a-space wrap><span>{{ detail.task.title }}</span><a-tag>{{ statuses[detail.task.status] }}</a-tag></a-space></template>
        <template #extra><a-button v-if="can('MANAGE')" @click="edit('edit')">目标与规则</a-button></template>
        <p class="goal">{{ detail.task.goal }}</p><p v-if="detail.task.stage">当前阶段：{{ detail.task.stage }}</p>
        <ul v-if="detail.task.policy.acceptanceCriteria.length"><li v-for="criterion in detail.task.policy.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ul>
        <a-space wrap v-if="detail.permissions.includes('MANAGE')">
          <a-button v-if="isClosed" @click="command('TRANSITION',{status:'ACTIVE'})">重新开展</a-button>
          <template v-else><a-button v-for="status in (['ACTIVE','WAITING','IN_REVIEW','COMPLETED'] as TaskStatus[]).filter(s=>s!==detail?.task.status)" :key="status" @click="command('TRANSITION',{status})">{{ {ACTIVE:'开始 / 继续',WAITING:'标记等待',IN_REVIEW:'提交验收',COMPLETED:'完成任务'}[status as 'ACTIVE'|'WAITING'|'IN_REVIEW'|'COMPLETED'] }}</a-button><a-popconfirm title="取消任务并停止未完成的执行？" @confirm="command('TRANSITION',{status:'CANCELLED'})"><a-button danger>取消任务</a-button></a-popconfirm></template>
        </a-space>
        <p class="muted">任务版本 {{ detail.task.revision }} · 成果版本 {{ detail.task.contentRevision }} · {{ detail.task.policy.requiredApprovals }} 份有效评审通过后可验收</p>
      </a-card>
      <a-tabs>
        <a-tab-pane key="context" tab="共享上下文与成果">
          <TaskContextPanel :detail="{...detail, permissions:isClosed?['VIEW']:detail.permissions}" :entries="entries" :busy="busy" @command="command" />
          <a-button v-if="contextCursor" @click="run(async()=>{if(detail&&contextCursor){const page=await api.context(detail.task.id,contextCursor);entries.push(...page.items);contextCursor=page.nextCursor;}})">加载更多上下文</a-button>
        </a-tab-pane>
        <a-tab-pane key="participants" tab="参与者与角色">
          <CollaborationParticipants :actors="actors" :roles="detail.task.policy.roles" :participants="detail.participants" :editable="can('MANAGE')" :busy="busy" @save="(actorId,roles)=>command(roles.length?'SET_PARTICIPANT':'REMOVE_PARTICIPANT',{actorId,roles})" />
          <a-space v-if="can('MANAGE')" style="margin-top:20px" wrap><a-select v-model:value="newAgentId" placeholder="登记 Agent 主体" style="width:250px" :options="agents.map(a=>({value:a.id,label:a.name}))" /><a-button :disabled="!newAgentId" @click="run(async()=>{if(newAgentId)await api.registerAgent(newAgentId);actors=await api.actors();})">登记到主体目录</a-button></a-space>
        </a-tab-pane>
        <a-tab-pane key="relations" tab="子任务与依赖">
          <a-button v-if="can('CONTRIBUTE')" @click="edit('child')">创建子任务</a-button>
          <a-list :data-source="detail.relations"><template #renderItem="{item}"><a-list-item><a-space><a-tag>{{ {CHILD:'子任务',DEPENDS_ON:'前置依赖',RELATED:'相关任务'}[item.kind as TaskRelationKind] }}</a-tag><a @click="run(()=>select(item.relatedTaskId))">{{ taskName(item.relatedTaskId) }}</a></a-space><a-button v-if="can('MANAGE')" size="small" @click="command('UNLINK_TASK',{relatedTaskId:item.relatedTaskId,kind:item.kind})">解除关系</a-button></a-list-item></template></a-list>
          <a-space v-if="can('MANAGE')" wrap><a-select v-model:value="relation.kind" style="width:140px" :options="[{value:'CHILD',label:'子任务'},{value:'DEPENDS_ON',label:'前置依赖'},{value:'RELATED',label:'相关任务'}]" /><a-select v-model:value="relation.relatedTaskId" style="width:280px" show-search option-filter-prop="label" placeholder="选择已有任务" :options="tasks.filter(t=>t.id!==detail?.task.id).map(t=>({value:t.id,label:t.title}))" /><a-button :disabled="!relation.relatedTaskId" @click="command('LINK_TASK',{...relation})">关联</a-button></a-space>
          <p class="muted">关系表达工作结构，不自动复制参与者或上下文。子任务、依赖未完成时，当前任务不能通过验收。</p>
        </a-tab-pane>
        <a-tab-pane key="execution" tab="Agent 与流程执行">
          <a-button v-if="can('EXECUTE')" type="primary" @click="launch">发起执行</a-button>
          <a-empty v-if="!detail.executions.length" description="为 Agent 授予执行角色，然后选择发布版本和本次目标" />
          <a-card v-for="item in detail.executions" :key="item.id" size="small" style="margin-top:16px" :title="`${actorName(item.actorId)} · ${item.kind} · ${item.status}`">
            <p>{{ item.instruction }}</p><a-alert v-if="item.failureReason" type="error" :message="item.failureReason" />
            <p v-if="item.status==='WAITING'">等待：{{ item.waiting.prompt }}</p>
            <a-space><a-button v-if="item.status==='WAITING'&&item.waiting.id&&can('DECIDE')" @click="respond(item)">提供信息并继续</a-button><a-popconfirm v-if="['QUEUED','RUNNING','WAITING'].includes(item.status)&&can('EXECUTE')" title="取消这次执行？" @confirm="command('CANCEL_EXECUTION',{executionId:item.id})"><a-button danger>取消执行</a-button></a-popconfirm><a-button v-if="item.status==='SUCCEEDED'&&Object.keys(item.result).length>0&&can('CONTRIBUTE')" @click="publishResult(item)">沉淀为任务成果</a-button></a-space>
            <details v-if="Object.keys(item.result).length"><summary>查看执行结果</summary><pre>{{ pretty(item.result) }}</pre></details>
          </a-card>
        </a-tab-pane>
        <a-tab-pane key="review" tab="验收与评审">
          <p>评审对应成果版本 {{ detail.task.contentRevision }}。更新目标、参与者、上下文或执行结果后，需要重新提交验收。</p>
          <a-space v-if="can('REVIEW')&&detail.task.status==='IN_REVIEW'" direction="vertical" style="width:100%"><a-textarea v-model:value="reviewComment" placeholder="评审意见" /><a-space><a-button type="primary" @click="command('REVIEW',{contentRevision:detail.task.contentRevision,approved:true,comment:reviewComment})">通过</a-button><a-button danger @click="command('REVIEW',{contentRevision:detail.task.contentRevision,approved:false,comment:reviewComment})">请求修改</a-button></a-space></a-space>
          <a-list :data-source="detail.reviews"><template #renderItem="{item}"><a-list-item><span>{{ actorName(item.actorId) }} · 版本 {{ item.contentRevision }} · {{ item.approved?'通过':'请求修改' }}<br>{{ item.comment }}</span></a-list-item></template></a-list>
        </a-tab-pane>
        <a-tab-pane key="sessions" tab="关联会话">
          <a-list :data-source="detail.sessions"><template #renderItem="{item}"><a-list-item><router-link :to="{path:'/console/runtime',query:{sessionId:item.sessionId}}">{{ sessions.find(s=>s.id===item.sessionId)?.title??item.sessionId }}</router-link><a-button @click="command('UNLINK_SESSION',{sessionId:item.sessionId})">解除关联</a-button></a-list-item></template></a-list>
          <a-space><a-select v-model:value="sessionId" style="width:300px" placeholder="选择自己的会话" :options="sessions.map(s=>({value:s.id,label:s.title}))" /><a-button :disabled="!sessionId" @click="command('LINK_SESSION',{sessionId})">关联会话</a-button></a-space>
          <p>关联会话后可以回到同一个任务继续工作。读取任务上下文仍需人和当前 Agent 各自拥有任务权限。</p>
        </a-tab-pane>
        <a-tab-pane key="history" tab="活动记录">
          <a-list :data-source="events"><template #renderItem="{item}"><a-list-item>版本 {{ item.revision }} · {{ actorName(item.actorId) }} · {{ item.kind }} · {{ item.createdAt }}</a-list-item></template></a-list>
          <a-button v-if="events.length" @click="run(async()=>{if(detail){const more=await api.events(detail.task.id,events[events.length-1].revision);events.push(...more);}})">加载后续活动</a-button>
        </a-tab-pane>
      </a-tabs>
    </main>
    <a-empty v-else description="选择任务，或从一个开放目标开始。任务可以用于研究、交付、持续运营和多人协作。" />
  </div>
  <a-modal :open="modal==='create'||modal==='edit'||modal==='child'" :title="modal==='edit'?'任务目标与协作规则':modal==='child'?'创建子任务':'创建任务'" width="960px" :confirm-loading="busy" @cancel="modal=null" @ok="saveTask">
    <a-form layout="vertical"><a-form-item label="标题"><a-input v-model:value="form.title" /></a-form-item><a-form-item label="目标"><a-textarea v-model:value="form.goal" :rows="4" /></a-form-item><a-form-item v-if="modal==='create'" label="所属组织（可选）"><a-select v-model:value="form.organizationId" allow-clear :options="organizations.map(o=>({value:o.id,label:o.name}))" /></a-form-item><a-form-item v-if="modal==='edit'" label="当前阶段"><a-input v-model:value="form.stage" placeholder="自由命名，如调研、验证、交付" /></a-form-item><a-form-item label="验收条件（每行一条）"><a-textarea v-model:value="form.criteria" :rows="3" /></a-form-item><a-space><a-form-item label="需要通过的评审数"><a-input-number v-model:value="form.requiredApprovals" :min="0" :max="100" /></a-form-item><a-checkbox v-model:checked="form.allowSelfReview">允许提交者自行评审</a-checkbox></a-space><a-form-item label="角色与权限"><CollaborationRoleEditor v-model="form.roles" /></a-form-item><details><summary>扩展属性</summary><a-textarea v-model:value="form.attributes" :rows="4" /></details></a-form>
  </a-modal>
  <a-modal :open="modal==='execution'" title="发起独立执行" width="760px" :confirm-loading="busy" @cancel="modal=null" @ok="execute">
    <a-form layout="vertical"><a-form-item label="执行主体"><a-select v-model:value="execution.actorId" :options="agentActors.map(a=>({value:a.id,label:a.displayName}))" /></a-form-item><a-form-item label="冻结的助手发布版本"><a-select v-model:value="execution.releaseId" :options="releases.map(r=>({value:r.id,label:`${assistants.find(a=>a.id===r.assistantId)?.name} @ ${r.releaseVersion}`}))" /></a-form-item><a-form-item label="执行方式"><a-select v-model:value="execution.playbookId" allow-clear placeholder="Agent 自主执行" :options="playbooks.map(p=>({value:p.id,label:p.name}))" /></a-form-item><a-form-item label="这次执行的目标与边界"><a-textarea v-model:value="execution.instruction" :rows="4" /></a-form-item><a-form-item label="提供给执行的上下文"><a-select v-model:value="execution.contextEntryIds" mode="multiple" :options="entries.map(e=>({value:e.id,label:e.title}))" /></a-form-item><a-form-item label="结构化输入（可选；流程输入需满足所选流程的定义）"><a-textarea v-model:value="execution.input" :rows="4" /></a-form-item><p>执行使用选定上下文的版本快照。任务、参与权限和成果验收保持独立；执行不会自动完成任务。</p></a-form>
  </a-modal>
  <a-modal :open="modal==='resume'" title="回应等待中的执行" :confirm-loading="busy" @cancel="modal=null" @ok="sendResponse"><p>{{ waitingExecution?.waiting.prompt }}</p><a-form layout="vertical"><a-form-item label="信息或决定"><a-textarea v-model:value="resume.text" :rows="5" /></a-form-item><a-form-item label="流程分支（可选）"><a-input v-model:value="resume.routeKey" /></a-form-item><details><summary>结构化补充</summary><a-textarea v-model:value="resume.extra" :rows="4" /></details></a-form></a-modal>
</template>
<style scoped>
.workspace{display:grid;grid-template-columns:260px minmax(0,1fr);gap:24px;align-items:start}.workspace aside{display:flex;flex-direction:column;gap:12px;position:sticky;top:20px}.task-item{display:flex;flex-direction:column;gap:8px;text-align:left;padding:16px;border:1px solid #e5e7eb;background:white;border-radius:10px;cursor:pointer}.task-item.selected{border-color:#1677ff;background:#f0f7ff}.task-item span,.muted{color:#777;font-size:12px}.goal,pre{white-space:pre-wrap;overflow-wrap:anywhere}details{margin-top:16px}main{min-width:0}@media(max-width:1000px){.workspace{grid-template-columns:1fr}.workspace aside{position:static;max-height:340px;overflow:auto}}
</style>
