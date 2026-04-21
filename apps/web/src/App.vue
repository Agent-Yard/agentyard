<script setup lang="ts">
const antTheme = {
  token: {
    colorPrimary: '#b66d2a',
    colorInfo: '#b66d2a',
    colorLink: '#8d521b',
    colorLinkHover: '#b66d2a',
    colorLinkActive: '#6f3f14',
    colorPrimaryHover: '#8d521b',
    colorPrimaryActive: '#6f3f14',
    colorPrimaryBorder: '#8d521b',
    colorPrimaryBorderHover: '#8d521b',
    colorTextPlaceholder: '#a2a8b0',
    controlOutline: 'rgba(182, 109, 42, 0.14)',
    controlOutlineWidth: 2,
  },
  components: {
    Button: {
      primaryShadow: 'none',
      dangerShadow: 'none',
      defaultShadow: 'none',
      linkHoverBg: 'transparent',
    },
    Input: {
      hoverBorderColor: '#b66d2a',
      activeBorderColor: '#8d521b',
      activeShadow: '0 0 0 2px rgba(182, 109, 42, 0.14)',
    },
    InputNumber: {
      hoverBorderColor: '#b66d2a',
      activeBorderColor: '#8d521b',
      activeShadow: '0 0 0 2px rgba(182, 109, 42, 0.14)',
    },
    Select: {
      hoverBorderColor: '#b66d2a',
      activeBorderColor: '#8d521b',
      activeOutlineColor: 'rgba(182, 109, 42, 0.14)',
    },
    DatePicker: {
      hoverBorderColor: '#b66d2a',
      activeBorderColor: '#8d521b',
      activeShadow: '0 0 0 2px rgba(182, 109, 42, 0.14)',
    },
    Mentions: {
      hoverBorderColor: '#b66d2a',
      activeBorderColor: '#8d521b',
    },
  },
} as const;
</script>

<template>
  <a-config-provider :theme="antTheme">
    <router-view />
  </a-config-provider>
</template>
