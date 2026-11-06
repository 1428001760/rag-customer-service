<template>
  <div class="chat-page">
    <!-- ===== 顶栏 ===== -->
    <header class="topbar">
      <div class="brand">
        <div class="logo">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M12 2a3 3 0 0 0-3 3v1a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/>
            <path d="M5 11a7 7 0 0 0 14 0"/>
            <path d="M8 18h8"/><path d="M10 21h4"/>
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-name">知了客服</div>
          <div class="brand-sub">企业知识库 · 智能问答</div>
        </div>
      </div>

      <div class="topbar-right">
        <div class="status-dot" :class="{ online: wsConnected }"></div>
        <span class="status-text">{{ wsConnected ? '实时连接' : 'HTTP 模式' }}</span>

        <button class="x-btn x-btn-ghost x-btn-sm" @click="newSession" title="开启新对话">
          <el-icon><Plus /></el-icon> 新对话
        </button>

        <div class="user-chip" v-if="isLogin">
          <span class="user-avatar">{{ username.charAt(0).toUpperCase() }}</span>
          <span class="user-name">{{ username }}</span>
          <button class="icon-btn" @click="goAdmin" v-if="role !== 'user'" title="管理后台">
            <el-icon><Setting /></el-icon>
          </button>
          <button class="icon-btn" @click="logout" title="退出登录">
            <el-icon><SwitchButton /></el-icon>
          </button>
        </div>
        <button v-else class="x-btn x-btn-primary x-btn-sm" @click="goLogin">
          登录后台
        </button>
      </div>
    </header>

    <!-- ===== 主体 ===== -->
    <main class="main">
      <!-- 左：对话区 -->
      <section class="chat-panel">
        <div class="chat-scroll" ref="scrollRef">
          <!-- 欢迎屏 -->
          <div v-if="messages.length === 0" class="welcome">
            <div class="welcome-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>
              </svg>
            </div>
            <h1 class="welcome-title">有什么可以帮您？</h1>
            <p class="welcome-sub">
              我基于企业知识库回答，答案都来自已上传的文档，不会编造。
            </p>
            <div class="suggestions">
              <button
                v-for="s in suggestions"
                :key="s"
                class="suggestion"
                @click="askSuggestion(s)"
              >
                {{ s }}
              </button>
            </div>
          </div>

          <!-- 消息列表 -->
          <div v-else class="messages">
            <div
              v-for="(m, i) in messages"
              :key="i"
              class="msg-row"
              :class="m.role"
            >
              <div class="msg-avatar" v-if="m.role === 'assistant'">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <circle cx="12" cy="12" r="9"/>
                  <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
                  <circle cx="9" cy="10" r=".6" fill="currentColor"/>
                  <circle cx="15" cy="10" r=".6" fill="currentColor"/>
                </svg>
              </div>

              <div class="msg-body">
                <div class="msg-bubble" :class="{ thinking: m.thinking }">
                  <span v-if="m.thinking" class="thinking-dots">
                    <i></i><i></i><i></i>
                  </span>
                  <span v-else class="msg-text" v-html="renderText(m.content)"></span>
                </div>

                <!-- 溯源信息 -->
                <div v-if="m.sources && m.sources.length" class="msg-sources">
                  <span class="src-label">来源</span>
                  <span v-for="(s, si) in m.sources" :key="si" class="src-chip">
                    {{ s }}
                  </span>
                </div>

                <!-- 操作 -->
                <div v-if="m.role === 'assistant' && !m.thinking" class="msg-actions">
                  <button class="mini-btn" @click="copyText(m.content)">
                    <el-icon><DocumentCopy /></el-icon> 复制
                  </button>
                  <button
                    class="mini-btn"
                    :class="{ active: m.feedback === 'up' }"
                    @click="feedback(m, 'up')"
                  >
                    <el-icon><Star /></el-icon> 有帮助
                  </button>
                  <button
                    class="mini-btn"
                    :class="{ active: m.feedback === 'down' }"
                    @click="feedback(m, 'down')"
                  >
                    没帮助
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="composer">
          <div class="composer-box" :class="{ focused: inputFocused }">
            <textarea
              v-model="question"
              class="composer-input"
              :placeholder="streaming ? '正在回答中…' : '输入您的问题，Enter 发送，Shift+Enter 换行'"
              :disabled="streaming"
              rows="1"
              ref="inputRef"
              @focus="inputFocused = true"
              @blur="inputFocused = false"
              @keydown.enter.exact.prevent="send"
              @input="autoGrow"
            ></textarea>

            <div class="composer-actions">
              <button
                class="x-btn x-btn-ghost x-btn-sm"
                @click="showTransfer = true"
                :disabled="streaming"
                title="转人工客服"
              >
                <el-icon><Service /></el-icon> 转人工
              </button>
              <button
                class="send-btn"
                :disabled="!question.trim() || streaming"
                @click="send"
              >
                <el-icon v-if="!streaming"><Promotion /></el-icon>
                <span v-else class="x-spinner"></span>
              </button>
            </div>
          </div>
          <div class="composer-tip">
            回答由 AI 基于知识库生成，重要信息建议核实
          </div>
        </div>
      </section>

      <!-- 右：侧栏 -->
      <aside class="side">
        <!-- 会话统计 -->
        <div class="x-card side-card">
          <div class="side-card-head">本次会话</div>
          <div class="side-stats">
            <div class="stat">
              <div class="stat-num">{{ stats.rounds }}</div>
              <div class="stat-label">提问</div>
            </div>
            <div class="stat-divider"></div>
            <div class="stat">
              <div class="stat-num">{{ stats.hit }}</div>
              <div class="stat-label">命中</div>
            </div>
            <div class="stat-divider"></div>
            <div class="stat">
              <div class="stat-num">{{ stats.avg }}<span class="stat-unit">s</span></div>
              <div class="stat-label">均耗时</div>
            </div>
          </div>
        </div>

        <!-- 我的工单 -->
        <div class="x-card side-card" v-if="myOrders.length">
          <div class="side-card-head">
            我的工单
            <span class="x-badge x-badge-amber">{{ myOrders.length }}</span>
          </div>
          <div class="order-list">
            <div v-for="o in myOrders" :key="o.id" class="order-item">
              <div class="order-top">
                <span class="order-id">#{{ o.id }}</span>
                <span class="x-badge" :class="orderBadge(o.status)">
                  {{ orderStatus(o.status) }}
                </span>
              </div>
              <div class="order-q">{{ o.question }}</div>
              <div v-if="o.reply" class="order-reply">
                <span class="reply-label">客服回复</span>
                {{ o.reply }}
              </div>
            </div>
          </div>
        </div>

        <!-- 知识库速览 -->
        <div class="x-card side-card">
          <div class="side-card-head">知识库</div>
          <div class="kb-list" v-if="kbDocs.length">
            <div v-for="d in kbDocs" :key="d.fileName" class="kb-item">
              <div class="kb-icon">
                <el-icon><Document /></el-icon>
              </div>
              <div class="kb-info">
                <div class="kb-name">{{ d.fileName }}</div>
                <div class="kb-meta">{{ d.chunkCount }} 个知识块</div>
              </div>
            </div>
          </div>
          <div class="x-empty" v-else style="padding: 28px 12px">
            <div class="x-empty-icon">📚</div>
            知识库还是空的
          </div>
        </div>

        <!-- 快速上传 -->
        <div class="x-card side-card">
          <div class="side-card-head">上传文档</div>
          <div class="upload-zone"
               :class="{ dragging }"
               @dragover.prevent="dragging = true"
               @dragleave.prevent="dragging = false"
               @drop.prevent="onDrop"
               @click="fileInput?.click()">
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-text">
              {{ uploading ? '正在解析向量化…' : '点击或拖拽文件到此处' }}
            </div>
            <div class="upload-hint">TXT / PDF / DOCX / MD</div>
            <input
              ref="fileInput"
              type="file"
              accept=".txt,.pdf,.docx,.md"
              style="display: none"
              @change="onFileChange"
            />
          </div>
        </div>
      </aside>
    </main>

    <!-- ===== 转人工弹窗 ===== -->
    <el-dialog v-model="showTransfer" title="转人工客服" width="460px">
      <div class="dialog-body">
        <p class="dialog-tip">描述一下您遇到的问题，客服会尽快处理并回复您。</p>
        <el-input
          v-model="transferQuestion"
          type="textarea"
          :rows="4"
          placeholder="请描述需要人工处理的问题…"
          maxlength="300"
          show-word-limit
        />
      </div>
      <template #footer>
        <button class="x-btn x-btn-ghost" @click="showTransfer = false">取消</button>
        <button class="x-btn x-btn-primary" @click="submitTransfer" :disabled="!transferQuestion.trim()">
          提交工单
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { chatApi, orderApi, knowledgeApi } from '@/api'

