<template>
  <div class="split">
    <!-- 左：竖向步骤指示器 + 流转动作 + 结论录入 -->
    <section class="split-left panel">
      <div class="panel-head">
        <span class="panel-title">检定流转</span>
        <span class="spacer"></span>
        <el-button size="small" type="primary" plain @click="openNew">＋ 新建委托单</el-button>
      </div>

      <el-steps direction="vertical" :active="activeStep" finish-status="success" class="flow-steps">
        <el-step v-for="step in flowSteps" :key="step.key" :title="step.name">
          <template #description>
            <span class="flow-desc">{{ step.desc }}</span>
          </template>
        </el-step>
      </el-steps>

      <div class="flow-actions">
        <el-button type="primary" size="small" :disabled="!canRollback" @click="rollback">◂ 回退</el-button>
        <el-button type="primary" size="small" :disabled="!canAdvance" @click="advance">推进 ▸</el-button>
      </div>

      <div class="flow-form">
        <div class="flow-form-title">结论与证书信息</div>
        <el-form label-width="72px" size="small">
          <el-form-item label="实测数据">
            <el-input v-model="form.measuredData" type="textarea" :rows="4"
                      placeholder="每行一条：校准点|标准值|示值|示值误差" />
          </el-form-item>
          <el-form-item label="检定结论">
            <el-select v-model="form.conclusion" placeholder="未判定" clearable style="width: 100%">
              <el-option label="合格" value="PASS" />
              <el-option label="限用" value="LIMITED" />
              <el-option label="不合格" value="FAIL" />
            </el-select>
          </el-form-item>
          <el-form-item label="证书编号">
            <el-input v-model="form.certNo" placeholder="出证前必填" />
          </el-form-item>
          <el-form-item label="检定员">
            <el-input v-model="form.verifier" />
          </el-form-item>
          <el-form-item label="核验员">
            <el-input v-model="form.checker" />
          </el-form-item>
          <el-form-item>
            <el-button size="small" type="primary" @click="saveResult">保存检定数据</el-button>
          </el-form-item>
        </el-form>
      </div>
    </section>

    <!-- 右：A4 比例纸质证书预览卡片 -->
    <section class="split-right">
      <div class="cert-paper">
        <div class="cert-head">
          <div class="cert-org">
            <div class="cert-org-name">滨江市计量检定所</div>
            <div class="cert-org-sub">法定计量技术机构 · 授权证书号 (国)法计(2026) 第 0032 号</div>
          </div>
          <div class="cert-qr" title="证书二维码（占位）"></div>
        </div>

        <div class="cert-title">检 定 证 书</div>
        <div class="cert-no">
          <span>证书编号：{{ order.certNo || '待出证' }}</span>
          <span>委托单号：{{ order.orderNo || '—' }}</span>
        </div>

        <table class="cert-info">
          <tbody>
            <tr><th>委托单位</th><td>{{ order.clientName || '—' }}</td><th>器具编号</th><td class="mono">{{ order.instNo || '—' }}</td></tr>
            <tr><th>器具名称</th><td>{{ order.instrumentName || '—' }}</td><th>型号规格</th><td>{{ order.modelSpec || '—' }}</td></tr>
            <tr><th>制造厂</th><td>{{ order.manufacturer || '—' }}</td><th>出厂编号</th><td>{{ order.factoryNo || '—' }}</td></tr>
            <tr><th>测量范围</th><td>{{ order.measureRange || '—' }}</td><th>准确度等级</th><td>{{ order.accuracyGrade || '—' }}</td></tr>
            <tr><th>检定依据</th><td colspan="3">{{ procedureText }}</td></tr>
          </tbody>
        </table>

        <div class="cert-subtitle">检定结果（标准器 • 环境条件：{{ envText }}）</div>
        <table class="cert-data">
          <thead>
            <tr><th>校准点</th><th>标准值</th><th>示值</th><th>示值误差</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in dataRows" :key="i">
              <td>{{ row[0] || '—' }}</td>
              <td>{{ row[1] || '—' }}</td>
              <td>{{ row[2] || '—' }}</td>
              <td>{{ row[3] || '—' }}</td>
            </tr>
            <tr v-if="!dataRows.length">
              <td colspan="4" class="cert-empty">尚未录入实测数据</td>
            </tr>
          </tbody>
        </table>

        <div class="cert-concl">
          检定结论：<b :class="conclusionClass">{{ conclusionText }}</b>
          <span v-if="order.remark" class="cert-concl-note">（{{ order.remark }}）</span>
        </div>

        <div class="cert-stamp" :class="{ 'is-none': order.status === 'RECEIVED' || order.status === 'CALIBRATING' }">
          <span class="cert-stamp-big">{{ stampText }}</span>
          <span class="cert-stamp-small">计量检定专用章</span>
        </div>

        <div class="cert-sign">
          <div><span>检定员</span><b>{{ order.verifier || '待指派' }}</b></div>
          <div><span>核验员</span><b>{{ order.checker || '待指派' }}</b></div>
          <div><span>批准人</span><b>{{ approver }}</b></div>
        </div>

        <div class="cert-foot">
          <span>证书有效期至：{{ order.certValidUntil || '出证后按检定周期核定' }}</span>
          <span>第 1 页 共 1 页</span>
        </div>
      </div>
    </section>

    <!-- 新建委托单 -->
    <el-dialog v-model="showNew" title="新建检定委托单（按器具编号受理）" width="520px">
      <el-form label-width="96px" size="small">
        <el-form-item label="委托单号">
          <el-input v-model="newForm.orderNo" />
        </el-form-item>
        <el-form-item label="器具编号">
          <el-select v-model="newForm.instNo" filterable placeholder="选器具编号" style="width: 100%">
            <el-option v-for="it in instruments" :key="it.instNo" :label="it.instNo + ' · ' + it.name" :value="it.instNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="检定规程">
          <el-select v-model="newForm.procNo" filterable placeholder="选规程号" style="width: 100%">
            <el-option v-for="p in procedures" :key="p.procNo" :label="p.procNo + ' · ' + p.name" :value="p.procNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="收样日期">
          <el-date-picker v-model="newForm.receiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="检定员">
          <el-input v-model="newForm.verifier" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="newForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitNew">受理并生成流转单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { instrumentApi, orderApi, procedureApi } from '../api'

