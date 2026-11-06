import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * 用 hash 路由（#/chat）而不是 history 路由。
 *
 * 原因：打包后前端作为静态资源塞进 Spring Boot 的 static 目录，
 * 如果用 history 模式，刷新 /admin/dashboard 会被 Spring 的
 * 静态资源处理器拦下来报 404，还得额外写转发规则。
 * hash 模式刷新永远只请求 index.html，省掉这类坑。
 */
const routes = [
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '智能客服' }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresStaff: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/admin/DashboardView.vue'),
        meta: { title: '数据看板' }
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/admin/OrdersView.vue'),
        meta: { title: '工单管理' }
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('@/views/admin/SessionsView.vue'),
        meta: { title: '对话记录' }
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/views/admin/KnowledgeView.vue'),
        meta: { title: '知识库' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const role = localStorage.getItem('role')

  if (to.meta.requiresAuth && !token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 普通用户不能进后台
  if (to.meta.requiresStaff && role === 'user') {
    next('/chat')
    return
  }

  next()
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 知了客服` : '知了 · 企业智能客服'
})

export default router
