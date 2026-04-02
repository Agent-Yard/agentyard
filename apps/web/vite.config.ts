import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const CONFIG_DIR = dirname(fileURLToPath(import.meta.url));
const WORKSPACE_ROOT = resolve(CONFIG_DIR, '../..');

function parseCsv(value?: string): string[] {
  if (!value) {
    return [];
  }

  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

function parseOptionalNumber(value?: string): number | undefined {
  if (!value?.trim()) {
    return undefined;
  }

  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, WORKSPACE_ROOT, '');
  const apiProxyTarget = env.LYNXUS_WEB_DEV_PROXY_TARGET || 'http://127.0.0.1:8080';
  const webPort = Number(env.LYNXUS_WEB_PORT || 5173);
  const allowedHosts = parseCsv(env.LYNXUS_WEB_ALLOWED_HOSTS);
  const hmrProtocol = env.LYNXUS_WEB_HMR_PROTOCOL?.trim();
  const hmrHost = env.LYNXUS_WEB_HMR_HOST?.trim();
  const hmrPort = parseOptionalNumber(env.LYNXUS_WEB_HMR_PORT);
  const hmrClientPort = parseOptionalNumber(env.LYNXUS_WEB_HMR_CLIENT_PORT);
  const shouldConfigureHmr = Boolean(hmrProtocol || hmrHost || hmrPort || hmrClientPort);

  return {
    plugins: [
      vue(),
      Components({
        resolvers: [
          AntDesignVueResolver({
            importStyle: false,
          }),
        ],
      }),
    ],
    envDir: WORKSPACE_ROOT,
    server: {
      port: webPort,
      allowedHosts: allowedHosts.length > 0 ? allowedHosts : undefined,
      hmr: shouldConfigureHmr
        ? {
            protocol: hmrProtocol,
            host: hmrHost,
            port: hmrPort,
            clientPort: hmrClientPort,
          }
        : undefined,
      proxy: {
        '/oauth2': {
          target: apiProxyTarget,
        },
        '/login/oauth2': {
          target: apiProxyTarget,
        },
        '/api': {
          target: apiProxyTarget,
        },
      },
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules/vue')) {
              return 'vue-vendor';
            }

            if (id.includes('node_modules/@ant-design/icons-vue')) {
              return 'antdv-icons';
            }

            if (id.includes('node_modules/ant-design-vue')) {
              const match = id.match(/ant-design-vue\/(?:es|lib)\/([^/]+)/);
              const segment = match?.[1];

              if (!segment || segment.startsWith('_') || segment.startsWith('vc-')) {
                return 'antdv-core';
              }

              if (['style', 'config-provider', 'locale', 'theme', 'app'].includes(segment)) {
                return 'antdv-core';
              }

              return `antdv-${segment}`;
            }

            if (id.includes('node_modules')) {
              return 'vendor';
            }

            return undefined;
          },
        },
      },
    },
  };
});