const lab = inject('lab')
const order = computed(() => lab.currentOrder.value || {})

const FLOW = [
  { key: 'RECEIVED', name: '收样' },
  { key: 'CALIBRATING', name: '检定中' },
  { key: 'VERIFYING', name: '核验' },
  { key: 'CERTIFYING', name: '出证' },
  { key: 'ISSUED', name: '已发放' }
]

const flowSteps = computed(() => {
  const index = order.value.stepIndex == null ? 0 : order.value.stepIndex
  return FLOW.map((step, i) => ({
    ...step,
    desc: stepDesc(step.key, index, i)
  }))
})

function stepDesc(key, index, i) {
  if (order.value.id == null) return '—'
  if (i < index) return '已完成 · ' + (i === 0 ? (order.value.receiveDate || '已收样') : '流转留痕')
  if (i === index) return '当前环节 · 待处理'
  return '待流转'
}

const activeStep = computed(() => {
  if (order.value.id == null) return -1
  return order.value.stepIndex == null ? 0 : order.value.stepIndex
})

const canAdvance = computed(() => order.value.id != null && order.value.status !== 'ISSUED')
const canRollback = computed(() =>
  order.value.id != null && order.value.status !== 'RECEIVED' && order.value.status !== 'ISSUED')

const instruments = ref([])
const procedures = ref([])
const procMap = computed(() => {
  const map = {}
  procedures.value.forEach((p) => (map[p.procNo] = p))
  return map
})

const procedureText = computed(() => {
  const no = order.value.procNo
  if (!no) return '—'
  const p = procMap.value[no]
  return p ? p.procNo + ' ' + p.name + '（收费 ' + p.unitFee + ' 元）' : no
})

