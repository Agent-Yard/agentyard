import { createApp } from 'vue';
import 'ant-design-vue/dist/reset.css';
import App from './App.vue';
import { router } from './router';
import { buildLoginRedirectPath, setUnauthorizedHandler } from './services/api';
import './styles.css';

setUnauthorizedHandler(() => {
  const currentRoute = router.currentRoute.value;
  if (currentRoute.path !== '/login') {
    void router.replace(buildLoginRedirectPath(currentRoute));
  }
});

createApp(App).use(router).mount('#root');
