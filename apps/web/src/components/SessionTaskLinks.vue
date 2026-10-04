<script setup lang="ts">
import { ref, watch } from 'vue';
import { collaborationApi } from '../services/collaboration';
import type { BusinessTask } from '../../../../packages/contracts/src/task';
const props=defineProps<{sessionId:string|null}>();const tasks=ref<BusinessTask[]>([]);const error=ref('');
watch(()=>props.sessionId,async id=>{tasks.value=[];error.value='';if(!id)return;try{const result=await collaborationApi.sessionTasks(id);if(props.sessionId===id)tasks.value=result;}catch(e){error.value=e instanceof Error?e.message:String(e);}},{immediate:true});
</script>
<template><a-space v-if="sessionId" wrap style="margin-bottom:16px"><span>关联任务</span><router-link v-for="task in tasks" :key="task.id" :to="{path:'/console/tasks',query:{taskId:task.id}}"><a-tag color="blue">{{ task.title }}</a-tag></router-link><router-link to="/console/tasks">进入协作工作台</router-link><span v-if="error">{{ error }}</span></a-space></template>
