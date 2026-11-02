import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

/**
 * 管理台前端工程：构建产物直接接管 oryxos-web 的 /admin/ 静态资源目录。
 *
 * 这里是「整体改造」而不是「新增一个页面」：原来 static/admin/ 下的 index.html + admin.js
 * 由这套 React 应用的产物覆盖，因此只有 /admin/ 一个入口，不存在并行的第二套前端。
 */
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  build: {
    outDir: fileURLToPath(new URL('../oryxos-web/src/main/resources/static/admin', import.meta.url)),
    // 目标目录在工程外，必须显式打开清空，否则旧的原生页面会残留
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    // 开发态直连本机后端：走同源代理，免 CORS 配置
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
