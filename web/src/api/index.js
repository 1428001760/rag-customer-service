import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一请求封装。
 *
 * 原项目的做法是「重写 window.fetch」，在每个请求里手动塞 token，
 * 还要自己处理 401。这里收敛到一个 axios 实例上：
 *   - token 自动带上
 *   - 401 自动清登录态跳登录页
 *   - 错误统一提示，业务代码里不用到处写 catch
 */
const http = axios.create({
  baseURL: '',
  timeout: 60000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.token = token
  }
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const status = err.response?.status
    if (status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('username')
      ElMessage.warning('登录已过期，请重新登录')
      if (!location.hash.includes('/login')) {
        location.hash = '#/login'
      }
      return Promise.reject(err)
    }
    const msg = err.response?.data?.message || err.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

/* ==================== 对话 ==================== */

export const chatApi = {
  ask: (question, sessionId) =>
    http.get('/chat/askWithSession', { params: { question, sessionId } }),

  history: (sessionId) =>
    http.get('/chat/history', { params: { sessionId } }),

  /** 知识库概况（公开接口，游客也能看） */
  kbSummary: () => http.get('/chat/kb-summary'),

  upload: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post('/chat/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 180000
    })
  }
}

/* ==================== 工单 ==================== */

export const orderApi = {
  list: () => http.get('/admin/order/list'),
  listBySession: (sessionId) =>
    http.get('/admin/order/listBySession', { params: { sessionId } }),
  create: (sessionId, question) =>
    http.post('/admin/order/create', null, { params: { sessionId, question } }),
  claim: (id, handler) =>
    http.post('/admin/order/claim', null, { params: { id, handler } }),
  handle: (id, handler, reply) =>
    http.post('/admin/order/handle', null, { params: { id, handler, reply } }),
  remove: (id) => http.post('/admin/order/delete', null, { params: { id } })
}

/* ==================== 知识库 ==================== */

export const knowledgeApi = {
  list: () => http.get('/admin/knowledge/list'),
  remove: (fileName) =>
    http.post('/admin/knowledge/delete', null, { params: { fileName } }),
  upload: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post('/chat/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 180000
    })
  }
}

/* ==================== 统计 ==================== */

export const statsApi = {
  overview: () => http.get('/admin/stats/overview'),
  trend: () => http.get('/admin/stats/trend'),
  topQuestions: () => http.get('/admin/stats/topQuestions'),
  sessions: () => http.get('/admin/session/list'),
  sessionDetail: (sessionId) =>
    http.get('/admin/session/detail', { params: { sessionId } }),
  logs: () => http.get('/admin/log/list'),
  health: () => http.get('/admin/health')
}

/* ==================== 登录 ==================== */

export const authApi = {
  /** 发送验证码（模拟，后端直接把验证码返回在 msg 里） */
  sendCode: (username) =>
    http.get('/admin/sendCode', { params: { username } }),

  /** 密码登录 */
  loginByPassword: (username, password, fromSession) =>
    http.post('/admin/login', null, { params: { username, password, fromSession } }),

  /** 验证码登录 */
  loginByCode: (username, code, fromSession) =>
    http.post('/admin/login', null, { params: { username, code, fromSession } })
}

export default http
