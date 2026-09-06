import axios from 'axios'

// 统一的 axios 实例：baseURL 为 /api，vite 会代理到后端 8080
const api = axios.create({ baseURL: '/api', timeout: 30000 })

// 响应拦截器：把后端统一信封 { code, message, data } 拆开——
// 成功直接返回 data，失败抛出 message，页面里不用每个请求都判断 code
api.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body.data
  },
  (error) => {
    const msg = error.response?.data?.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

export default api
