<template>
  <div class="sessions">
    <div class="split">
      <!-- 左：会话列表 -->
      <div class="x-card list-card">
        <div class="x-card-head">
          会话列表
          <span class="head-count">{{ sessions.length }} 个</span>
        </div>

        <div class="search-box">
          <el-input
            v-model="keyword"
            placeholder="搜索会话 ID"
            size="small"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>

        <div class="session-list">
          <button
            v-for="s in filteredSessions"
            :key="s.session_id"
            class="session-item"
            :class="{ active: current?.session_id === s.session_id }"
            @click="select(s)"
          >
            <div class="s-top">
              <span class="s-id mono">{{ shortId(s.session_id) }}</span>
              <span class="s-rounds">{{ s.rounds }} 轮</span>
            </div>
            <div class="s-bottom">
              <span class="s-time">{{ fmt(s.last_time) }}</span>
              <span class="s-hit" v-if="s.hit_count > 0">命中 {{ s.hit_count }}</span>
            </div>
          </button>

          <div class="x-empty" v-if="!filteredSessions.length" style="padding: 36px 16px">
            <div class="x-empty-icon">💬</div>
            没有会话
          </div>
        </div>
      </div>

      <!-- 右：对话详情 -->
      <div class="x-card detail-card">
        <div class="x-card-head">
          <span v-if="current">
            对话详情
            <span class="mono head-sid">{{ current.session_id }}</span>
          </span>
          <span v-else>对话详情</span>
          <span class="head-count" v-if="detail.length">{{ detail.length }} 条记录</span>
        </div>

        <div class="detail-body" v-if="current">
          <div class="detail-scroll">
            <div v-for="d in detail" :key="d.id" class="turn">
              <div class="turn-q">
                <span class="turn-avatar user">U</span>
                <div class="turn-content">
                  <div class="turn-text">{{ d.question }}</div>
                  <div class="turn-meta">
                    <span class="x-badge" :class="d.isHit === 1 ? 'x-badge-jade' : 'x-badge-gray'">
                      {{ d.isHit === 1 ? '命中知识库' : '未命中' }}
                    </span>
                    <span class="meta-item">{{ d.duration }}ms</span>
                    <span class="meta-item">{{ fmt(d.createTime) }}</span>
                  </div>
                </div>
              </div>

              <div class="turn-a">
                <span class="turn-avatar ai">AI</span>
                <div class="turn-content">
                  <div class="turn-text answer" v-html="renderText(stripSources(d.answer))"></div>
                  <div class="turn-sources" v-if="sourcesOf(d.answer).length">
                    <span class="src-label">来源</span>
                    <span v-for="(s, i) in sourcesOf(d.answer)" :key="i" class="src-chip">{{ s }}</span>
                  </div>
                </div>
              </div>
            </div>

            <div class="x-empty" v-if="!detail.length && !loadingDetail">
              <div class="x-empty-icon">📭</div>
              这个会话没有记录
            </div>
          </div>
        </div>

        <div class="x-empty" v-else style="padding: 80px 20px">
          <div class="x-empty-icon">👈</div>
          从左侧选择一个会话查看对话
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { statsApi } from '@/api'

const sessions = ref([])
const detail = ref([])
const current = ref(null)
const keyword = ref('')
const loadingDetail = ref(false)

const filteredSessions = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) return sessions.value
  return sessions.value.filter(s => String(s.session_id).includes(kw))
})

function shortId(id) {
  if (!id) return '—'
  return id.length > 18 ? id.slice(0, 16) + '…' : id
}

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

/** 把回答尾部的来源信息拆出来 */
function stripSources(text) {
  if (!text) return ''
  const idx = text.indexOf('—— 信息来源')
  return idx === -1 ? text : text.slice(0, idx).trim()
}

function sourcesOf(text) {
  if (!text) return []
  const idx = text.indexOf('—— 信息来源')
  if (idx === -1) return []
  return text
    .slice(idx)
    .split('\n')
    .map(l => l.replace(/^——\s*信息来源[：:]\s*/, '').replace(/^\d+\.\s*/, '').trim())
    .filter(Boolean)
}

/** 轻量 Markdown 渲染：只处理加粗、行内代码、换行，先转义避免 XSS */
function renderText(text) {
  if (!text) return ''
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.+?)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>')
}

async function select(s) {
  current.value = s
  loadingDetail.value = true
  try {
    const res = await statsApi.sessionDetail(s.session_id)
    detail.value = res.data || []
  } catch {
    detail.value = []
  } finally {
    loadingDetail.value = false
  }
}

onMounted(async () => {
  try {
    const res = await statsApi.sessions()
    sessions.value = res.data || []
    if (sessions.value.length) {
      await select(sessions.value[0])
    }
  } catch { /* ignore */ }
})
</script>

<style scoped>
.sessions {
  height: 100%;
}

.split {
  display: grid;
  grid-template-columns: 290px 1fr;
  gap: 14px;
  height: calc(100vh - 62px - 48px);
  min-height: 420px;
}

/* ---------- 左列表 ---------- */
.list-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.head-count {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-3);
}

.search-box {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}

.session-list {
  flex: 1;
  overflow-y: auto;
}

.session-item {
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  font-family: inherit;
  transition: background .14s;
  display: block;
}
.session-item:hover {
  background: var(--surface-2);
}
.session-item.active {
  background: var(--ink-100);
  border-left: 3px solid var(--ink-700);
  padding-left: 11px;
}

.s-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
  gap: 8px;
}

.s-id {
  font-size: 12px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.s-rounds {
  font-size: 11px;
  color: var(--text-3);
  flex-shrink: 0;
}

.s-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.s-time {
  font-size: 11px;
  color: var(--text-4);
}

.s-hit {
  font-size: 10.5px;
  color: var(--jade-700);
  background: var(--jade-100);
  padding: 1px 6px;
  border-radius: var(--r-pill);
}

/* ---------- 右详情 ---------- */
.detail-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.head-sid {
  font-size: 11px;
  color: var(--text-4);
  margin-left: 8px;
  font-weight: 400;
}

.detail-body {
  flex: 1;
  overflow: hidden;
  display: flex;
}

.detail-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.turn {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: 18px;
  border-bottom: 1px dashed var(--line);
}
.turn:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.turn-q,
.turn-a {
  display: flex;
  gap: 10px;
}

.turn-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  font-weight: 600;
  flex-shrink: 0;
  margin-top: 1px;
}
.turn-avatar.user {
  background: var(--ink-100);
  color: var(--ink-700);
}
.turn-avatar.ai {
  background: var(--jade-100);
  color: var(--jade-700);
}

.turn-content {
  min-width: 0;
  flex: 1;
}

.turn-text {
  font-size: 13.5px;
  color: var(--text-1);
  line-height: 1.7;
  word-break: break-word;
}
.turn-text.answer {
  color: var(--text-2);
  background: var(--surface-2);
  padding: 10px 13px;
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
}
.turn-text.answer :deep(strong) {
  font-weight: 600;
  color: var(--ink-800);
}
.turn-text.answer :deep(code) {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-soft);
  padding: 1px 5px;
  border-radius: 4px;
  color: var(--ink-700);
}

.turn-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

.meta-item {
  font-size: 11px;
  color: var(--text-4);
}

.turn-sources {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
}

.src-label {
  font-size: 11px;
  color: var(--text-4);
}

.src-chip {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--jade-100);
  color: var(--jade-700);
}

@media (max-width: 900px) {
  .split {
    grid-template-columns: 1fr;
    height: auto;
  }
  .list-card {
    max-height: 300px;
  }
  .detail-card {
    min-height: 400px;
  }
}
</style>
