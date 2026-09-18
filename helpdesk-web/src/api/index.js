import axios from 'axios'

// 统一的 axios 实例：baseURL 为 /api，vite 会代理到后端 8080
const api = axios.create({ baseURL: '/api', timeout: 30000 })

// 请求拦截器：有 token 就带上。
// 为什么需要：登录成功后 token 存在 localStorage，但之前没有任何地方把它塞进请求头，
// 所以坐席控制台接口（/api/console/**）一旦调用就是 401。
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

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
    // 401 只能出现在这个错误分支里，不能写在上面那个成功分支。
    // 原因：后端两类错误的 HTTP 行为不同——
    //   · BusinessException / 参数校验异常 → HTTP 200 + body.code=400（走成功分支）
    //   · LoginInterceptor 拦截 → HTTP 401 + 手写 JSON（走这个错误分支）
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      // 已经在登录页就不再跳，避免死循环
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    const msg = error.response?.data?.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

export default api
