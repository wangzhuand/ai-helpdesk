import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 固定 5174 + strictPort：
    // 本机 5173 常被另一个项目（drone-ground-station）的 dev server 占用，
    // strictPort 让端口被占时直接报错，而不是悄悄换到别的端口
    // （2026-09-21 踩过：只检查 HTTP 200 导致误判"本项目前端已在跑"）
    port: 5174,
    strictPort: true,
    // 代理：前端所有 /api 开头的请求，转发给后端 8080
    // 作用：浏览器看到的请求都来自 5174 自己，跨域问题从源头消失
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
