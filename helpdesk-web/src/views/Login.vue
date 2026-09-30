<template>
  <div class="login-container">
    <el-card class="login-card">
      <h3>坐席登录</h3>
      <el-input v-model="username" placeholder="用户名" style="margin-bottom: 12px" />
      <el-input v-model="password" type="password" placeholder="密码" show-password
                @keyup.enter="login" style="margin-bottom: 12px" />
      <el-button type="primary" style="width: 100%" @click="login">登录</el-button>
      <div class="tip">访客聊天入口：<router-link to="/chat">/chat</router-link></div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'

const route = useRoute()
const router = useRouter()

const username = ref('admin')
const password = ref('')

async function login() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  try {
    const data = await api.post('/console/login', {
      username: username.value,
      password: password.value
    })
    localStorage.setItem('token', data.token)
    // ★ W6-5：昵称和角色只用于控制台顶栏展示，和鉴权无关
    localStorage.setItem('nickname', data.nickname || '')
    localStorage.setItem('role', data.role || '')
    ElMessage.success('登录成功，欢迎 ' + data.nickname + '（' + data.role + '）')

    // ★ W6-5：登录成功跳控制台。
    //   优先回跳到"被路由守卫拦下来的那个页面"（守卫会带上 ?redirect=...），
    //   没有就落到会话工作台 —— 这样"点链接→被拦→登录→原路返回"是一条顺的体验
    router.push(route.query.redirect || '/console/conversations')
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card { width: 360px; }
.login-card h3 { text-align: center; margin-top: 0; }
.tip {
  margin-top: 14px;
  font-size: 12px;
  color: #999;
  text-align: center;
}
</style>
