<script setup lang="ts">
import { ref } from 'vue';
import type { CollaborationActor, CollaborationRole, TaskParticipant } from '../../../../packages/contracts/src/task';
defineProps<{ actors: CollaborationActor[]; roles: CollaborationRole[]; participants: TaskParticipant[]; editable: boolean; busy: boolean }>();
const emit = defineEmits<{ save: [actorId: string, roles: string[]] }>();
const actorId = ref<string>(); const assigned = ref<string[]>([]);
</script>
<template>
  <a-list :data-source="participants" bordered>
    <template #renderItem="{ item }"><a-list-item>
      <a-space wrap><strong>{{ actors.find(a=>a.id===item.actorId)?.displayName ?? item.actorId }}</strong><a-tag>{{ actors.find(a=>a.id===item.actorId)?.kind }}</a-tag><a-tag v-for="role in item.roles" :key="role">{{ roles.find(r=>r.key===role)?.name ?? role }}</a-tag></a-space>
      <a-space v-if="editable"><a-button size="small" @click="actorId=item.actorId; assigned=[...item.roles]">调整角色</a-button><a-popconfirm title="移除该参与者？" @confirm="emit('save',item.actorId,[])"><a-button danger size="small">移除</a-button></a-popconfirm></a-space>
    </a-list-item></template>
  </a-list>
  <a-space v-if="editable" wrap style="margin-top: 16px">
    <a-select v-model:value="actorId" show-search option-filter-prop="label" placeholder="选择人或 Agent" style="width:240px" :options="actors.map(a=>({value:a.id,label:`${a.displayName} · ${a.kind}`}))" />
    <a-select v-model:value="assigned" mode="multiple" placeholder="授予角色" style="min-width:240px" :options="roles.map(r=>({value:r.key,label:r.name}))" />
    <a-button :disabled="!actorId || !assigned.length" :loading="busy" @click="actorId && emit('save',actorId,assigned)">保存参与关系</a-button>
  </a-space>
</template>