const router = useRouter()

/* ---------- 状态 ---------- */
const question = ref('')
const messages = ref([])
const streaming = ref(false)
const inputFocused = ref(false)
const dragging = ref(false)
const uploading = ref(false)
const scrollRef = ref(null)
const inputRef = ref(null)
const fileInput = ref(null)

const showTransfer = ref(false)
const transferQuestion = ref('')

const wsConnected = ref(false)
const myOrders = ref([])
const kbDocs = ref([])

const username = ref(localStorage.getItem('username') || '访客')
const isLogin = computed(() => !!localStorage.getItem('token'))
const role = computed(() => localStorage.getItem('role') || '')

const stats = reactive({ rounds: 0, hit: 0, durations: [], avg: 0 })

const suggestions = [
  '客服电话是多少',
  '我想退货怎么办',
  '黄金会员有什么优惠',
  '多久能发货',
  '可以开专票吗'
]

/* ---------- 会话 ID ---------- */
function makeSessionId() {
  if (isLogin.value) {
    return 'user_' + username.value
  }
  let sid = sessionStorage.getItem('xw_rag_session')
  if (!sid) {
    sid = 'guest_' + Math.random().toString(36).slice(2, 10)
    sessionStorage.setItem('xw_rag_session', sid)
  }
  return sid
}
const sessionId = ref(makeSessionId())

