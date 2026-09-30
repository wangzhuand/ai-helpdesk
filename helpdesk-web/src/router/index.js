import { createRouter, createWebHistory } from 'vue-router'
import Chat from '../views/Chat.vue'
import Login from '../views/Login.vue'
import ConsoleLayout from '../layout/ConsoleLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: Chat },
    { path: '/login', component: Login },
    {
      // ★ W6-5：坐席控制台。用一个"布局组件"包住所有子页面——
      //   侧边栏和顶栏只在布局里写一次，切换子页面时它们不会重新渲染
      path: '/console',
      component: ConsoleLayout,
      redirect: '/console/conversations',
      children: [
        // 子页面用"路由懒加载"（() => import(...)）：
        // 首屏只下载当前页面的代码，不把控制台三个页面全塞进入口包
        { path: 'conversations', component: () => import('../views/console/Conversations.vue') },
        { path: 'tickets', component: () => import('../views/console/Tickets.vue') },
        { path: 'knowledge', component: () => import('../views/console/Knowledge.vue') }
      ]
    }
  ]
})

// ★ W6-5：路由守卫——控制台必须先登录。
// 为什么放在路由层而不是每个页面里各写一遍：登录态是"整个控制台"的约束，
// 在这里拦一次就够了；以后新增控制台页面，不用再记得加判断（漏加就是白送一个后门）。
router.beforeEach((to) => {
  if (to.path.startsWith('/console') && !localStorage.getItem('token')) {
    // 记下"你本来想去哪"，登录成功后原路返回（见 Login.vue）
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})

export default router
