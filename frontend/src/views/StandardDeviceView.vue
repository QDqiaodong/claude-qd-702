<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">标准器</span>
        <span class="panel-note">证书有效期倒计时徽章：过期 / 60 天内待溯源用不同颜色标注</span>
        <span class="spacer"></span>
        <el-select v-model="statusFilter" size="small" placeholder="状态" clearable style="width: 130px">
          <el-option label="在用" value="IN_USE" />
          <el-option label="停用" value="STOPPED" />
          <el-option label="送检" value="TRACING" />
        </el-select>
        <el-button size="small" type="primary" plain @click="showNew = true">＋ 登记标准器</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="deviceNo" label="标准器编号" width="120">
          <template #default="{ row }"><span class="mono">{{ row.deviceNo }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" width="120" />
        <el-table-column prop="measureRange" label="测量范围" width="150" />
        <el-table-column prop="traceOrg" label="溯源机构" min-width="180" show-overflow-tooltip />
        <el-table-column prop="lastTraceDate" label="上次溯源" width="110" />
        <el-table-column prop="certValidUntil" label="有效期至" width="110" />
        <el-table-column label="有效期倒计时" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.expired" type="danger" size="small">已过期 {{ Math.abs(row.remainDays) }} 天</el-tag>
            <el-tag v-else-if="row.expiring" type="warning" size="small">剩 {{ row.remainDays }} 天</el-tag>
            <el-tag v-else-if="row.remainDays !== null" type="success" size="small">剩 {{ row.remainDays }} 天</el-tag>
            <span v-else class="muted">未登记</span>
          </template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="86">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'IN_USE' ? 'success' : row.status === 'TRACING' ? 'warning' : 'info'">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link :disabled="row.status === 'TRACING'" @click="setStatus(row, 'TRACING')">
              送检溯源
            </el-button>
            <el-button size="small" type="primary" link :disabled="row.status === 'STOPPED'" @click="setStatus(row, 'STOPPED')">
              停用
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="showNew" title="登记标准器" width="520px">
      <el-form label-width="110px" size="small">
        <el-form-item label="标准器编号"><el-input v-model="form.deviceNo" placeholder="如 STD-0014" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
        <el-form-item label="测量范围"><el-input v-model="form.measureRange" /></el-form-item>
        <el-form-item label="溯源机构"><el-input v-model="form.traceOrg" /></el-form-item>
        <el-form-item label="上次溯源">
          <el-date-picker v-model="form.lastTraceDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="form.certValidUntil" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="doCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi } from '../api'

const lab = inject('lab')
const list = ref([])
const statusFilter = ref('')

const rows = computed(() =>
  statusFilter.value ? list.value.filter((row) => row.status === statusFilter.value) : list.value
)

async function load() {
  list.value = await deviceApi.list()
}

async function setStatus(row, status) {
  await deviceApi.setStatus(row.id, status)
  ElMessage.success(status === 'TRACING' ? '已送上级机构溯源' : '标准器已停用')
  await Promise.all([load(), lab.refreshAll()])
}

const showNew = ref(false)
const form = reactive({
  deviceNo: '',
  name: '',
  model: '',
  measureRange: '',
  traceOrg: '',
  lastTraceDate: '',
  certValidUntil: ''
})

async function doCreate() {
  if (!form.deviceNo || !form.name) {
    ElMessage.warning('标准器编号与名称必填')
    return
  }
  await deviceApi.create({ ...form })
  showNew.value = false
  ElMessage.success('标准器已登记')
  await load()
}

onMounted(load)
</script>
