<template>
  <div class="orders">
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="filters">
        <button
          v-for="f in filters"
          :key="f.value"
          class="filter"
          :class="{ active: status === f.value }"
          @click="status = f.value"
        >
          {{ f.label }}
          <span class="filter-count" v-if="countOf(f.value) > 0">{{ countOf(f.value) }}</span>
        </button>
      </div>

      <div class="toolbar-right">
        <el-input
          v-model="keyword"
          placeholder="搜索工单内容"
          size="default"
          clearable
          style="width: 220px"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <button class="x-btn x-btn-ghost x-btn-sm" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </button>
      </div>
    </div>

    <!-- 表格 -->
    <div class="x-card">
      <table class="x-table" v-if="filtered.length">
        <thead>
          <tr>
            <th style="width: 70px">工单号</th>
            <th>用户问题</th>
            <th style="width: 100px">状态</th>
            <th style="width: 110px">处理人</th>
            <th style="width: 150px">创建时间</th>
            <th style="width: 190px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="o in filtered" :key="o.id">
            <td class="mono">#{{ o.id }}</td>
            <td>
              <div class="q-text">{{ o.question }}</div>
              <div class="reply-text" v-if="o.reply">
                <span class="reply-tag">回复</span>{{ o.reply }}
              </div>
            </td>
            <td>
              <span class="x-badge" :class="badge(o.status)">{{ statusText(o.status) }}</span>
            </td>
            <td class="text-3">{{ o.handler || '—' }}</td>
            <td class="text-3 mono" style="font-size: 12px">{{ fmt(o.createTime) }}</td>
            <td>
              <div class="ops">
                <button
                  v-if="o.status === 1"
                  class="x-btn x-btn-ghost x-btn-sm"
                  @click="claim(o)"
                >
                  认领
                </button>
                <button
                  v-if="o.status === 1 || o.status === 2"
                  class="x-btn x-btn-primary x-btn-sm"
                  @click="openHandle(o)"
                >
                  处理
                </button>
                <button class="x-btn x-btn-danger x-btn-sm" @click="remove(o)">
                  删除
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="x-empty" v-else>
        <div class="x-empty-icon">📋</div>
        {{ loading ? '加载中…' : '没有符合条件的工单' }}
      </div>
    </div>

    <!-- 处理弹窗 -->
    <el-dialog v-model="showHandle" title="处理工单" width="500px">
      <div class="handle-body" v-if="current">
        <div class="handle-section">
          <div class="handle-label">用户问题</div>
          <div class="handle-question">{{ current.question }}</div>
        </div>
        <div class="handle-section">
          <div class="handle-label">会话 ID</div>
          <div class="mono text-3" style="font-size: 12px">{{ current.sessionId }}</div>
        </div>
        <div class="handle-section">
          <div class="handle-label">回复内容</div>
          <el-input
            v-model="replyText"
            type="textarea"
            :rows="4"
            placeholder="输入给用户的回复，提交后会实时推送到用户端…"
            maxlength="500"
            show-word-limit
          />
        </div>
      </div>
      <template #footer>
        <button class="x-btn x-btn-ghost" @click="showHandle = false">取消</button>
        <button
          class="x-btn x-btn-primary"
          :disabled="!replyText.trim()"
          @click="submitHandle"
        >
          提交回复
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '@/api'

const orders = ref([])
const loading = ref(false)
const status = ref(0)
const keyword = ref('')

const showHandle = ref(false)
const current = ref(null)
const replyText = ref('')

const handlerName = localStorage.getItem('username') || 'admin'

const filters = [
  { label: '全部', value: 0 },
  { label: '待处理', value: 1 },
  { label: '处理中', value: 2 },
  { label: '已完成', value: 3 }
]

const filtered = computed(() => {
  let list = orders.value
  if (status.value !== 0) {
    list = list.filter(o => o.status === status.value)
  }
  const kw = keyword.value.trim()
  if (kw) {
    list = list.filter(o =>
      (o.question || '').includes(kw) ||
      (o.reply || '').includes(kw) ||
      String(o.id).includes(kw)
    )
  }
  return list
})

function countOf(v) {
  return v === 0
    ? orders.value.length
    : orders.value.filter(o => o.status === v).length
}

function statusText(s) {
  return { 1: '待处理', 2: '处理中', 3: '已完成' }[s] || '未知'
}
function badge(s) {
  return { 1: 'x-badge-amber', 2: 'x-badge-ink', 3: 'x-badge-jade' }[s] || 'x-badge-gray'
}

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  try {
    const res = await orderApi.list()
    orders.value = (res.data || []).sort((a, b) =>
      String(b.createTime || '').localeCompare(String(a.createTime || ''))
    )
  } catch { /* ignore */ } finally {
    loading.value = false
  }
}

async function claim(o) {
  try {
    const res = await orderApi.claim(o.id, handlerName)
    if (res.code === 200) {
      ElMessage.success('认领成功')
      await load()
    } else {
      ElMessage.error(res.message || '认领失败')
    }
  } catch { /* ignore */ }
}

function openHandle(o) {
  current.value = o
  replyText.value = o.reply || ''
  showHandle.value = true
}

async function submitHandle() {
  if (!current.value || !replyText.value.trim()) return
  try {
    const res = await orderApi.handle(current.value.id, handlerName, replyText.value.trim())
    if (res.code === 200) {
      ElMessage.success('已回复，用户端会实时收到')
      showHandle.value = false
      await load()
    } else {
      ElMessage.error(res.message || '处理失败')
    }
  } catch { /* ignore */ }
}

async function remove(o) {
  try {
    await ElMessageBox.confirm(
      `确定删除工单 #${o.id} 吗？删除后无法恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await orderApi.remove(o.id)
    ElMessage.success('已删除')
    await load()
  } catch { /* 取消或失败 */ }
}

onMounted(load)
</script>

<style scoped>
.orders {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.filters {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: var(--bg-soft);
  border-radius: var(--r-sm);
}

.filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 14px;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 12.5px;
  color: var(--text-3);
  cursor: pointer;
  font-family: inherit;
  transition: all .16s;
}
.filter:hover {
  color: var(--text-1);
}
.filter.active {
  background: var(--surface);
  color: var(--text-1);
  font-weight: 500;
  box-shadow: var(--sh-1);
}

.filter-count {
  font-size: 10.5px;
  padding: 0 5px;
  height: 16px;
  line-height: 16px;
  border-radius: var(--r-pill);
  background: var(--ink-100);
  color: var(--ink-700);
  font-weight: 600;
}
.filter.active .filter-count {
  background: var(--ink-700);
  color: #fff;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.q-text {
  font-size: 13px;
  color: var(--text-1);
  line-height: 1.55;
  max-width: 420px;
}

.reply-text {
  margin-top: 6px;
  font-size: 12px;
  color: var(--jade-700);
  background: var(--jade-100);
  padding: 6px 9px;
  border-radius: 6px;
  line-height: 1.55;
  max-width: 420px;
}

.reply-tag {
  font-size: 10px;
  opacity: .65;
  margin-right: 5px;
}

.ops {
  display: flex;
  gap: 6px;
}

/* 弹窗 */
.handle-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.handle-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.handle-label {
  font-size: 12px;
  color: var(--text-3);
}

.handle-question {
  font-size: 13.5px;
  color: var(--text-1);
  line-height: 1.65;
  background: var(--surface-2);
  padding: 10px 12px;
  border-radius: var(--r-sm);
  border-left: 3px solid var(--amber-500);
}
</style>
