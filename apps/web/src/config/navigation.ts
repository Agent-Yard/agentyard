export type PageKey = 'reception' | 'tasks' | 'organizations' | 'role-templates' | 'entries' | 'playbook' | 'playbook-editor' | 'knowledge-library' | 'resource-library' | 'integration-account' | 'channel-admin';
export type SectionKey = 'work' | 'configuration';
export const defaultPageKey: PageKey = 'reception';
export const consoleBasePath = '/console';
export const sectionMeta = { work: { label: '协作', description: '与 Agent 交流，跟进工作与成果。' }, configuration: { label: '管理', description: '独立配置角色、能力和接待入口。' } };
export const pageMeta: Record<PageKey, { label: string; title: string; subtitle: string; section: SectionKey; path: string }> = {
  reception: { label: '与 Agent 交流', title: '与 Agent 交流', subtitle: '告诉我你想完成什么，也可以继续之前的工作。', section: 'work', path: '/console/reception' },
  tasks: { label: '工作与成果', title: '工作与成果', subtitle: '查看目标、进展、参与者、共享资料和决定。', section: 'work', path: '/console/tasks' },
  organizations: { label: '组织与协作', title: '组织与协作', subtitle: '维护长期成员关系与职责，具体工作独立授权。', section: 'work', path: '/console/organizations' },
  'role-templates': { label: '角色模板', title: '角色模板', subtitle: '提示词和能力引用独立版本化；执行权限由授权决定。', section: 'configuration', path: '/console/role-templates' },
  entries: { label: '接待入口', title: '接待入口', subtitle: '指定持续接待的角色和管理员授予的能力范围。', section: 'configuration', path: '/console/entries' },
  playbook: { label: '流程工具', title: '流程工具', subtitle: '可持久等待和恢复的工具，独立维护与发布版本。', section: 'configuration', path: '/console/playbooks' },
  'playbook-editor': { label: '流程编排', title: '流程编排', subtitle: '编辑节点、分支和等待点，保存为新的独立版本。', section: 'configuration', path: '/console/playbooks/editor' },
  'knowledge-library': { label: '知识库', title: '知识库', subtitle: '导入资料、构建索引和管理可引用的知识版本。', section: 'configuration', path: '/console/knowledge' },
  'resource-library': { label: '模型、工具与技能', title: '模型、工具与技能', subtitle: '独立维护共享能力和版本。', section: 'configuration', path: '/console/resources' },
  'integration-account': { label: '接入账号', title: '接入账号', subtitle: '管理连接器账号与凭证生命周期。', section: 'configuration', path: '/console/integration-accounts' },
  'channel-admin': { label: '沟通渠道', title: '沟通渠道', subtitle: '将渠道消息送入持续接待入口。', section: 'configuration', path: '/console/channel-admin' },
};
export const pagePathByKey = Object.fromEntries(Object.entries(pageMeta).map(([key, item]) => [key, item.path])) as Record<PageKey, string>;
export function resolvePageKeyFromPath(path: string): PageKey { return (Object.entries(pageMeta).find(([, item]) => item.path === path.replace(/\/$/, ''))?.[0] as PageKey) ?? defaultPageKey; }
export const menuItems = (['work', 'configuration'] as SectionKey[]).map(key => ({ key, label: sectionMeta[key].label,
  children: Object.entries(pageMeta).filter(([page, meta]) => meta.section === key && page !== 'playbook-editor').map(([page, meta]) => ({ key: page, label: meta.label })) }));
