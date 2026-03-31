<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import type {
  BusinessDomain,
  CreateScenarioPayload,
  Scenario,
  UpdateScenarioPayload,
} from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  scenarios: Scenario[];
  catalogRevision: number;
}>();

const emit = defineEmits<{
  createScenario: [payload: CreateScenarioPayload];
  updateScenario: [payload: { scenarioId: string; data: UpdateScenarioPayload }];
  deleteScenario: [scenarioId: string];
}>();

const selectedScenarioId = ref('');
const createForm = reactive<CreateScenarioPayload>({
  domainId: '',
  name: '',
  goal: '',
});
const editForm = reactive<UpdateScenarioPayload>({
  name: '',
  goal: '',
});

const current = computed(() =>
  props.scenarios.find((item) => item.id === selectedScenarioId.value) ?? props.scenarios[0] ?? null,
);
const currentDomain = computed(() =>
  props.domains.find((item) => item.id === current.value?.domainId) ?? null,
);
const assistantCount = computed(() => props.scenarios.flatMap((item) => item.assistants).length);
const publishedCount = computed(() => props.scenarios.filter((item) => item.version.status === 'PUBLISHED').length);

watch(
  () => props.scenarios,
  (scenarios) => {
    if (!scenarios.length) {
      selectedScenarioId.value = '';
      return;
    }
    if (!scenarios.some((item) => item.id === selectedScenarioId.value)) {
      selectedScenarioId.value = scenarios[0].id;
    }
  },
  { immediate: true },
);

watch(
  () => props.domains,
  (domains) => {
    if (!createForm.domainId && domains.length) {
      createForm.domainId = domains[0].id;
    }
  },
  { immediate: true },
);

watch(
  current,
  (scenario) => {
    if (!scenario) {
      return;
    }
    editForm.name = scenario.name;
    editForm.goal = scenario.goal;
  },
  { immediate: true },
);

function submitCreate() {
  emit('createScenario', {
    domainId: createForm.domainId,
    name: createForm.name,
    goal: createForm.goal,
  });
  createForm.name = '';
  createForm.goal = '';
}

function submitUpdate() {
  if (!current.value) {
    return;
  }
  emit('updateScenario', {
    scenarioId: current.value.id,
    data: {
      name: editForm.name,
      goal: editForm.goal,
    },
  });
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="6">
      <a-card><a-statistic title="业务场景数" :value="scenarios.length" /></a-card>
    </a-col>
    <a-col :span="6">
      <a-card><a-statistic title="已发布场景" :value="publishedCount" /></a-card>
    </a-col>
    <a-col :span="6">
      <a-card><a-statistic title="关联业务域" :value="domains.length" /></a-card>
    </a-col>
    <a-col :span="6">
      <a-card><a-statistic title="场景承载助手" :value="assistantCount" /></a-card>
    </a-col>
  </a-row>

  <a-row :gutter="[16, 16]">
    <a-col :span="9">
      <a-card title="新建业务场景">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="所属业务域">
            <a-select
              v-model:value="createForm.domainId"
              :options="domains.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="场景名称">
            <a-input v-model:value="createForm.name" placeholder="例如：客户投诉升级处理" />
          </a-form-item>
          <a-form-item label="场景目标">
            <a-textarea
              v-model:value="createForm.goal"
              :rows="4"
              placeholder="描述该场景要交付的业务结果"
            />
          </a-form-item>
          <a-button type="primary" html-type="submit">创建场景</a-button>
        </a-form>
      </a-card>

      <a-card title="场景列表">
        <a-list :data-source="scenarios" :locale="{ emptyText: '暂无业务场景' }">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': current?.id === item.id }"
              @click="selectedScenarioId = item.id"
            >
              <a-list-item-meta
                :title="item.name"
                :description="domains.find((domain) => domain.id === item.domainId)?.name ?? item.domainId"
              />
              <a-space>
                <a-tag>{{ item.assistants.length }} 助手</a-tag>
                <a-tag :color="item.version.status === 'PUBLISHED' ? 'green' : 'gold'">
                  {{ item.version.status }}
                </a-tag>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="15">
      <a-card v-if="current" :title="current.name">
        <template #extra>
          <a-space>
            <a-tag color="blue">{{ current.assistants.length }} 个助手</a-tag>
            <a-tag :color="current.version.status === 'PUBLISHED' ? 'green' : 'gold'">
              {{ current.version.version }}
            </a-tag>
          </a-space>
        </template>

        <a-descriptions :column="3" size="small">
          <a-descriptions-item label="场景 ID">{{ current.id }}</a-descriptions-item>
          <a-descriptions-item label="所属业务域">
            {{ currentDomain?.name ?? current.domainId }}
          </a-descriptions-item>
          <a-descriptions-item label="助手数">{{ current.assistants.length }}</a-descriptions-item>
          <a-descriptions-item label="目标" :span="3">
            {{ current.goal }}
          </a-descriptions-item>
        </a-descriptions>

        <a-divider />

        <a-form layout="vertical" :model="editForm" @finish="submitUpdate">
          <a-form-item label="场景名称">
            <a-input v-model:value="editForm.name" />
          </a-form-item>
          <a-form-item label="场景目标">
            <a-textarea v-model:value="editForm.goal" :rows="4" />
          </a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit">保存场景</a-button>
            <a-button danger @click="emit('deleteScenario', current.id)">删除场景</a-button>
          </a-space>
        </a-form>
      </a-card>

      <a-card v-if="current" title="当前场景承载助手">
        <a-list :data-source="current.assistants" :locale="{ emptyText: '当前场景下暂无助手' }">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta :title="item.name" :description="item.description || '暂无描述'" />
              <a-tag :color="item.version.status === 'PUBLISHED' ? 'green' : 'gold'">
                {{ item.version.status }}
              </a-tag>
            </a-list-item>
          </template>
        </a-list>
      </a-card>

      <ObjectReferencePanel
        v-if="current"
        style="margin-top: 16px"
        object-type="SCENARIO"
        :object-id="current.id"
        :reload-key="catalogRevision"
      />

      <a-empty v-else description="暂无业务场景，请先创建" />
    </a-col>
  </a-row>
</template>
