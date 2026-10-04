<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { collaborationApi as api } from '../services/collaboration';
import CollaborationRoleEditor from '../components/CollaborationRoleEditor.vue';
import CollaborationParticipants from '../components/CollaborationParticipants.vue';
import { defaultCollaborationRoles, type CollaborationActor, type CollaborationOrganization, type OrganizationDetail } from '../../../../packages/contracts/src/task';
import type { Agent } from '../types';
defineProps<{ agents: Agent[] }>();
const organizations=ref<CollaborationOrganization[]>([]);const actors=ref<CollaborationActor[]>([]);const current=ref<OrganizationDetail>();
const busy=ref(false);const error=ref('');const open=ref(false);const editing=ref(false);const agentId=ref<string>();
const form=reactive({name:'',description:'',parentId:undefined as string|undefined,roles:defaultCollaborationRoles()});
async function run(action:()=>Promise<void>) { busy.value=true;error.value='';try{await action();}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;} }
async function refresh() { await api.me();[organizations.value,actors.value]=await Promise.all([api.organizations(),api.actors()]); }
function edit(update=false) {editing.value=update;Object.assign(form,update&&current.value?{...current.value.organization,roles:JSON.parse(JSON.stringify(current.value.organization.roles))}:{name:'',description:'',parentId:undefined,roles:defaultCollaborationRoles()});open.value=true;}
function save() { void run(async()=>{current.value=editing.value&&current.value?await api.updateOrganization(current.value.organization.id,{...form,expectedRevision:current.value.organization.revision}):await api.createOrganization(form);open.value=false;await refresh();}); }
function member(actorId:string,roles:string[]) {void run(async()=>{if(current.value) current.value=await api.member(current.value.organization.id,{expectedRevision:current.value.organization.revision,actorId,roles});});}
onMounted(()=>run(refresh));
</script>
<template>
  <a-space wrap style="margin-bottom:20px"><a-button type="primary" @click="edit()">创建组织</a-button><a-select placeholder="选择组织" style="min-width:260px" :options="organizations.map(o=>({value:o.id,label:o.name}))" @change="(id: string)=>run(async()=>{current=await api.organization(id);})" /><a-button @click="run(refresh)">刷新</a-button></a-space>
  <a-alert v-if="error" :message="error" type="error" show-icon style="margin-bottom:16px" />
  <a-card v-if="current" :title="current.organization.name">
    <template #extra><a-button v-if="current.permissions.includes('MANAGE')" @click="edit(true)">组织与角色设置</a-button></template>
    <p>{{ current.organization.description }}</p><p v-if="current.organization.parentId">上级组织：{{ organizations.find(o=>o.id===current?.organization.parentId)?.name ?? current.organization.parentId }}</p>
    <CollaborationParticipants :actors="actors" :roles="current.organization.roles" :participants="current.members" :editable="current.permissions.includes('MANAGE')" :busy="busy" @save="member" />
  </a-card>
  <a-empty v-else description="创建组织，定义长期协作关系。组织层级不自动扩大任务和上下文的访问范围。" />
  <a-card title="将 Agent 纳入主体目录" style="margin-top:20px"><a-space><a-select v-model:value="agentId" placeholder="选择已配置的 Agent" style="width:300px" :options="agents.map(a=>({value:a.id,label:a.name}))" /><a-button :disabled="!agentId" @click="run(async()=>{if(agentId) await api.registerAgent(agentId);await refresh();})">登记 Agent</a-button></a-space><p>登记主体后，再在组织或具体任务中授予角色。人类用户首次进入协作工作台时自动登记。</p></a-card>
  <a-modal v-model:open="open" :title="editing?'组织设置':'创建组织'" width="960px" :confirm-loading="busy" @ok="save">
    <a-form layout="vertical"><a-form-item label="名称"><a-input v-model:value="form.name" /></a-form-item><a-form-item label="描述"><a-textarea v-model:value="form.description" /></a-form-item><a-form-item v-if="!editing" label="上级组织（可选）"><a-select v-model:value="form.parentId" allow-clear :options="organizations.map(o=>({value:o.id,label:o.name}))" /></a-form-item><a-form-item label="角色和权限"><CollaborationRoleEditor v-model="form.roles" /></a-form-item></a-form>
  </a-modal>
</template>
