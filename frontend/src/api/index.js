import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 模块一：送检客户 */
export const clientApi = {
  list: () => http.get('/clients'),
  orders: (id) => http.get(`/clients/${id}/orders`),
  create: (data) => http.post('/clients', data)
}

/** 模块二：计量器具台账（器具编号 = 自然主键） */
export const instrumentApi = {
  list: () => http.get('/instruments'),
  /** 【@NaturalId】按器具编号查器具 */
  find: (instNo) => http.get(`/instruments/${encodeURIComponent(instNo)}`),
  /** 【@NaturalId 批量加载】逗号分隔的多个器具编号 */
  batch: (instNos) => http.get('/instruments/batch', { params: { instNos } }),
  /** 派生查询：N 天内到期清单 */
  due: (days) => http.get('/instruments/due', { params: { days } }),
  create: (data) => http.post('/instruments', data),
  /** 台账「送检」：生成收样状态的委托单 */
  submit: (instNo, data) => http.post(`/instruments/${encodeURIComponent(instNo)}/submit`, data)
}

/** 模块三：检定规程 */
export const procedureApi = {
  list: () => http.get('/procedures'),
  create: (data) => http.post('/procedures', data)
}

/** 模块四：检定委托与流转单 */
export const orderApi = {
  list: (params) => http.get('/calibration-orders', { params: params || {} }),
  queue: () => http.get('/calibration-orders/queue'),
  overview: () => http.get('/calibration-orders/overview'),
  detail: (id) => http.get(`/calibration-orders/${id}`),
  create: (data) => http.post('/calibration-orders', data),
  result: (id, data) => http.post(`/calibration-orders/${id}/result`, data),
  advance: (id) => http.post(`/calibration-orders/${id}/advance`),
  rollback: (id) => http.post(`/calibration-orders/${id}/rollback`),
  voidOrder: (id) => http.post(`/calibration-orders/${id}/void`),
  /** 挂接抽屉：当前清单 + 候选标准器（含占用冲突） */
  devices: (id) => http.get(`/calibration-orders/${id}/devices`),
  /** 全量保存挂接清单（挂接/替换/解除），服务端事务判定占用与放行 */
  saveDevices: (id, deviceIds) => http.put(`/calibration-orders/${id}/devices`, { deviceIds })
}

/** 模块五：标准器 */
export const deviceApi = {
  list: () => http.get('/standard-devices'),
  create: (data) => http.post('/standard-devices', data),
  setStatus: (id, status) => http.post(`/standard-devices/${id}/status`, { status }),
  /** 修订档案/证书（如证书被修订为更早到期） */
  update: (id, data) => http.put(`/standard-devices/${id}`, data)
}
