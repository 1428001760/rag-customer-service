<template>
  <div class="dashboard">
    <!-- 指标卡片 -->
    <div class="stat-grid">
      <div class="stat-card" v-for="c in cards" :key="c.key">
        <div class="stat-top">
          <span class="stat-label">{{ c.label }}</span>
          <span class="stat-icon" :style="{ background: c.bg, color: c.color }">
            <el-icon><component :is="c.icon" /></el-icon>
          </span>
        </div>
        <div class="stat-value">
          {{ c.value }}<span class="stat-suffix" v-if="c.suffix">{{ c.suffix }}</span>
        </div>
        <div class="stat-foot" v-if="c.foot">{{ c.foot }}</div>
      </div>
    </div>

    <!-- 趋势图 + 工单分布 -->
    <div class="row-2">
      <div class="x-card">
        <div class="x-card-head">
          近 7 天对话量
          <span class="head-legend">
            <i class="dot dot-ink"></i>总对话
            <i class="dot dot-jade"></i>命中知识库
          </span>
        </div>
        <div class="chart-wrap">
          <div ref="trendRef" class="chart"></div>
        </div>
      </div>

      <div class="x-card">
        <div class="x-card-head">工单状态分布</div>
        <div class="chart-wrap">
          <div ref="pieRef" class="chart"></div>
        </div>
      </div>
    </div>

    <!-- 高频问题 -->
    <div class="x-card">
      <div class="x-card-head">
        高频提问 Top 10
        <span class="head-hint">按出现次数排序，可用于补充知识库</span>
      </div>
      <div class="top-list" v-if="topQuestions.length">
        <div
          v-for="(q, i) in topQuestions"
          :key="i"
          class="top-item"
        >
          <span class="rank" :class="rankClass(i)">{{ i + 1 }}</span>
          <span class="top-q">{{ q.question }}</span>
          <span class="top-bar">
            <i :style="{ width: barWidth(q.cnt) }"></i>
          </span>
          <span class="top-cnt">{{ q.cnt }} 次</span>
        </div>
      </div>
      <div class="x-empty" v-else>
        <div class="x-empty-icon">📊</div>
        还没有对话数据
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { statsApi } from '@/api'

const trendRef = ref(null)
const pieRef = ref(null)
const overview = ref({})
const trend = ref({ days: [], totals: [], hits: [] })
const topQuestions = ref([])

let trendChart = null
let pieChart = null

const cards = computed(() => {
  const o = overview.value
  return [
    {
      key: 'total',
      label: '总对话数',
      value: o.totalChat ?? '—',
      icon: 'ChatDotRound',
      bg: 'var(--ink-100)',
      color: 'var(--ink-700)',
      foot: `今日新增 ${o.todayChat ?? 0} 条`
    },
    {
      key: 'hit',
      label: '知识库命中率',
      value: o.hitRate ?? '—',
      suffix: '%',
      icon: 'Aim',
      bg: 'var(--jade-100)',
      color: 'var(--jade-700)',
      foot: `命中 ${o.hitChat ?? 0} / 总计 ${o.totalChat ?? 0}`
    },
    {
      key: 'duration',
      label: '平均响应耗时',
      value: o.avgDuration ?? '—',
      suffix: 'ms',
      icon: 'Timer',
      bg: 'var(--amber-100)',
      color: 'var(--amber-600)',
      foot: '从提问到回答完成'
    },
    {
      key: 'order',
      label: '待处理工单',
      value: o.pendingOrder ?? '—',
      icon: 'Tickets',
      bg: 'var(--rose-100)',
      color: 'var(--rose-600)',
      foot: `处理中 ${o.doingOrder ?? 0} · 已完成 ${o.doneOrder ?? 0}`
    }
  ]
})

const maxCnt = computed(() => Math.max(1, ...topQuestions.value.map(q => q.cnt)))

function barWidth(cnt) {
  return Math.round((cnt / maxCnt.value) * 100) + '%'
}

function rankClass(i) {
  if (i === 0) return 'rank-1'
  if (i === 1) return 'rank-2'
  if (i === 2) return 'rank-3'
  return ''
}

