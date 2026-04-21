import { createRouter, createWebHistory } from 'vue-router';
import { consoleBasePath, defaultPageKey, pageMeta, type PageKey } from '../config/navigation';

const LoginPage = () => import('../pages/LoginPage.vue');
const ConsolePage = () => import('../pages/ConsolePage.vue');

const consolePageComponents: Record<PageKey, () => Promise<unknown>> = {
  domain: () => import('../pages/DomainPage.vue'),
  scenario: () => import('../pages/ScenarioPage.vue'),
  assistant: () => import('../pages/AssistantPage.vue'),
  agent: () => import('../pages/AgentPage.vue'),
  playbook: () => import('../pages/PlaybookPage.vue'),
  'playbook-editor': () => import('../pages/PlaybookEditorPage.vue'),
  'knowledge-library': () => import('../pages/KnowledgeLibraryPage.vue'),
  'resource-library': () => import('../pages/ResourceLibraryPage.vue'),
  runtime: () => import('../pages/RuntimeConversationPage.vue'),
};

const defaultConsolePath = pageMeta[defaultPageKey].path;
const consoleChildRoutes = (Object.entries(pageMeta) as [PageKey, typeof pageMeta[PageKey]][]).map(([pageKey, meta]) => ({
  path: meta.path.slice(`${consoleBasePath}/`.length),
  name: pageKey,
  component: consolePageComponents[pageKey],
}));

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginPage,
    },
    {
      path: '/',
      redirect: defaultConsolePath,
    },
    {
      path: consoleBasePath,
      name: 'console',
      component: ConsolePage,
      children: [
        {
          path: '',
          redirect: defaultConsolePath,
        },
        ...consoleChildRoutes,
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: defaultConsolePath,
    },
  ],
});
