<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import CatalogFormDrawer from '../components/CatalogFormDrawer.vue';
import ObjectHistoryPanel from '../components/ObjectHistoryPanel.vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import type { BusinessDomain, CreateDomainPayload, UpdateDomainPayload } from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  createDomain: [payload: CreateDomainPayload];
  updateDomain: [payload: { domainId: string; data: UpdateDomainPayload }];
  deleteDomain: [domainId: string];
}>();

const selectedDomainId = ref('');
const createDrawerOpen = ref(false);
const editDrawerOpen = ref(false);
const createForm = reactive<CreateDomainPayload>({
  name: '',
  description: '',
});
const editForm = reactive<UpdateDomainPayload>({
  name: '',
  description: '',
});

const current = computed(() =>
  props.domains.find((item) => item.id === selectedDomainId.value) ?? props.domains[0] ?? null,
);
const scenarioCount = computed(() => props.domains.flatMap((item) => item.scenarios).length);
const resourceCount = computed(() => props.domains.flatMap((item) => item.resources).length);
const assistantCount = computed(() => props.domains.flatMap((item) => item.scenarios).flatMap((item) => item.assistants).length);

watch(
  () => props.domains,
  (domains) => {
    if (!domains.length) {
      selectedDomainId.value = '';
      return;
    }
    if (!domains.some((item) => item.id === selectedDomainId.value)) {
      selectedDomainId.value = domains[0].id;
    }
  },
  { immediate: true },
);

watch(
  current,
  (domain) => {
    if (!domain) {
      return;
    }
    editForm.name = domain.name;
    editForm.description = domain.description;
  },
  { immediate: true },
);

function submitCreate() {
  emit('createDomain', {
    name: createForm.name,
    description: createForm.description,
  });
  createDrawerOpen.value = false;
  createForm.name = '';
  createForm.description = '';
}

function submitUpdate() {
  if (!current.value) {
    return;
  }
  emit('updateDomain', {
    domainId: current.value.id,
    data: {
      name: editForm.name,
      description: editForm.description,
    },
  });
}

function openEditDrawer(domainId: string) {
  selectedDomainId.value = domainId;
  const domain = props.domains.find((item) => item.id === domainId);
  if (domain) {
    editForm.name = domain.name;
    editForm.description = domain.description;
  }
  editDrawerOpen.value = true;
}
</script>

<template>
  <div v-if="canManageGovernance" class="page-inline-toolbar">
    <a-button type="primary" @click="createDrawerOpen = true">新建业务域</a-button>
  </div>

  <a-row :gutter="[16, 16]">
    <a-col :span="6">
      <a-card><a-statistic title="业务域数" :value="domains.length" /></a-card>
    </a-col>
    <a-col :span="6">
      <a-card><a-statistic title="场景总数" :value="scenarioCount" /></a-card>
    </a-col>
    <a-col :span="6">
      <a-card><a-statistic title="域下资源" :value="resourceCount" /></a-card>
    </a-col>
    <a-col :span="6">
      <a-card><a-statistic title="承载助手" :value="assistantCount" /></a-card>
    </a-col>
  </a-row>

  <a-row :gutter="[16, 16]">
    <a-col :span="9">
      <a-card title="业务域列表">
        <a-list :data-source="domains" :locale="{ emptyText: '暂无业务域' }">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': current?.id === item.id }"
              @click="selectedDomainId = item.id"
            >
              <a-list-item-meta :title="item.name" :description="item.description || '暂无说明'" />
              <a-space>
                <a-tag class="console-accent-tag">{{ item.scenarios.length }} 场景</a-tag>
                <a-tag>{{ item.resources.length }} 资源</a-tag>
                <a-button v-if="canManageGovernance" type="link" size="small" @click.stop="openEditDrawer(item.id)">编辑</a-button>
                <a-button v-if="canManageGovernance" type="link" size="small" danger @click.stop="emit('deleteDomain', item.id)">
                  删除
                </a-button>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="15">
      <div v-if="current" class="console-stack">
        <a-card :title="current.name">
          <template #extra>
            <a-space>
              <a-tag class="console-accent-tag">{{ current.scenarios.length }} 个场景</a-tag>
              <a-tag>{{ current.resources.length }} 个资源</a-tag>
            </a-space>
          </template>

          <a-descriptions :column="3" size="small">
            <a-descriptions-item label="业务域 ID">{{ current.id }}</a-descriptions-item>
            <a-descriptions-item label="助手数">
              {{ current.scenarios.flatMap((item) => item.assistants).length }}
            </a-descriptions-item>
            <a-descriptions-item label="资源数">{{ current.resources.length }}</a-descriptions-item>
            <a-descriptions-item label="描述" :span="3">
              {{ current.description || '暂无说明' }}
            </a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card title="当前域承载关系">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-list header="业务场景" :data-source="current.scenarios" :locale="{ emptyText: '暂无场景' }">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta :title="item.name" :description="item.goal" />
                    <a-tag>{{ item.assistants.length }} 助手</a-tag>
                  </a-list-item>
                </template>
              </a-list>
            </a-col>
            <a-col :span="12">
              <a-list header="域内资源" :data-source="current.resources" :locale="{ emptyText: '暂无资源' }">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta :title="item.name" :description="item.summary || '暂无摘要'" />
                    <a-tag>{{ item.type }}</a-tag>
                  </a-list-item>
                </template>
              </a-list>
            </a-col>
          </a-row>
        </a-card>

        <ObjectReferencePanel
          object-type="DOMAIN"
          :object-id="current.id"
          :reload-key="catalogRevision"
        />

        <ObjectHistoryPanel
          aggregate-type="DOMAIN"
          :object-id="current.id"
          :reload-key="catalogRevision"
        />
      </div>

      <a-empty v-else description="暂无业务域，请先创建" />
    </a-col>
  </a-row>

  <CatalogFormDrawer
    :open="createDrawerOpen"
    title="新建业务域"
    :width="480"
    kicker="01.01 / 平台设计 / 业务域"
    @close="createDrawerOpen = false"
  >
    <a-form layout="vertical" :model="createForm" @finish="submitCreate">
      <a-form-item label="业务域名称">
        <a-input v-model:value="createForm.name" placeholder="例如：客户运营域" />
      </a-form-item>
      <a-form-item label="业务域说明">
        <a-textarea
          v-model:value="createForm.description"
          :rows="6"
          placeholder="描述域边界、职责范围和核心资产"
        />
      </a-form-item>
      <div class="create-drawer__actions">
        <a-button @click="createDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">创建业务域</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>

  <CatalogFormDrawer
    :open="editDrawerOpen"
    title="编辑业务域"
    :width="480"
    kicker="01.01 / 平台设计 / 业务域"
    @close="editDrawerOpen = false"
  >
    <a-form layout="vertical" :model="editForm" @finish="submitUpdate">
      <a-form-item label="业务域名称">
        <a-input v-model:value="editForm.name" />
      </a-form-item>
      <a-form-item label="业务域说明">
        <a-textarea v-model:value="editForm.description" :rows="6" />
      </a-form-item>
      <div class="create-drawer__actions">
        <a-button @click="editDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">保存业务域</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>
</template>
