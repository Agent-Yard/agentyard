<script setup lang="ts">
import { computed } from 'vue';
import type { ResourceOwnerType } from '../types';
import { ownershipTypes, ownerChoices, type OwnershipOption } from '../composables/ownership';
const props = defineProps<{ options: OwnershipOption[] }>();
const ownerType = defineModel<ResourceOwnerType>('ownerType', { required:true });
const ownerId = defineModel<string>('ownerId', { required:true });
const types = computed(() => ownershipTypes.filter(type => props.options.some(option => option.ownerType === type.value)));
const owners = computed(() => ownerChoices(props.options, ownerType.value));
function changeType(value:ResourceOwnerType) { ownerType.value=value;ownerId.value=ownerChoices(props.options,value)[0]?.value??''; }
</script>
<template><a-form-item label="配置归属" required><a-space><a-select :value="ownerType" :options="types" @change="changeType" style="width:120px" /><a-select v-model:value="ownerId" :options="owners" style="min-width:200px" /></a-space></a-form-item></template>