const envText = computed(() => {
  const p = procMap.value[order.value.procNo]
  return p && p.envCondition ? p.envCondition : '温度 (20±5)℃，湿度 ≤70%RH'
})

/** 实测数据：多条 ';' 分隔，单条 '|' 分隔 */
const dataRows = computed(() => {
  const raw = order.value.measuredData
  if (!raw) return []
  return raw
    .split(';')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split('|'))
})

const conclusionText = computed(() => {
  const map = { PASS: '合格', LIMITED: '限用', FAIL: '不合格' }
  return order.value.conclusion ? map[order.value.conclusion] : '未判定'
})

const conclusionClass = computed(() => ({
  'ok-text': order.value.conclusion === 'PASS',
  'warn-text': order.value.conclusion === 'LIMITED',
  'danger-text': order.value.conclusion === 'FAIL'
}))

const stampText = computed(() => {
  if (order.value.status === 'RECEIVED' || order.value.status === 'CALIBRATING') return '未盖章'
  return conclusionText.value
})

const approver = computed(() => {
  if (order.value.status === 'ISSUED' || order.value.status === 'CERTIFYING') return '所技术负责人'
  return '待签发'
})

const form = reactive({ measuredData: '', conclusion: '', certNo: '', verifier: '', checker: '' })

watch(
  order,
  (val) => {
    form.measuredData = val && val.measuredData ? val.measuredData.split(';').join('\n') : ''
    form.conclusion = val && val.conclusion ? val.conclusion : ''
    form.certNo = val && val.certNo ? val.certNo : ''
    form.verifier = val && val.verifier ? val.verifier : ''
    form.checker = val && val.checker ? val.checker : ''
  },
  { immediate: true }
)

async function saveResult() {
  if (!order.value.id) return
  const payload = {
    measuredData: form.measuredData.split('\n').map((s) => s.trim()).filter(Boolean).join(';'),
    conclusion: form.conclusion || null,
    certNo: form.certNo || null,
    verifier: form.verifier || null,
    checker: form.checker || null
  }
  await orderApi.result(order.value.id, payload)
  ElMessage.success('实测数据与结论已保存')
  await lab.refreshAll()
}

async function advance() {
  await orderApi.advance(order.value.id)
  ElMessage.success('流转推进一格')
  await lab.refreshAll()
}

async function rollback() {
  await orderApi.rollback(order.value.id)
  ElMessage.success('流转回退一格')
  await lab.refreshAll()
}

const showNew = ref(false)
const newForm = reactive({
  orderNo: '',
  instNo: '',
  procNo: '',
  receiveDate: '',
  verifier: '',
  remark: ''
})

function nextOrderNo() {
  const y = new Date().getFullYear()
  const seq = (lab.orderList.value || []).length + 1
  return 'WT' + y + '-' + String(seq).padStart(4, '0')
}

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function openNew() {
  await loadOptions()
  newForm.orderNo = nextOrderNo()
  newForm.receiveDate = today()
  showNew.value = true
}

async function submitNew() {
  if (!newForm.instNo || !newForm.procNo) {
    ElMessage.warning('请选择器具编号与检定规程')
    return
  }
  const created = await orderApi.create({ ...newForm })
  showNew.value = false
  ElMessage.success('委托单已受理，流转状态：收样')
  await lab.refreshAll()
  await lab.selectOrder(created.id)
}

async function loadOptions() {
  const [its, pros] = await Promise.all([instrumentApi.list(), procedureApi.list()])
  instruments.value = its
  procedures.value = pros
}

onMounted(loadOptions)
</script>

<style scoped>
.flow-steps {
  padding: 4px 0;
}

.flow-desc {
  font-size: 12px;
  color: var(--mt-text-sub);
}

.flow-actions {
  display: flex;
  gap: 8px;
  padding: 12px 0 4px;
  border-top: 1px dashed var(--mt-border);
}

