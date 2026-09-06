<template>
  <div class="login-container">
    <el-card class="login-card">
      <h3>坐席登录</h3>
      <el-input v-model="username" placeholder="用户名" style="margin-bottom: 12px" />
      <el-input v-model="password" type="password" placeholder="密码" show-password
                @keyup.enter="login" style="margin-bottom: 12px" />
      <el-button type="primary" style="width: 100%" @click="login">登录</el-button>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

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
    ElMessage.success('登录成功，欢迎 ' + data.nickname + '（' + data.role + '）')
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
</style>