function renderTrend() {
  if (!trendRef.value) return
  if (!trendChart) trendChart = echarts.init(trendRef.value)

  const d = trend.value
  trendChart.setOption({
    grid: { left: 40, right: 20, top: 24, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#fff',
      borderColor: '#E4E7E5',
      textStyle: { color: '#16232B', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: '#D4DDE3' } }
    },
    xAxis: {
      type: 'category',
      data: d.days.map(x => x.slice(5)),
      axisLine: { lineStyle: { color: '#E4E7E5' } },
      axisTick: { show: false },
      axisLabel: { color: '#7B8F9B', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#F0F2F1' } },
      axisLabel: { color: '#A8B8C2', fontSize: 11 }
    },
    series: [
      {
        name: '总对话',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: d.totals,
        itemStyle: { color: '#2F4858' },
        lineStyle: { width: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(47,72,88,.16)' },
            { offset: 1, color: 'rgba(47,72,88,0)' }
          ])
        }
      },
      {
        name: '命中',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: d.hits,
        itemStyle: { color: '#3D8B7D' },
        lineStyle: { width: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(61,139,125,.14)' },
            { offset: 1, color: 'rgba(61,139,125,0)' }
          ])
        }
      }
    ]
  })
}

function renderPie() {
  if (!pieRef.value) return
  if (!pieChart) pieChart = echarts.init(pieRef.value)

  const o = overview.value
  pieChart.setOption({
    tooltip: {
      trigger: 'item',
      backgroundColor: '#fff',
      borderColor: '#E4E7E5',
      textStyle: { color: '#16232B', fontSize: 12 }
    },
    legend: {
      bottom: 6,
      icon: 'circle',
      itemWidth: 7,
      itemHeight: 7,
      textStyle: { color: '#7B8F9B', fontSize: 11.5 }
    },
    series: [
      {
        type: 'pie',
        radius: ['46%', '66%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: [
          { value: o.pendingOrder ?? 0, name: '待处理', itemStyle: { color: '#D98E5A' } },
          { value: o.doingOrder ?? 0, name: '处理中', itemStyle: { color: '#5A7688' } },
          { value: o.doneOrder ?? 0, name: '已完成', itemStyle: { color: '#52A395' } }
        ]
      }
    ]
  })
}

function resize() {
  trendChart?.resize()
  pieChart?.resize()
}

onMounted(async () => {
  try {
    const [ov, tr, tq] = await Promise.all([
      statsApi.overview(),
      statsApi.trend(),
      statsApi.topQuestions()
    ])
    overview.value = ov.data || {}
    trend.value = tr.data || { days: [], totals: [], hits: [] }
    topQuestions.value = tq.data || []
  } catch { /* 拦截器已提示 */ }

  await nextTick()
  renderTrend()
  renderPie()
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  window.removeEventListener('resize', resize)
  trendChart?.dispose()
  pieChart?.dispose()
})
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- 指标卡 ---------- */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.stat-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  padding: 16px 18px;
  box-shadow: var(--sh-1);
  transition: box-shadow .18s, transform .18s;
}
.stat-card:hover {
  box-shadow: var(--sh-2);
  transform: translateY(-1px);
}

.stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.stat-label {
  font-size: 12.5px;
  color: var(--text-3);
}

.stat-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  color: var(--text-1);
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
  letter-spacing: -.5px;
}

.stat-suffix {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-3);
  margin-left: 3px;
  letter-spacing: 0;
}

.stat-foot {
  font-size: 11.5px;
  color: var(--text-4);
  margin-top: 6px;
}

/* ---------- 图表 ---------- */
.row-2 {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 14px;
}

.chart-wrap {
  padding: 12px 8px 8px;
}

.chart {
  width: 100%;
  height: 250px;
}

.head-legend {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-3);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
  margin-left: 8px;
}
.dot-ink { background: var(--ink-700); }
.dot-jade { background: var(--jade-600); }

.head-hint {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-4);
}

/* ---------- 高频问题 ---------- */
.top-list {
  padding: 6px 20px 14px;
}

.top-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--line);
}
.top-item:last-child {
  border-bottom: none;
}

.rank {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: var(--bg-soft);
  color: var(--text-3);
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.rank-1 { background: #F4E3D4; color: #B87444; }
.rank-2 { background: #E3E7EA; color: #5A7688; }
.rank-3 { background: #E4F0ED; color: var(--jade-700); }

.top-q {
  flex: 1;
  font-size: 13px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.top-bar {
  width: 110px;
  height: 5px;
  background: var(--bg-soft);
  border-radius: var(--r-pill);
  overflow: hidden;
  flex-shrink: 0;
}
.top-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--ink-600), var(--jade-600));
  border-radius: var(--r-pill);
  transition: width .4s ease;
}

.top-cnt {
  width: 48px;
  text-align: right;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1200px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .row-2 {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
