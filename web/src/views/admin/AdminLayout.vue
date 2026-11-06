<template>
  <div class="admin-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-brand">
        <div class="logo">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M12 2a3 3 0 0 0-3 3v1a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/>
            <path d="M5 11a7 7 0 0 0 14 0"/>
            <path d="M8 18h8"/><path d="M10 21h4"/>
          </svg>
        </div>
        <transition name="fade">
          <div class="brand-text" v-if="!collapsed">
            <div class="name">知了客服</div>
            <div class="sub">管理后台</div>
          </div>
        </transition>
      </div>

      <nav class="nav">
        <router-link
          v-for="item in visibleMenus"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          active-class="active"
        >
          <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
          <span v-if="!collapsed" class="nav-label">{{ item.title }}</span>
          <span v-if="!collapsed && item.badge" class="nav-badge">{{ item.badge }}</span>
        </router-link>
      </nav>

      <div class="sidebar-foot">
        <button class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? 'ArrowRight' : 'ArrowLeft'" /></el-icon>
          <span v-if="!collapsed">收起</span>
        </button>
      </div>
    </aside>

    <!-- 右侧内容 -->
    <div class="content-wrap">
      <header class="content-head">
        <div class="head-left">
          <h1 class="page-title">{{ currentTitle }}</h1>
          <span class="page-sub" v-if="subtitle">{{ subtitle }}</span>
        </div>

        <div class="head-right">
          <router-link to="/chat" class="x-btn x-btn-ghost x-btn-sm">
            <el-icon><ChatDotRound /></el-icon> 回到对话
          </router-link>

          <div class="user-chip">
            <span class="avatar">{{ (username || 'A').charAt(0).toUpperCase() }}</span>
            <div class="user-meta">
              <div class="uname">{{ username }}</div>
              <div class="urole">{{ roleLabel }}</div>
            </div>
            <button class="icon-btn" @click="logout" title="退出登录">
              <el-icon><SwitchButton /></el-icon>
            </button>
          </div>
        </div>
      </header>

      <main class="content-body">
        <router-view v-slot="{ Component }">
          <transition name="fade-up" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const username = ref(localStorage.getItem('username') || '管理员')
const role = ref(localStorage.getItem('role') || 'admin')

const roleLabel = computed(() => ({
  admin: '系统管理员',
  kb_admin: '知识库管理员',
  cs_staff: '客服坐席'
}[role.value] || '管理员'))

const allMenus = [
  { path: '/admin/dashboard', title: '数据看板', icon: 'DataAnalysis', roles: ['admin', 'kb_admin', 'cs_staff'] },
  { path: '/admin/orders', title: '工单管理', icon: 'Tickets', roles: ['admin', 'cs_staff'] },
  { path: '/admin/sessions', title: '对话记录', icon: 'ChatLineSquare', roles: ['admin', 'cs_staff'] },
  { path: '/admin/knowledge', title: '知识库', icon: 'Collection', roles: ['admin', 'kb_admin'] }
]

const visibleMenus = computed(() =>
  allMenus.filter(m => m.roles.includes(role.value))
)

const currentTitle = computed(() => route.meta.title || '管理后台')

const subtitle = computed(() => ({
  dashboard: '实时反映系统运行状况',
  orders: '处理用户转人工的工单',
  sessions: '查看用户与 AI 的完整对话',
  knowledge: '管理向量化知识库文档'
}[route.name] || ''))

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('role')
  localStorage.removeItem('username')
  router.push('/login')
}
</script>

<style scoped>
.admin-layout {
  height: 100%;
  display: flex;
  background: var(--bg);
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  width: 216px;
  flex-shrink: 0;
  background: var(--ink-800);
  display: flex;
  flex-direction: column;
  transition: width .22s ease;
  overflow: hidden;
}
.sidebar.collapsed {
  width: 64px;
}

.sidebar-brand {
  height: 62px;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 0 18px;
  border-bottom: 1px solid rgba(255, 255, 255, .07);
  flex-shrink: 0;
}

.logo {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: rgba(255, 255, 255, .1);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.logo svg {
  width: 17px;
  height: 17px;
  color: #fff;
}

.brand-text {
  white-space: nowrap;
}
.brand-text .name {
  font-size: 13.5px;
  font-weight: 600;
  color: #fff;
  line-height: 1.2;
}
.brand-text .sub {
  font-size: 10.5px;
  color: rgba(255, 255, 255, .4);
  line-height: 1.3;
}

.nav {
  flex: 1;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  height: 40px;
  padding: 0 11px;
  border-radius: var(--r-sm);
  color: rgba(255, 255, 255, .62);
  text-decoration: none;
  font-size: 13px;
  transition: all .16s;
  white-space: nowrap;
  position: relative;
}
.nav-item:hover {
  background: rgba(255, 255, 255, .07);
  color: rgba(255, 255, 255, .9);
}
.nav-item.active {
  background: rgba(255, 255, 255, .11);
  color: #fff;
  font-weight: 500;
}
.nav-item.active::before {
  content: "";
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: var(--jade-500);
}

.nav-icon {
  font-size: 17px;
  flex-shrink: 0;
}

.nav-label {
  flex: 1;
}

.nav-badge {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: var(--amber-500);
  color: #fff;
  font-weight: 600;
}

.sidebar-foot {
  padding: 10px;
  border-top: 1px solid rgba(255, 255, 255, .07);
  flex-shrink: 0;
}

.collapse-btn {
  width: 100%;
  height: 34px;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, .45);
  border-radius: var(--r-sm);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  font-family: inherit;
}
.collapse-btn:hover {
  background: rgba(255, 255, 255, .07);
  color: rgba(255, 255, 255, .8);
}

/* ---------- 内容区 ---------- */
.content-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.content-head {
  height: 62px;
  flex-shrink: 0;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  gap: 16px;
}

.head-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  white-space: nowrap;
}

.page-sub {
  font-size: 12.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 6px 4px 4px;
  border-radius: var(--r-pill);
  background: var(--surface-2);
  border: 1px solid var(--line);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--ink-700);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-meta {
  line-height: 1.25;
}
.uname {
  font-size: 12.5px;
  color: var(--text-1);
  font-weight: 500;
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.urole {
  font-size: 10.5px;
  color: var(--text-3);
}

.icon-btn {
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  color: var(--text-3);
  border-radius: var(--r-sm);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:hover {
  background: var(--bg-soft);
  color: var(--text-1);
}

.content-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px 28px;
}

/* ---------- 过渡 ---------- */
.fade-enter-active,
.fade-leave-active {
  transition: opacity .18s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.fade-up-enter-active,
.fade-up-leave-active {
  transition: opacity .2s ease, transform .2s ease;
}
.fade-up-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.fade-up-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