/* ---------- WebSocket ---------- */
let ws = null
let reconnectTimer = null
let currentAssistantMsg = null

function connectWs() {
  try {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    ws = new WebSocket(`${proto}://${location.host}/ws/chat/${sessionId.value}`)

    ws.onopen = () => {
      wsConnected.value = true
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
    }

    ws.onmessage = (e) => {
      let msg
      try {
        msg = JSON.parse(e.data)
      } catch {
        // 兼容纯文本（人工客服消息）
        messages.value.push({
          role: 'assistant',
          content: e.data,
          sources: []
        })
        scrollToBottom()
        return
      }

      if (msg.type === 'ai_token') {
        if (currentAssistantMsg) {
          if (currentAssistantMsg.thinking) {
            currentAssistantMsg.thinking = false
            currentAssistantMsg.content = ''
          }
          currentAssistantMsg.content += msg.text
          scrollToBottom()
        }
      } else if (msg.type === 'ai_done') {
        finishAssistant()
      } else if (msg.type === 'ai_error') {
        if (currentAssistantMsg) {
          currentAssistantMsg.thinking = false
          currentAssistantMsg.content = msg.text || '抱歉，系统暂时无法回答，请稍后再试'
        }
        finishAssistant()
      } else if (msg.type === 'agent') {
        messages.value.push({
          role: 'assistant',
          content: msg.text,
          sources: [],
          isAgent: true
        })
        scrollToBottom()
      }
    }

    ws.onclose = () => {
      wsConnected.value = false
      // 断线重连
      reconnectTimer = setTimeout(connectWs, 3000)
    }

    ws.onerror = () => {
      try { ws.close() } catch { /* ignore */ }
    }
  } catch {
    wsConnected.value = false
  }
}

