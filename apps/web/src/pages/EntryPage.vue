<script setup lang="ts">
import { computed, reactive, ref, toRaw } from 'vue';
import { message } from 'ant-design-vue';
import { api } from '../services/api';
import type { CatalogSummary, Entry, EntryPayload } from '../types';
import type { ScopeKind, GrantRequest } from '../../../../packages/contracts/src/agent';
import type { OwnershipOption } from '../composables/ownership';
import OwnershipFields from '../components/OwnershipFields.vue';
const props=defineProps<{catalog?:CatalogSummary;entries:Entry[];currentUserId:string;ownershipOptions:OwnershipOption[];platformAdmin:boolean}>();
const emit=defineEmits<{refreshCatalog:[]}>();
const open=ref(false),saving=ref(false);const target=ref<Entry|null>(null);
const blank=():EntryPayload=>({name:'',description:'',ownerType:'PLATFORM',ownerId:'platform',shareScope:'SHARED',receptionTemplateVersionId:'',enabled:true,allowedTemplateIds:[],capabilityGrants:[]});
const form=reactive<EntryPayload>(blank());
const versions=computed(()=>props.catalog?.roleTemplates.flatMap(t=>t.versions.filter(v=>v.status==='PUBLISHED').map(v=>({label:`${t.name} · ${v.version}`,value:v.id})))??[]);
const templates=computed(()=>props.catalog?.roleTemplates.filter(t=>t.versions.some(v=>v.status==='PUBLISHED')).map(t=>({label:t.name,value:t.id}))??[]);
const scopes:ScopeKind[]=['PLATFORM','ORGANIZATION','TASK','SESSION','TEMPLATE','RESOURCE','PARTICIPANT'];
const operations=['task.create','message.send','agent.create','session.read','session.history','session.control','schedule.create','schedule.manage','subscription.create','subscription.manage','tool.invoke','knowledge.read','skill.read','playbook.run','sandbox.execute','resource.read','grant.manage'];
function edit(entry?:Entry){target.value=entry??null;Object.assign(form,entry?structuredClone(toRaw(entry)):blank());open.value=true;}
function addGrant(){form.capabilityGrants.push({operation:'task.create',scopeKind:'PLATFORM',scopeId:'*',delegable:false,expiresAt:null});}
function basicGrants(){for(const operation of ['task.create','schedule.create','subscription.create']) if(!form.capabilityGrants.some(g=>g.operation===operation))form.capabilityGrants.push({operation,scopeKind:'PLATFORM',scopeId:'*',delegable:true,expiresAt:null});}
function resourceOptions(grant:GrantRequest) {
  if(grant.scopeKind==='TEMPLATE')return templates.value;
  if(grant.scopeKind==='ORGANIZATION')return props.ownershipOptions.filter(item=>item.ownerType==='ORGANIZATION').map(item=>({label:item.label,value:item.ownerId}));
  if(grant.scopeKind!=='RESOURCE')return [];
  return [...(props.catalog?.resources.map(item=>({label:item.name,value:item.id}))??[]),...(props.catalog?.knowledgeBases.map(item=>({label:item.name,value:item.id}))??[]),...(props.catalog?.playbooks.flatMap(item=>item.versions.filter(v=>v.status==='PUBLISHED').map(v=>({label:`${item.name} · ${v.version}`,value:v.id})))??[])];
}
function addReferencedResourceGrants(){
  const previousCount=form.capabilityGrants.length;
  const roles=props.catalog?.roleTemplates.flatMap(item=>item.versions.filter(v=>v.id===form.receptionTemplateVersionId || form.allowedTemplateIds.includes(item.id)&&v.id===item.effectiveVersion?.id))??[];
  const add=(operation:string,scopeId:string)=>{if(!form.capabilityGrants.some(g=>g.operation===operation&&g.scopeKind==='RESOURCE'&&g.scopeId===scopeId))form.capabilityGrants.push({operation,scopeKind:'RESOURCE',scopeId,delegable:true,expiresAt:null});};
  for(const role of roles){
    for(const [operation,ids] of [['tool.invoke',role.configuration.toolVersionIds],['skill.read',role.configuration.skillVersionIds]] as const){for(const id of ids){const resource=props.catalog?.resources.find(item=>item.versions.some(v=>v.id===id));if(resource)add(operation,resource.id);}}
    const knowledge=props.catalog?.knowledgeBases.find(item=>item.releases.some(v=>v.id===role.configuration.knowledgeReleaseId));if(knowledge)add('knowledge.read',knowledge.id);
    for(const id of role.configuration.playbookVersionIds){add('playbook.run',id);const playbook=props.catalog?.playbooks.flatMap(item=>item.versions).find(v=>v.id===id);if(playbook?.definition.nodes.some(n=>n.nodeType==='STEP'))add('sandbox.execute',id);}
  }
  if(form.capabilityGrants.length===previousCount)void message.info('所选角色无需额外资源授权，或所需授权已在列表中。');
}
async function save(){if(!form.name.trim()||!form.receptionTemplateVersionId)return void message.error('请填写入口名称并选择已发布的接待模板版本。');saving.value=true;try{const payload:EntryPayload={name:form.name,description:form.description,ownerType:form.ownerType,ownerId:form.ownerId,shareScope:form.shareScope,receptionTemplateVersionId:form.receptionTemplateVersionId,enabled:form.enabled,allowedTemplateIds:form.allowedTemplateIds,capabilityGrants:form.capabilityGrants};if(target.value)await api.updateEntry(target.value.id,payload);else await api.createEntry(payload);open.value=false;emit('refreshCatalog');void message.success('入口已保存');}catch(e){void message.error(e instanceof Error?e.message:String(e));}finally{saving.value=false;}}
async function remove(entry:Entry){try{await api.deleteEntry(entry.id);emit('refreshCatalog');}catch(e){void message.error(e instanceof Error?e.message:String(e));}}
</script>
<template><a-card title="接待入口"><template #extra><a-button v-if="platformAdmin" type="primary" @click="edit()">配置入口</a-button></template>
<a-alert type="info" show-icon message="入口把用户连接到持续接待的 Agent，并独立授予初始权限。只有平台管理员可以发布和调整入口授权。" style="margin-bottom:20px" />
<a-steps v-if="!entries.length" :current="versions.length?2:catalog?.resources.some(r=>r.type==='LLM_MODEL'&&r.effectiveVersion)?1:0" style="margin:24px 0"><a-step title="配置真实模型" description="在资源库创建并发布模型版本"/><a-step title="发布接待模板" description="选择预制上下文，绑定模型版本"/><a-step title="启用接待入口" description="选择用户范围与协作权限"/></a-steps>
<a-space v-if="!entries.length" style="margin-bottom:24px"><router-link to="/console/resources">配置模型</router-link><router-link to="/console/role-templates">配置角色模板</router-link></a-space>
<a-empty v-if="!entries.length" description="尚未配置接待入口" />
<a-list :data-source="entries"><template #renderItem="{item}"><a-list-item><template #actions><a-button v-if="platformAdmin" @click="edit(item)">编辑与授权</a-button><a-popconfirm v-if="platformAdmin" title="删除入口？已经创建的实例将保留。" @confirm="remove(item)"><a-button danger>删除</a-button></a-popconfirm></template><a-list-item-meta :title="item.name" :description="item.description"/><a-space><a-tag :color="item.enabled?'green':'default'">{{item.enabled?'已启用':'已停用'}}</a-tag><a-tag>{{item.shareScope==='SHARED'?'可共享':'归属内使用'}}</a-tag></a-space></a-list-item></template></a-list>
</a-card><a-modal v-model:open="open" title="入口与初始授权" width="950px" :confirm-loading="saving" @ok="save"><a-form layout="vertical"><a-form-item label="入口名称" required><a-input v-model:value="form.name"/></a-form-item><a-form-item label="介绍"><a-textarea v-model:value="form.description"/></a-form-item><OwnershipFields v-model:owner-type="form.ownerType" v-model:owner-id="form.ownerId" :options="ownershipOptions"/><a-form-item label="可见范围"><a-radio-group v-model:value="form.shareScope"><a-radio value="PRIVATE">仅归属主体</a-radio><a-radio value="SHARED">共享可见</a-radio></a-radio-group></a-form-item><a-form-item label="接待角色版本" required><a-select v-model:value="form.receptionTemplateVersionId" :options="versions"/></a-form-item><a-form-item label="可实例化的角色模板"><a-select v-model:value="form.allowedTemplateIds" mode="multiple" :options="templates"/><small>仅授予列表中模板的实例化能力；各模板所引用资源仍需单独授权。</small></a-form-item><a-form-item label="启用入口"><a-switch v-model:checked="form.enabled"/></a-form-item>
<a-divider orientation="left">接待实例的初始能力</a-divider><a-alert type="warning" show-icon message="授权独立于角色提示词，撤回入口授权会影响已有实例。平台范围 * 覆盖全部同类对象；请依据实际工作范围配置。" style="margin-bottom:16px"/>
<div v-for="(grant,index) in form.capabilityGrants" :key="index" class="grant"><a-auto-complete v-model:value="grant.operation" :options="operations.map(value=>({value}))" placeholder="操作，例如 task.create"/><a-select v-model:value="grant.scopeKind" :options="scopes.map(value=>({value,label:value}))"/><a-auto-complete v-model:value="grant.scopeId" :options="resourceOptions(grant)" placeholder="对象或 ID / *"/><a-checkbox v-model:checked="grant.delegable">可转授</a-checkbox><a-button danger @click="form.capabilityGrants.splice(index,1)">移除</a-button></div><a-space><a-button @click="addGrant">添加授权</a-button><a-button @click="basicGrants">添加任务与调度基础授权</a-button><a-button @click="addReferencedResourceGrants">添加所选角色的资源授权</a-button></a-space>
</a-form></a-modal></template><style scoped>.grant{display:grid;grid-template-columns:1.4fr 1fr 1.3fr auto auto;gap:8px;align-items:center;margin:10px 0}@media(max-width:800px){.grant{grid-template-columns:1fr 1fr}}</style>
