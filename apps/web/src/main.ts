import { createApp } from 'vue';
import 'ant-design-vue/dist/reset.css';
import App from './App.vue';
import { router } from './router';
import { setUnauthorizedHandler } from './services/api';
import './styles.css';

setUnauthorizedHandler(() => {
  if (router.currentRoute.value.path !== '/login') {
    void router.replace('/login');
  }
});

createApp(App).use(router).mount('#root');
