<template>
  <div class="knowledge">
    <!-- 上传区 -->
    <div class="x-card upload-card">
      <div class="x-card-head">
        上传知识库文档
        <span class="head-hint">同名文档自动覆盖，不会重复堆积</span>
      </div>
      <div class="upload-body">
        <div
          class="drop-zone"
          :class="{ dragging, uploading }"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
          @click="fileInput?.click()"
        >
          <div class="drop-icon">
            <el-icon v-if="!uploading"><UploadFilled /></el-icon>
            <span v-else class="x-spinner" style="width: 24px; height: 24px"></span>
          </div>
          <div class="drop-title">
            {{ uploading ? '正在解析并向量化…' : '点击选择，或把文件拖到这里' }}
          </div>
          <div class="drop-sub">
            支持 TXT / PDF / DOCX / Markdown，单文件建议不超过 10MB
          </div>
          <input
            ref="fileInput"
            type="file"
            accept=".txt,.pdf,.docx,.md"
            style="display: none"
            @change="onFileChange"
          />
        </div>

        <!-- 切块说明 -->
        <div class="split-info">
          <div class="split-title">文档如何切块</div>
          <div class="split-steps">
            <div class="step">
              <span class="step-num">1</span>
              <div>
                <div class="step-name">按标题切章节</div>
                <div class="step-desc">识别「一、二、三」或 Markdown 标题</div>
              </div>
            </div>
            <div class="step">
              <span class="step-num">2</span>
              <div>
                <div class="step-name">章节内按段落切</div>
                <div class="step-desc">一个语义单元 = 一个知识块</div>
              </div>
            </div>
            <div class="step">
              <span class="step-num">3</span>
              <div>
                <div class="step-name">超长段落按句子切</div>
                <div class="step-desc">保证句子不被拦腰截断</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 文档列表 -->
    <div class="x-card">
      <div class="x-card-head">
        已有文档
        <div class="head-right">
          <span class="head-count">{{ docs.length }} 个文档 · {{ totalChunks }} 个知识块</span>
          <button class="x-btn x-btn-ghost x-btn-sm" @click="load">
            <el-icon><Refresh /></el-icon> 刷新
          </button>
        </div>
      </div>

      <div class="doc-list" v-if="docs.length">
        <div v-for="d in docs" :key="d.fileName" class="doc-item">
          <div class="doc-icon">
            <el-icon><Document /></el-icon>
          </div>

          <div class="doc-main">
            <div class="doc-name">{{ d.fileName }}</div>
            <div class="doc-meta">
              <span class="x-badge x-badge-ink">{{ d.chunkCount }} 个知识块</span>
              <span class="doc-headings" v-if="d.headings?.length">
                章节：{{ d.headings.slice(0, 4).join(' / ') }}
                <span v-if="d.headings.length > 4">等 {{ d.headings.length }} 个</span>
              </span>
            </div>
          </div>

          <button class="x-btn x-btn-danger x-btn-sm" @click="remove(d)">
            <el-icon><Delete /></el-icon> 删除
          </button>
        </div>
      </div>

      <div class="x-empty" v-else>
        <div class="x-empty-icon">📚</div>
        {{ loading ? '加载中…' : '还没有上传任何文档' }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi } from '@/api'

const docs = ref([])
const loading = ref(false)
const uploading = ref(false)
const dragging = ref(false)
const fileInput = ref(null)

const totalChunks = computed(() =>
  docs.value.reduce((sum, d) => sum + (d.chunkCount || 0), 0)
)

async function load() {
  loading.value = true
  try {
    const res = await knowledgeApi.list()
    docs.value = res.data || []
  } catch { /* ignore */ } finally {
    loading.value = false
  }
}

async function onFileChange(e) {
  const f = e.target.files?.[0]
  if (f) await upload(f)
  e.target.value = ''
}

async function onDrop(e) {
  dragging.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) await upload(f)
}

async function upload(file) {
  uploading.value = true
  try {
    const msg = await knowledgeApi.upload(file)
    ElMessage.success(typeof msg === 'string' ? msg : '上传成功')
    await load()
  } catch {
    ElMessage.error('上传失败，请检查文件格式')
  } finally {
    uploading.value = false
  }
}

async function remove(d) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${d.fileName}」吗？它的 ${d.chunkCount} 个知识块会一起删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await knowledgeApi.remove(d.fileName)
    ElMessage.success('已删除')
    await load()
  } catch { /* 取消 */ }
}

onMounted(load)
</script>

<style scoped>
.knowledge {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head-hint {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-4);
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.head-count {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-3);
}

/* ---------- 上传 ---------- */
.upload-body {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 20px;
  padding: 20px;
}

.drop-zone {
  border: 1.5px dashed var(--line-strong);
  border-radius: var(--r-md);
  padding: 34px 20px;
  text-align: center;
  cursor: pointer;
  transition: all .18s;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 190px;
}
.drop-zone:hover {
  border-color: var(--ink-500);
  background: var(--surface-2);
}
.drop-zone.dragging {
  border-color: var(--jade-600);
  background: var(--jade-100);
}
.drop-zone.uploading {
  cursor: wait;
  opacity: .8;
}

.drop-icon {
  color: var(--ink-400);
  font-size: 30px;
  margin-bottom: 12px;
}

.drop-title {
  font-size: 13.5px;
  color: var(--text-1);
  font-weight: 500;
  margin-bottom: 5px;
}

.drop-sub {
  font-size: 11.5px;
  color: var(--text-4);
}

/* 切块说明 */
.split-info {
  background: var(--surface-2);
  border-radius: var(--r-md);
  padding: 16px 18px;
  border: 1px solid var(--line);
}

.split-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 14px;
}

.split-steps {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.step {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.step-num {
  width: 18px;
  height: 18px;
  border-radius: 5px;
  background: var(--ink-700);
  color: #fff;
  font-size: 10.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}

.step-name {
  font-size: 12.5px;
  color: var(--text-1);
  line-height: 1.4;
}

.step-desc {
  font-size: 11px;
  color: var(--text-4);
  line-height: 1.5;
  margin-top: 1px;
}

/* ---------- 文档列表 ---------- */
.doc-list {
  padding: 4px 0;
}

.doc-item {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
}
.doc-item:last-child {
  border-bottom: none;
}

.doc-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--r-sm);
  background: var(--ink-100);
  color: var(--ink-600);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex-shrink: 0;
}

.doc-main {
  flex: 1;
  min-width: 0;
}

.doc-name {
  font-size: 13.5px;
  color: var(--text-1);
  font-weight: 500;
  margin-bottom: 5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.doc-headings {
  font-size: 11.5px;
  color: var(--text-4);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 900px) {
  .upload-body {
    grid-template-columns: 1fr;
  }
}
</style>
