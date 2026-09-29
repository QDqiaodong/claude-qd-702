<template>
  <div class="shell">
    <!-- 顶部：品牌 + 横向面包屑（当前委托单 / 器具）+ 全站计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">⚖</span>
        <div>
          计量检定所业务平台
          <div class="brand-sub">法定计量技术机构 · 器具台账 · 检定流转 · 证书管理</div>
        </div>
      </div>

      <div class="crumb">
        <div class="crumb-slot">
          <span class="crumb-label">当前委托单</span>
          <el-select
            v-model="orderId"
            size="small"
            filterable
            placeholder="选择委托单"
            style="width: 210px"
            @change="selectOrder"
          >
            <el-option v-for="o in flatOrders" :key="o.id" :label="o.orderNo" :value="o.id">
              <span>{{ o.orderNo }}</span>
              <span style="float: right; font-size: 12px; opacity: 0.65">{{ o.statusName }}</span>
            </el-option>
          </el-select>
        </div>
        <span class="crumb-arrow">›</span>
        <div class="crumb-slot">
          <span class="crumb-label">器具</span>
          <span class="crumb-value mono">{{ currentOrder.instNo || '未选择' }}</span>
        </div>
        <span class="crumb-arrow">›</span>
        <div class="crumb-slot">
          <span class="crumb-label">受检器具</span>
          <span class="crumb-value">{{ currentOrder.instrumentName || '—' }}</span>
        </div>
        <span class="crumb-arrow">›</span>
        <div class="crumb-slot">
          <span class="crumb-label">委托单位</span>
          <span class="crumb-value">{{ currentOrder.clientName || '—' }}</span>
        </div>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.instrumentTotal || 0 }}</b><span>在册器具</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.instrumentOverdue || 0) > 0 }">
          <b>{{ overview.instrumentOverdue || 0 }}</b><span>已超期</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.instrumentDueSoon || 0) > 0 }">
          <b>{{ overview.instrumentDueSoon || 0 }}</b><span>30 天内到期</span>
        </div>
        <div class="stat-chip"><b>{{ overview.orderOpen || 0 }}</b><span>在流转委托</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.deviceExpiring || 0) > 0 }">
          <b>{{ overview.deviceExpiring || 0 }}</b><span>标准器待溯源</span>
        </div>
      </div>
    </header>

    <!-- 横向模块条 -->
    <nav class="navbar">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <div class="body-wrap">
      <!-- 左侧常驻「待办队列」：按流转状态分组 -->
      <aside class="queue">
        <div class="queue-head">🧾 待办队列（按流转状态）</div>
        <div v-for="group in queue" :key="group.status" class="qgroup">
          <div class="qgroup-head">
            <span class="qdot" :class="'qdot-' + group.status"></span>
            {{ group.statusName }}
            <span class="qcount">{{ group.count }}</span>
          </div>
          <button
            v-for="item in group.items"
            :key="item.id"
            class="qitem"
            :class="{ 'is-on': item.id === orderId, 'is-blocked': item.blockReasons && item.blockReasons.length }"
            type="button"
            :title="(item.blockReasons || []).join('；')"
            @click="selectOrder(item.id)"
          >
            <b>{{ item.orderNo }}</b>
            <span>{{ item.instNo }} · {{ item.instrumentName }}</span>
            <em>{{ item.clientName }}<template v-if="item.daysWaiting !== null"> · 已 {{ item.daysWaiting }} 天</template></em>
            <span class="qitem-tags">
              <el-tag size="small" :type="item.deviceCount ? 'primary' : 'info'" effect="plain">
                标准器 {{ item.deviceCount || 0 }}
              </el-tag>
              <el-tag v-if="item.earliestCertValidUntil" size="small"
                      :type="earliestTagType(item)" effect="plain">
                至 {{ item.earliestCertValidUntil }}
              </el-tag>
              <el-tag v-if="item.blockReasons && item.blockReasons.length" size="small" type="danger">
                不可推进
              </el-tag>
            </span>
          </button>
          <div v-if="!group.items.length" class="qempty">暂无</div>
        </div>
      </aside>

      <main class="stage">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { orderApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const overview = ref({})
const queue = ref([])
const orderList = ref([])
const orderId = ref(null)
const currentOrder = ref({})

const flatOrders = computed(() => {
  const list = []
  queue.value.forEach((group) => group.items.forEach((item) => list.push(item)))
  return list
})

/** 全局上下文：左侧队列点单 → 顶栏面包屑与流转页同步切换 */
const lab = {
  overview,
  queue,
  orderList,
  orderId,
  currentOrder,
  refreshAll: loadAll,
  refreshQueue: loadQueue,
  selectOrder
}
provide('lab', lab)

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'orders'
})

function go(path) {
  nav.push(path)
}

async function selectOrder(id) {
  orderId.value = id
  if (!route.path.startsWith('/orders')) {
    nav.push('/orders')
  }
  await loadCurrent()
}

async function loadCurrent() {
  if (orderId.value == null) {
    currentOrder.value = {}
    return
  }
  currentOrder.value = await orderApi.detail(orderId.value)
}

async function loadQueue() {
  queue.value = await orderApi.queue()
  orderList.value = await orderApi.list()
}

async function loadOverview() {
  overview.value = await orderApi.overview()
}

/** 最早证书到期日的徽标颜色：有不可推进原因一律红，60 天内黄，否则绿 */
function earliestTagType(item) {
  if (item.blockReasons && item.blockReasons.length) return 'danger'
  return 'warning'
}

async function loadAll() {
  await Promise.all([loadQueue(), loadOverview()])
  if (orderId.value == null) {
    // 默认落在最靠前的在流转委托单上
    let picked = null
    for (const group of queue.value) {
      if (!picked && group.items.length && group.status !== 'ISSUED') {
        picked = group.items[0].id
      }
    }
    if (picked == null && queue.value.length && queue.value[0].items.length) {
      picked = queue.value[0].items[0].id
    }
    orderId.value = picked
  }
  await loadCurrent()
}

onMounted(loadAll)
</script>
