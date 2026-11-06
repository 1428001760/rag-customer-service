<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <div class="brand-side">
      <div class="brand-content">
        <div class="brand-mark">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path d="M12 2a3 3 0 0 0-3 3v1a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/>
            <path d="M5 11a7 7 0 0 0 14 0"/>
            <path d="M8 18h8"/><path d="M10 21h4"/>
          </svg>
        </div>
        <h1 class="brand-title">知了客服</h1>
        <p class="brand-desc">
          基于检索增强生成的企业知识库客服系统。<br>
          答案全部来自已上传文档，可溯源、不编造。
        </p>

        <div class="feature-list">
          <div class="feature">
            <div class="feature-dot"></div>
            <span>多轮对话，自动补全指代问题</span>
          </div>
          <div class="feature">
            <div class="feature-dot"></div>
            <span>命中知识库时标注来源章节</span>
          </div>
          <div class="feature">
            <div class="feature-dot"></div>
            <span>未命中直接拒答，不瞎编</span>
          </div>
          <div class="feature">
            <div class="feature-dot"></div>
            <span>支持转人工，工单实时推送</span>
          </div>
        </div>
      </div>

      <div class="brand-footer">
        RAG · Spring Boot 2.7 · LangChain4j · Vue 3
      </div>
    </div>

    <!-- 右侧登录表单 -->
    <div class="form-side">
      <div class="form-card">
        <button class="back-btn" @click="router.push('/chat')">
          <el-icon><ArrowLeft /></el-icon> 返回对话
        </button>

        <h2 class="form-title">登录管理后台</h2>
        <p class="form-sub">客服坐席 / 知识库管理员 / 系统管理员</p>

        <!-- 切换方式 -->
        <div class="tabs">
          <button
            class="tab"
            :class="{ active: mode === 'password' }"
            @click="mode = 'password'"
          >
            密码登录
          </button>
          <button
            class="tab"
            :class="{ active: mode === 'code' }"
            @click="mode = 'code'"
          >
            验证码登录
          </button>
        </div>

        <div class="fields">
          <div class="field">
            <label class="label">账号</label>
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              size="large"
              @keyup.enter="submit"
            >
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </div>

          <div class="field" v-if="mode === 'password'">
            <label class="label">密码</label>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              size="large"
              show-password
              @keyup.enter="submit"
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </div>

          <div class="field" v-else>
            <label class="label">验证码</label>
            <div class="code-row">
              <el-input
                v-model="form.code"
                placeholder="6 位验证码"
                size="large"
                maxlength="6"
                @keyup.enter="submit"
              >
                <template #prefix>
                  <el-icon><Message /></el-icon>
                </template>
              </el-input>
              <button
                class="x-btn x-btn-ghost code-btn"
                :disabled="countdown > 0 || !form.username.trim()"
                @click="sendCode"
              >
                {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
              </button>
            </div>
          </div>
        </div>

        <button
          class="x-btn x-btn-primary submit-btn"
          :disabled="loading || !canSubmit"
          @click="submit"
        >
          <span v-if="loading" class="x-spinner"></span>
          <span v-else>登录</span>
        </button>

        <div class="demo-hint" v-if="demoHint">
          <el-icon><InfoFilled /></el-icon>
          <span>{{ demoHint }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api'

const router = useRouter()
const route = useRoute()

const mode = ref('password')
const loading = ref(false)
const countdown = ref(0)
const demoHint = ref('')
let timer = null

const form = reactive({
  username: '',
  password: '',
  code: ''
})

const canSubmit = computed(() => {
  if (!form.username.trim()) return false
  return mode.value === 'password'
    ? !!form.password
    : form.code.length === 6
})

async function sendCode() {
  if (!form.username.trim()) return
  try {
    const res = await authApi.sendCode(form.username.trim())
    // 后端是模拟发送，验证码直接返回在 msg 里，方便演示
    demoHint.value = res.msg || ''
    ElMessage.success('验证码已发送')
    countdown.value = 60
    timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
  } catch { /* 拦截器已提示 */ }
}

async function submit() {
  if (!canSubmit.value || loading.value) return
  loading.value = true
  try {
    const fromSession = sessionStorage.getItem('xw_rag_session') || ''
    const res = mode.value === 'password'
      ? await authApi.loginByPassword(form.username.trim(), form.password, fromSession)
      : await authApi.loginByCode(form.username.trim(), form.code, fromSession)

    if (res.code !== 200) {
      ElMessage.error(res.message || '登录失败')
      return
    }

    localStorage.setItem('token', res.token)
    localStorage.setItem('role', res.role)
    localStorage.setItem('username', res.username)
    ElMessage.success('登录成功')

    const redirect = route.query.redirect
    if (res.role === 'user') {
      router.push('/chat')
    } else {
      router.push(redirect || '/admin/dashboard')
    }
  } catch { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  background: var(--bg);
}

/* ---------- 左侧品牌 ---------- */
.brand-side {
  width: 46%;
  max-width: 560px;
  background: linear-gradient(150deg, var(--ink-800) 0%, var(--ink-700) 55%, #35505F 100%);
  color: #fff;
  padding: 56px 52px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
  overflow: hidden;
}

/* 背景装饰：两个柔光圆，不用图片 */
.brand-side::before,
.brand-side::after {
  content: "";
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.brand-side::before {
  width: 380px;
  height: 380px;
  right: -140px;
  top: -120px;
  background: radial-gradient(circle, rgba(82,163,149,.22), transparent 68%);
}
.brand-side::after {
  width: 300px;
  height: 300px;
  left: -110px;
  bottom: -90px;
  background: radial-gradient(circle, rgba(217,142,90,.16), transparent 70%);
}

.brand-content {
  position: relative;
  z-index: 1;
}

.brand-mark {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: rgba(255, 255, 255, .1);
  border: 1px solid rgba(255, 255, 255, .16);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 26px;
}
.brand-mark svg {
  width: 26px;
  height: 26px;
  color: #fff;
}

.brand-title {
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 1px;
  margin-bottom: 14px;
}

.brand-desc {
  font-size: 13.5px;
  line-height: 1.9;
  color: rgba(255, 255, 255, .62);
  margin-bottom: 38px;
}

.feature-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.feature {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: rgba(255, 255, 255, .78);
}
.feature-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--jade-500);
  flex-shrink: 0;
  box-shadow: 0 0 0 3px rgba(82, 163, 149, .18);
}

.brand-footer {
  position: relative;
  z-index: 1;
  font-size: 11.5px;
  color: rgba(255, 255, 255, .32);
  letter-spacing: .4px;
}

/* ---------- 右侧表单 ---------- */
.form-side {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-card {
  width: 100%;
  max-width: 380px;
  position: relative;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
  margin-bottom: 28px;
  font-family: inherit;
}
.back-btn:hover {
  color: var(--text-1);
}

.form-title {
  font-size: 23px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 7px;
  letter-spacing: .3px;
}

.form-sub {
  font-size: 13px;
  color: var(--text-3);
  margin-bottom: 26px;
}

.tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: var(--bg-soft);
  border-radius: var(--r-sm);
  margin-bottom: 22px;
}
.tab {
  flex: 1;
  height: 32px;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-3);
  cursor: pointer;
  font-family: inherit;
  transition: all .16s;
}
.tab.active {
  background: var(--surface);
  color: var(--text-1);
  font-weight: 500;
  box-shadow: var(--sh-1);
}

.fields {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.label {
  font-size: 12.5px;
  color: var(--text-2);
  font-weight: 500;
}

.code-row {
  display: flex;
  gap: 9px;
}
.code-row :deep(.el-input) {
  flex: 1;
}
.code-btn {
  width: 108px;
  height: 40px;
  flex-shrink: 0;
  font-size: 12.5px;
}

.submit-btn {
  width: 100%;
  height: 42px;
  margin-top: 24px;
  font-size: 14.5px;
  letter-spacing: 1px;
}

.demo-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 16px;
  padding: 10px 12px;
  background: var(--amber-100);
  border-radius: var(--r-sm);
  font-size: 12px;
  color: var(--amber-600);
  line-height: 1.6;
  word-break: break-all;
}
.demo-hint :deep(.el-icon) {
  margin-top: 2px;
  flex-shrink: 0;
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .brand-side {
    display: none;
  }
}
</style>