/* ---------- 发送 ---------- */
async function send() {
  const q = question.value.trim()
  if (!q || streaming.value) return

  messages.value.push({ role: 'user', content: q, sources: [] })
  question.value = ''
  if (inputRef.value) inputRef.value.style.height = 'auto'
  scrollToBottom()

  streaming.value = true
  const started = Date.now()

  currentAssistantMsg = reactive({
    role: 'assistant',
    content: '',
    thinking: true,
    sources: [],
    feedback: null
  })
  messages.value.push(currentAssistantMsg)
  scrollToBottom()

  // 优先走 WebSocket 流式
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'question', text: q }))
    // 看门狗：8 秒没有任何 token 就切 HTTP
    const guard = setTimeout(() => {
      if (currentAssistantMsg && currentAssistantMsg.thinking) {
        httpFallback(q, started)
      }
    }, 8000)
    currentAssistantMsg._guard = guard
  } else {
    httpFallback(q, started)
  }
}

async function httpFallback(q, started) {
  try {
    const res = await chatApi.ask(q, sessionId.value)
    const answer = res.answer || ''
    if (currentAssistantMsg) {
      currentAssistantMsg.thinking = false
      const { text, sources } = splitSources(answer)
      currentAssistantMsg.content = text
      currentAssistantMsg.sources = sources
    }
    recordStat(started, true)
  } catch {
    if (currentAssistantMsg) {
      currentAssistantMsg.thinking = false
      currentAssistantMsg.content = '系统暂时无法响应，请稍后重试，或点击「转人工」'
    }
  } finally {
    finishAssistant()
  }
}

function finishAssistant() {
  if (currentAssistantMsg?._guard) {
    clearTimeout(currentAssistantMsg._guard)
  }
  if (currentAssistantMsg) {
    // 从回答里抽溯源
    const { text, sources } = splitSources(currentAssistantMsg.content || '')
    if (sources.length && !currentAssistantMsg.sources.length) {
      currentAssistantMsg.content = text
      currentAssistantMsg.sources = sources
    }
  }
  recordStat(null, true)
  currentAssistantMsg = null
  streaming.value = false
  scrollToBottom()
}

/** 把回答尾部的「—— 信息来源：」拆出来，单独渲染成 chip */
function splitSources(text) {
  if (!text) return { text: '', sources: [] }
  const idx = text.indexOf('—— 信息来源')
  if (idx === -1) return { text, sources: [] }

  const body = text.slice(0, idx).trim()
  const tail = text.slice(idx)
  const sources = tail
    .split('\n')
    .map(l => l.replace(/^——\s*信息来源[：:]\s*/, '').replace(/^\d+\.\s*/, '').trim())
    .filter(Boolean)

  return { text: body, sources }
}

function recordStat(started, hit) {
  stats.rounds++
  if (hit) stats.hit++
  if (started) {
    stats.durations.push((Date.now() - started) / 1000)
    const sum = stats.durations.reduce((a, b) => a + b, 0)
    stats.avg = (sum / stats.durations.length).toFixed(1)
  }
}

/* ---------- 工具 ---------- */
function scrollToBottom() {
  nextTick(() => {
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  })
}

