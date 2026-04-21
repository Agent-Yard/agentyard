export type PageKey =
  | 'domain'
  | 'scenario'
  | 'assistant'
  | 'agent'
  | 'playbook'
  | 'knowledge-library'
  | 'resource-library'
  | 'runtime';

export type SectionKey = 'design' | 'build' | 'knowledge' | 'resource' | 'runtime-observe';

export const defaultPageKey: PageKey = 'domain';
export const consoleBasePath = '/console';

export const sectionMeta: Record<SectionKey, { label: string; description: string }> = {
  design: {
    label: '平台设计',
    description: '沉淀业务域和场景边界，定义平台承载的业务上下文。',
  },
  build: {
    label: '助手构建',
    description: '围绕助手、智能体和运行策略，完成核心协作链路配置。',
  },
  knowledge: {
    label: '知识库',
    description: '管理知识库目录、内容导入、索引快照和发布版本。',
  },
  resource: {
    label: '能力资源',
    description: '管理 Tool / LLM / Skill 等可复用能力资源。',
  },
  'runtime-observe': {
    label: '运行与观测',
    description: '查看运行会话、任务流程和人工介入状态。',
  },
};

export const pageMeta: Record<PageKey, { label: string; title: string; subtitle: string; section: SectionKey; path: string }> = {
  domain: {
    label: '业务域',
    title: '业务域页',
    subtitle: '查看平台承载的业务域、域内资源和场景入口。',
    section: 'design',
    path: `${consoleBasePath}/domains`,
  },
  scenario: {
    label: '业务场景',
    title: '业务场景页',
    subtitle: '定义业务目标、场景边界以及承载它的助手。',
    section: 'design',
    path: `${consoleBasePath}/scenarios`,
  },
  assistant: {
    label: '助手配置',
    title: '助手配置页',
    subtitle: '管理助手基础信息、发布状态和冻结资源版本快照。',
    section: 'build',
    path: `${consoleBasePath}/assistants`,
  },
  agent: {
    label: '智能体',
    title: '智能体详情与资源绑定页',
    subtitle: '配置智能体职责、继承覆盖和可用 Tool 集。',
    section: 'build',
    path: `${consoleBasePath}/agents`,
  },
  playbook: {
    label: 'Playbook',
    title: 'Playbook 配置页',
    subtitle: '维护强流程定义、节点、边以及等待点策略。',
    section: 'build',
    path: `${consoleBasePath}/playbooks`,
  },
  'knowledge-library': {
    label: '知识库目录',
    title: '知识库工作台',
    subtitle: '集中处理内容导入、文档、索引快照、发布版本与引用分析。',
    section: 'knowledge',
    path: `${consoleBasePath}/knowledge`,
  },
  'resource-library': {
    label: '资源目录',
    title: '资源目录页',
    subtitle: '查看 Tool / LLM / Skill 的版本流转、生效状态和引用分析。',
    section: 'resource',
    path: `${consoleBasePath}/resources`,
  },
  runtime: {
    label: '会话运行',
    title: '运行时对话页',
    subtitle: '围绕 session event、当前 owner、playbook 和 handoff 观察运行状态。',
    section: 'runtime-observe',
    path: `${consoleBasePath}/runtime`,
  },
};

export const pagePathByKey: Record<PageKey, string> = Object.fromEntries(
  Object.entries(pageMeta).map(([key, meta]) => [key, meta.path]),
) as Record<PageKey, string>;

const pageKeyByPath = Object.fromEntries(
  Object.entries(pageMeta).map(([key, meta]) => [meta.path, key]),
) as Record<string, PageKey>;

export function resolvePageKeyFromPath(path: string): PageKey {
  const normalizedPath = path.length > 1 ? path.replace(/\/+$/, '') : path;
  return pageKeyByPath[normalizedPath] ?? defaultPageKey;
}

export const menuItems = [
  {
    key: 'design',
    label: sectionMeta.design.label,
    children: [
      { key: 'domain', label: pageMeta.domain.label },
      { key: 'scenario', label: pageMeta.scenario.label },
    ],
  },
  {
    key: 'build',
    label: sectionMeta.build.label,
    children: [
      { key: 'assistant', label: pageMeta.assistant.label },
      { key: 'agent', label: pageMeta.agent.label },
      { key: 'playbook', label: pageMeta.playbook.label },
    ],
  },
  {
    key: 'knowledge',
    label: sectionMeta.knowledge.label,
    children: [
      { key: 'knowledge-library', label: pageMeta['knowledge-library'].label },
    ],
  },
  {
    key: 'resource',
    label: sectionMeta.resource.label,
    children: [
      { key: 'resource-library', label: pageMeta['resource-library'].label },
    ],
  },
  {
    key: 'runtime-observe',
    label: sectionMeta['runtime-observe'].label,
    children: [
      { key: 'runtime', label: pageMeta.runtime.label },
    ],
  },
];
