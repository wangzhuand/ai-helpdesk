import { createRouter, createWebHistory } from 'vue-router'
import Chat from '../views/Chat.vue'
import Login from '../views/Login.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: Chat },
    { path: '/login', component: Login }
  ]
})

export default router