function autoGrow(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

/** 极简 Markdown：转义 → 加粗 → 行内代码 → 无序列表 → 换行。先转义，不会引入 XSS */
function renderText(text) {
  if (!text) return ''
  const safe = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.+?)`/g, '<code>$1</code>')

  // 把连续的 "- xxx" 行收成一个 <ul>
  const lines = safe.split('\n')
  const out = []
  let inList = false
  for (const line of lines) {
    const m = line.match(/^\s*[-*]\s+(.*)$/)
    if (m) {
      if (!inList) { out.push('<ul class="md-ul">'); inList = true }
      out.push(`<li>${m[1]}</li>`)
    } else {
      if (inList) { out.push('</ul>'); inList = false }
      out.push(line ? `${line}<br>` : '<br>')
    }
  }
  if (inList) out.push('</ul>')
  return out.join('').replace(/<\/ul><br>/g, '</ul>')
}

function copyText(text) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制')
  }).catch(() => {
    ElMessage.warning('复制失败')
  })
}

function feedback(m, type) {
  m.feedback = m.feedback === type ? null : type
  if (m.feedback) {
    ElMessage.success('感谢反馈')
  }
}

function askSuggestion(s) {
  question.value = s
  send()
}

function newSession() {
  messages.value = []
  stats.rounds = 0
  stats.hit = 0
  stats.durations = []
  stats.avg = 0
  if (!isLogin.value) {
    const sid = 'guest_' + Math.random().toString(36).slice(2, 10)
    sessionStorage.setItem('xw_rag_session', sid)
    sessionId.value = sid
  }
  if (ws) {
    try { ws.close() } catch { /* ignore */ }
  }
  connectWs()
}

/* ---------- 工单 ---------- */
function orderStatus(s) {
  return { 1: '待处理', 2: '处理中', 3: '已完成' }[s] || '未知'
}
function orderBadge(s) {
  return { 1: 'x-badge-amber', 2: 'x-badge-ink', 3: 'x-badge-jade' }[s] || 'x-badge-gray'
}

async function loadOrders() {
  try {
    const res = await orderApi.listBySession(sessionId.value)
    myOrders.value = res.data || []
  } catch { /* ignore */ }
}

async function submitTransfer() {
  const q = transferQuestion.value.trim()
  if (!q) return
  try {
    const res = await orderApi.create(sessionId.value, q)
    showTransfer.value = false
    transferQuestion.value = ''
    messages.value.push({
      role: 'assistant',
      content: `已为您提交工单 #${res.orderId}，客服会尽快处理并回复您。`,
      sources: []
    })
    scrollToBottom()
    await loadOrders()
  } catch { /* ignore */ }
}

/* ---------- 知识库 ---------- */
async function loadKb() {
  try {
    // 用公开接口，游客也能看到知识库里有什么
    const res = await chatApi.kbSummary()
    kbDocs.value = res.data || []
  } catch { /* ignore */ }
}

async function onFileChange(e) {
  const f = e.target.files?.[0]
  if (f) await doUpload(f)
  e.target.value = ''
}

async function onDrop(e) {
  dragging.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) await doUpload(f)
}

async function doUpload(file) {
  uploading.value = true
  try {
    const msg = await knowledgeApi.upload(file)
    ElMessage.success(typeof msg === 'string' ? msg : '上传成功')
    await loadKb()
  } catch {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
}

/* ---------- 导航 ---------- */
function goLogin() {
  router.push('/login')
}
function goAdmin() {
  router.push('/admin/dashboard')
}
function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('role')
  localStorage.removeItem('username')
  location.reload()
}

/* ---------- 生命周期 ---------- */
onMounted(async () => {
  connectWs()
  await loadOrders()
  await loadKb()
})

