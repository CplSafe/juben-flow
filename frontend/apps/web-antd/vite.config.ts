import { fileURLToPath } from 'node:url';
import { defineConfig } from '@vben/vite-config';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      // Shared workspace packages can be reached through older node_modules symlinks.
      // All widgets and application bootstrap must use the same i18n instance.
      resolve: {
        alias: [
          {
            find: /^@vben\/locales$/,
            replacement: fileURLToPath(
              new URL('../../packages/locales/src/index.ts', import.meta.url),
            ),
          },
        ],
      },
      plugins: [
        {
          name: 'workflow-locale-singleton-check',
          generateBundle() {
            const instances = [...this.getModuleIds()].filter((id) =>
              /[/\\]packages[/\\]locales[/\\]src[/\\]i18n\.ts$/.test(id),
            );
            if (instances.length !== 1)
              this.error(`Expected one shared i18n instance, found ${instances.length}`);
          },
        },
      ],
      server: {
        allowedHosts: true,
        proxy: {
          '/admin-api': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/admin-api/, ''),
            // mock代理目标地址
            target: 'http://localhost:48080/admin-api',
            ws: true,
          },
        },
      },
    },
  };
});
