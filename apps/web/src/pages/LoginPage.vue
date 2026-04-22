<script setup lang="ts">
import { AUTH_DEV_BOOTSTRAP_LOGIN_PATH, AUTH_LOGIN_PATH } from '../services/api';
import { isNonPrdDeployEnv, resolveDeployEnv } from '../config/deployEnv';

const deployEnv = resolveDeployEnv(import.meta.env.VITE_DEPLOY_ENV, import.meta.env.MODE);
const showDevBootstrapLogin = isNonPrdDeployEnv(deployEnv);

function goToOidcLogin() {
  window.location.assign(AUTH_LOGIN_PATH);
}

function goToDevBootstrapLogin() {
  window.location.assign(AUTH_DEV_BOOTSTRAP_LOGIN_PATH);
}
</script>

<template>
  <div class="login-screen">
    <div class="login-card">
      <span class="login-card__eyebrow">OIDC Sign-In</span>
      <a-typography-title :level="2">登录 Lynxus 控制台</a-typography-title>
      <a-typography-paragraph type="secondary" class="login-card__meta">
        控制台认证已切换为 API 托管会话。浏览器不会持久化 access token，登录完成后只通过同域 HttpOnly session cookie 访问 `/api`。
      </a-typography-paragraph>
      <div class="login-card__actions">
        <a-button type="primary" size="large" block @click="goToOidcLogin">使用 OIDC 登录</a-button>
        <a-button v-if="showDevBootstrapLogin" size="large" block @click="goToDevBootstrapLogin">
          开发态 Bootstrap 登录
        </a-button>
      </div>
    </div>
  </div>
</template>