onUnmounted(() => {
  if (ws) {
    try { ws.close() } catch { /* ignore */ }
  }
  if (reconnectTimer) clearTimeout(reconnectTimer)
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

/* ---------- 顶栏 ---------- */
.topbar {
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

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
}

.logo {
  width: 34px;
  height: 34px;
  border-radius: var(--r-md);
  background: linear-gradient(135deg, var(--ink-700), var(--ink-600));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.logo svg {
  width: 19px;
  height: 19px;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  letter-spacing: .3px;
  line-height: 1.2;
}
.brand-sub {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.3;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ink-300);
  transition: background .3s;
}
.status-dot.online {
  background: var(--jade-500);
  box-shadow: 0 0 0 3px rgba(82, 163, 149, .18);
}
.status-text {
  font-size: 12px;
  color: var(--text-3);
  margin-right: 4px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 4px 4px;
  border-radius: var(--r-pill);
  background: var(--surface-2);
  border: 1px solid var(--line);
}
.user-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--ink-700);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 12.5px;
  color: var(--text-2);
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* ---------- 主体布局 ---------- */
.main {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px 24px 20px;
  overflow: hidden;
  max-width: 1400px;
  width: 100%;
  margin: 0 auto;
}

.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-radius: var(--r-lg);
  border: 1px solid var(--line);
  box-shadow: var(--sh-1);
  overflow: hidden;
  min-width: 0;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

/* ---------- 欢迎屏 ---------- */
.welcome {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 20px;
}

.welcome-icon {
  width: 62px;
  height: 62px;
  border-radius: 20px;
  background: var(--ink-100);
  color: var(--ink-600);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}
.welcome-icon svg {
  width: 30px;
  height: 30px;
}

.welcome-title {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 8px;
  letter-spacing: .3px;
}

.welcome-sub {
  font-size: 13.5px;
  color: var(--text-3);
  max-width: 420px;
  line-height: 1.7;
  margin-bottom: 28px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  justify-content: center;
  max-width: 560px;
}

.suggestion {
  padding: 8px 15px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  border-radius: var(--r-pill);
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  transition: all .16s;
  font-family: inherit;
}
.suggestion:hover {
  border-color: var(--ink-500);
  color: var(--ink-700);
  background: var(--surface-2);
  transform: translateY(-1px);
}
.suggestion:active {
  transform: translateY(0);
}

/* ---------- 消息 ---------- */
.messages {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.msg-row {
  display: flex;
  gap: 10px;
  max-width: 88%;
}
.msg-row.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--ink-100);
  color: var(--ink-600);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}
.msg-avatar svg {
  width: 17px;
  height: 17px;
}

.msg-body {
  min-width: 0;
}

.msg-bubble {
  padding: 11px 15px;
  border-radius: var(--r-md);
  font-size: 14px;
  line-height: 1.72;
  word-break: break-word;
  background: var(--surface-2);
  border: 1px solid var(--line);
  color: var(--text-1);
}

.msg-row.user .msg-bubble {
  background: var(--ink-700);
  border-color: var(--ink-700);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.msg-row.assistant .msg-bubble {
  border-bottom-left-radius: 4px;
}

.msg-bubble.thinking {
  padding: 13px 16px;
}

.thinking-dots {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
.thinking-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-400);
  animation: bounce 1.2s infinite;
}
.thinking-dots i:nth-child(2) { animation-delay: .16s; }
.thinking-dots i:nth-child(3) { animation-delay: .32s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .45; }
  30% { transform: translateY(-4px); opacity: 1; }
}

.msg-text :deep(strong) {
  font-weight: 600;
  color: var(--ink-800);
}
.msg-text :deep(code) {
  font-family: var(--font-mono);
  font-size: 12.5px;
  background: var(--bg-soft);
  padding: 1px 5px;
  border-radius: 4px;
  color: var(--ink-700);
}
.msg-text :deep(.md-ul) {
  margin: 6px 0;
  padding-left: 18px;
  list-style: disc;
}
.msg-text :deep(.md-ul li) {
  margin: 3px 0;
}
.msg-row.user .msg-text :deep(code) {
  background: rgba(255,255,255,.16);
  color: #fff;
}

/* 溯源 */
.msg-sources {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.src-label {
  font-size: 11.5px;
  color: var(--text-4);
}
.src-chip {
  font-size: 11.5px;
  padding: 2px 9px;
  border-radius: var(--r-pill);
  background: var(--jade-100);
  color: var(--jade-700);
}

/* 操作 */
.msg-actions {
  display: flex;
  gap: 4px;
  margin-top: 7px;
  opacity: 0;
  transition: opacity .18s;
}
.msg-row:hover .msg-actions {
  opacity: 1;
}

.mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 8px;
  border: none;
  background: transparent;
  color: var(--text-4);
  font-size: 11.5px;
  border-radius: var(--r-sm);
  cursor: pointer;
  font-family: inherit;
}
.mini-btn:hover {
  background: var(--bg-soft);
  color: var(--text-2);
}
.mini-btn.active {
  color: var(--jade-600);
  background: var(--jade-100);
}

/* ---------- 输入区 ---------- */
.composer {
  border-top: 1px solid var(--line);
  padding: 14px 20px 12px;
  background: var(--surface);
}

