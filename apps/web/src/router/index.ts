import { createRouter, createWebHistory } from 'vue-router';
import ConsolePage from '../pages/ConsolePage.vue';
import LoginPage from '../pages/LoginPage.vue';

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
      name: 'console',
      component: ConsolePage,
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
});
