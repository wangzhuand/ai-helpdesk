<template>
  <el-container class="console">
    <!-- 左侧导航 -->
    <el-aside width="200px" class="aside">
      <div class="brand">AI 客服 · 坐席台</div>
      <!-- el-menu 加 router 属性后，点菜单项会直接按 index 当路径跳转，不用自己写 click 事件 -->
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/console/conversations">会话工作台</el-menu-item>
        <el-menu-item index="/console/tickets">工单管理</el-menu-item>
        <el-menu-item index="/console/knowledge">知识库</el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="title">{{ pageTitle }}</div>
        <div class="right">
          <span class="who">{{ nickname }}（{{ role }}）</span>
          <el-button size="small" text @click="logout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <!-- 子页面（会话工作台 / 工单管理 / 知识库）渲染在这里 -->
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

// 菜单高亮：直接用当前路径，省得再维护一份映射
const activeMenu = computed(() => route.path)

const nickname = localStorage.getItem('nickname') || '坐席'
const role = localStorage.getItem('role') || 'AGENT'

const TITLES = {
  '/console/conversations': '会话工作台',
  '/console/tickets': '工单管理',
  '/console/knowledge': '知识库'
}
const pageTitle = computed(() => TITLES[route.path] || '坐席台')

function logout() {
  // 清干净三样：token 是通行证，nickname/role 只用于顶栏展示
  localStorage.removeItem('token')
  localStorage.removeItem('nickname')
  localStorage.removeItem('role')
  router.push('/login')
}
</script>

<style scoped>
.console { height: 100vh; }

.aside {
  background: #001529;
  display: flex;
  flex-direction: column;
}
.brand {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  padding: 18px 16px;
  letter-spacing: 1px;
}
.menu {
  border-right: none;
  background: #001529;
  flex: 1;
}
/* Element Plus 的菜单默认是浅色，深色底要手动改这三个状态的颜色 */
.menu :deep(.el-menu-item) { color: rgba(255, 255, 255, .75); }
.menu :deep(.el-menu-item:hover) { background: #12263f; color: #fff; }
.menu :deep(.el-menu-item.is-active) { background: #1890ff; color: #fff; }

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
  background: #fff;
}
.title { font-size: 16px; font-weight: 600; }
.right { display: flex; align-items: center; gap: 8px; }
.who { font-size: 13px; color: #666; }

.main { background: #f5f7fa; padding: 16px; }
</style>
