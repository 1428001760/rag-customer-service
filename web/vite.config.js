import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 打包产物直接输出到 Spring Boot 的静态资源目录，
  // 这样 `mvn package` 出来的 jar 里就带着前端，不用单独部署 nginx
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  },
  server: {
    port: 5173,
    proxy: {
      // 开发时把接口转发到本地跑的 Spring Boot
      '/chat': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/admin': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/ws': { target: 'ws://127.0.0.1:8080', ws: true }
    }
  }
})
