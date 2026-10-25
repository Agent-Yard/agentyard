<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { appendSafeReturnTo, AUTH_DEV_BOOTSTRAP_LOGIN_PATH, AUTH_LOGIN_PATH } from '../services/api';
import { isNonPrdDeployEnv, resolveDeployEnv } from '../config/deployEnv';

const deployEnv = resolveDeployEnv(import.meta.env.VITE_DEPLOY_ENV, import.meta.env.MODE);
const showDevBootstrapLogin = isNonPrdDeployEnv(deployEnv);
const route = useRoute();
const returnTo = computed(() => typeof route.query.returnTo === 'string' ? route.query.returnTo : null);

function goToOidcLogin() {
  window.location.assign(appendSafeReturnTo(AUTH_LOGIN_PATH, returnTo.value));
}

function goToDevBootstrapLogin() {
  window.location.assign(appendSafeReturnTo(AUTH_DEV_BOOTSTRAP_LOGIN_PATH, returnTo.value));
}
</script>

<template>
  <div class="login-screen">
    <div class="login-card">
      <span class="login-card__eyebrow">AgentYard</span>
      <a-typography-title :level="2">登录你的协作空间</a-typography-title>
      <a-typography-paragraph type="secondary" class="login-card__meta">
        与 Agent 持续交流，和团队一起推进工作、共享资料与确认成果。
      </a-typography-paragraph>
      <div class="login-card__actions">
        <a-button type="primary" size="large" block @click="goToOidcLogin">使用组织账号登录</a-button>
        <a-button v-if="showDevBootstrapLogin" size="large" block @click="goToDevBootstrapLogin">
          本地管理员登录
        </a-button>
      </div>
    </div>
  </div>
</template>
