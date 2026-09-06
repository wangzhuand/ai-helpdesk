import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 代理：前端所有 /api 开头的请求，转发给后端 8080
    // 作用：浏览器看到的请求都来自 5173 自己，跨域问题从源头消失
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
