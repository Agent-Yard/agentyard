import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers';

export default defineConfig({
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
  server: {
    port: 5173,
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
});