.composer-box {
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
  padding: 10px 12px;
  transition: border-color .18s, box-shadow .18s;
  background: var(--surface);
}
.composer-box.focused {
  border-color: var(--ink-500);
  box-shadow: 0 0 0 3px rgba(90, 118, 136, .1);
}

.composer-input {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-1);
  background: transparent;
  max-height: 160px;
  min-height: 24px;
}
.composer-input::placeholder {
  color: var(--text-4);
}
.composer-input:disabled {
  color: var(--text-3);
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.send-btn {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: var(--r-sm);
  background: var(--ink-700);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all .16s;
}
.send-btn:hover:not(:disabled) {
  background: var(--ink-600);
}
.send-btn:active:not(:disabled) {
  transform: scale(.94);
}
.send-btn:disabled {
  background: var(--ink-200);
  cursor: not-allowed;
}

.composer-tip {
  text-align: center;
  font-size: 11px;
  color: var(--text-4);
  margin-top: 8px;
}

/* ---------- 侧栏 ---------- */
.side {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding-right: 2px;
}

.side-card {
  padding: 0;
  overflow: hidden;
  flex-shrink: 0;
}

.side-card-head {
  padding: 13px 16px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 统计 */
.side-stats {
  display: flex;
  align-items: center;
  padding: 16px 8px;
}
.stat {
  flex: 1;
  text-align: center;
}
.stat-num {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-700);
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.stat-unit {
  font-size: 12px;
  color: var(--text-3);
  margin-left: 1px;
}
.stat-label {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 3px;
}
.stat-divider {
  width: 1px;
  height: 26px;
  background: var(--line);
}

/* 工单 */
.order-list {
  max-height: 220px;
  overflow-y: auto;
}
.order-item {
  padding: 11px 16px;
  border-bottom: 1px solid var(--line);
}
.order-item:last-child {
  border-bottom: none;
}
.order-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
}
.order-id {
  font-size: 11.5px;
  color: var(--text-4);
  font-family: var(--font-mono);
}
.order-q {
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.order-reply {
  margin-top: 7px;
  padding: 8px 10px;
  background: var(--jade-100);
  border-radius: var(--r-sm);
  font-size: 12px;
  color: var(--jade-700);
  line-height: 1.6;
}
.reply-label {
  display: block;
  font-size: 10.5px;
  opacity: .7;
  margin-bottom: 2px;
}

/* 知识库 */
.kb-list {
  padding: 6px 0;
}
.kb-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 16px;
}
.kb-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-sm);
  background: var(--bg-soft);
  color: var(--ink-500);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kb-info {
  min-width: 0;
}
.kb-name {
  font-size: 12.5px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-meta {
  font-size: 11px;
  color: var(--text-4);
}

/* 上传 */
.upload-zone {
  margin: 12px;
  border: 1.5px dashed var(--line-strong);
  border-radius: var(--r-md);
  padding: 22px 14px;
  text-align: center;
  cursor: pointer;
  transition: all .18s;
}
.upload-zone:hover,
.upload-zone.dragging {
  border-color: var(--ink-500);
  background: var(--surface-2);
}
.upload-icon {
  font-size: 24px;
  color: var(--ink-400);
  margin-bottom: 8px;
}
.upload-text {
  font-size: 12.5px;
  color: var(--text-2);
  margin-bottom: 3px;
}
.upload-hint {
  font-size: 11px;
  color: var(--text-4);
}

/* 弹窗 */
.dialog-body {
  padding: 4px 0;
}
.dialog-tip {
  font-size: 13px;
  color: var(--text-3);
  margin-bottom: 12px;
  line-height: 1.6;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .side {
    display: none;
  }
  .main {
    padding: 12px;
  }
}
@media (max-width: 720px) {
  .topbar {
    padding: 0 14px;
  }
  .brand-sub,
  .status-text {
    display: none;
  }
  .chat-scroll {
    padding: 16px;
  }
  .msg-row {
    max-width: 94%;
  }
}
</style>