.flow-form-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mt-primary);
  margin: 6px 0 10px;
}

/* ---------- A4 纸质证书预览卡片 ---------- */
.cert-paper {
  position: relative;
  width: 100%;
  max-width: 452px;
  margin: 0 auto;
  aspect-ratio: 1 / 1.414;
  background: #fffdf8;
  border: 1px solid #d8d2c4;
  border-radius: 2px;
  box-shadow: 0 6px 18px rgba(60, 50, 30, 0.14);
  padding: 22px 24px 16px;
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #23201c;
  overflow: hidden;
}

/* 纸张底纹：细横线模拟证书纸的安全底印 */
.cert-paper::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(69, 39, 160, 0.035) 1px, transparent 1px);
  background-size: 100% 22px;
  pointer-events: none;
}

.cert-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border-bottom: 2px solid #4527a0;
  padding-bottom: 8px;
}

.cert-org-name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #4527a0;
}

.cert-org-sub {
  font-size: 10px;
  color: #7a7368;
  margin-top: 3px;
}

.cert-qr {
  margin-left: auto;
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  border: 1px solid #8d8d8d;
  background-color: #fff;
  background-image:
    repeating-linear-gradient(0deg, #2b2b2b 0 2px, transparent 2px 4px),
    repeating-linear-gradient(90deg, #2b2b2b 0 2px, transparent 2px 4px);
  background-size: 100% 4px, 4px 100%;
  background-repeat: repeat;
}

.cert-title {
  text-align: center;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 8px;
  margin: 12px 0 6px;
  color: #23201c;
}

.cert-no {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #6a6357;
  margin-bottom: 10px;
}

.cert-info {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 10px;
}

.cert-info th,
.cert-info td {
  border: 1px solid #b9b3a5;
  padding: 4px 6px;
  text-align: left;
}

.cert-info th {
  width: 68px;
  background: #f3f0e6;
  color: #5d5648;
  font-weight: 600;
}

.cert-subtitle {
  font-size: 11px;
  color: #4527a0;
  font-weight: 600;
  margin-bottom: 5px;
}

.cert-data {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 10px;
}

.cert-data th,
.cert-data td {
  border: 1px solid #b9b3a5;
  padding: 3px 6px;
  text-align: center;
}

.cert-data th {
  background: #f3f0e6;
  color: #5d5648;
}

.cert-empty {
  color: #9a9385;
  padding: 8px 0;
}

.cert-concl {
  font-size: 12px;
  margin-bottom: 4px;
}

.cert-concl-note {
  color: #7a7368;
  font-size: 11px;
}

/* 红色圆形印章（圆环 + 文字） */
.cert-stamp {
  position: absolute;
  right: 26px;
  bottom: 96px;
  width: 96px;
  height: 96px;
  border: 3px double #c62828;
  border-radius: 50%;
  color: #c62828;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  transform: rotate(-13deg);
  opacity: 0.86;
  background: rgba(198, 40, 40, 0.03);
}

.cert-stamp::before {
  content: '';
  position: absolute;
  inset: 6px;
  border: 1px solid rgba(198, 40, 40, 0.55);
  border-radius: 50%;
}

.cert-stamp-big {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 2px;
}

.cert-stamp-small {
  font-size: 9px;
  letter-spacing: 1px;
  margin-top: 2px;
}

.cert-stamp.is-none {
  border-color: #a9a39a;
  color: #a9a39a;
  opacity: 0.5;
}

.cert-stamp.is-none::before {
  border-color: rgba(169, 163, 154, 0.6);
}

.cert-sign {
  margin-top: auto;
  display: flex;
  gap: 10px;
  padding-right: 130px;
}

.cert-sign div {
  flex: 1;
  border-top: 1px solid #8d8577;
  padding-top: 3px;
  font-size: 11px;
}

.cert-sign span {
  display: block;
  color: #7a7368;
}

.cert-sign b {
  font-weight: 600;
}

.cert-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed #b9b3a5;
  font-size: 10px;
  color: #7a7368;
}
</style>
