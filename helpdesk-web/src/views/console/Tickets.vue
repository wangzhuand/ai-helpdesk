<template>
  <el-card shadow="never" class="page">
    <!-- ===================== 筛选栏 ===================== -->
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="loadList(1)">
        <el-option v-for="(text, key) in STATUS_TEXT" :key="key" :label="text" :value="key" />
      </el-select>
      <el-select v-model="query.priority" placeholder="全部优先级" clearable style="width: 140px" @change="loadList(1)">
        <el-option label="优先级 1" :value="1" />
        <el-option label="优先级 2" :value="2" />
        <el-option label="优先级 3" :value="3" />
      </el-select>
      <el-button @click="loadList(1)">查询</el-button>
      <el-button text @click="resetQuery">重置</el-button>

      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建工单</el-button>
    </div>

    <!-- ===================== 列表 ===================== -->
    <el-table v-loading="loading" :data="rows" border stripe style="margin-top: 12px">
      <el-table-column prop="ticketNo" label="单号" width="180" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column prop="priority" label="优先级" width="80" align="center" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="STATUS_TYPE[row.status] || 'info'" size="small">
            {{ STATUS_TEXT[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="负责人" width="100" align="center">
        <template #default="{ row }">
          <span v-if="row.assigneeId">{{ row.assigneeId }}</span>
          <el-tag v-else size="small" type="warning">未分配</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openDetail(row.id)">详情</el-button>
          <!-- 未分配的才能抢（后端 takeOver 也是这么判的，这里只是不浪费时间点） -->
          <el-button v-if="!row.assigneeId" size="small" text type="warning" @click="takeover(row)">
            抢单
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :current-page="query.page"
      :page-size="query.size"
      @current-change="loadList"
    />
  </el-card>

  <!-- ===================== 新建工单 ===================== -->
  <el-dialog v-model="createVisible" title="新建工单" width="520px">
    <el-form label-width="90px">
      <el-form-item label="标题"><el-input v-model="form.title" placeholder="一句话概括问题" /></el-form-item>
      <el-form-item label="分类">
        <el-select v-model="form.category" placeholder="选择分类" style="width: 100%">
          <el-option label="订单问题" value="ORDER" />
          <el-option label="退款退货" value="REFUND" />
          <el-option label="产品咨询" value="PRODUCT" />
          <el-option label="其他" value="OTHER" />
        </el-select>
      </el-form-item>
      <el-form-item label="关联会话">
        <el-input v-model="form.conversationId" placeholder="会话 ID（可留空）" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="4" placeholder="详细描述" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 工单详情 ===================== -->
  <el-drawer v-model="detailVisible" title="工单详情" size="620px">
    <div v-if="detail" class="detail">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="单号">{{ detail.ticketNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="STATUS_TYPE[detail.status] || 'info'" size="small">
            {{ STATUS_TEXT[detail.status] || detail.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="标题" :span="2">{{ detail.title }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ detail.category || '-' }}</el-descriptions-item>
        <el-descriptions-item label="优先级">{{ detail.priority }}</el-descriptions-item>
        <el-descriptions-item label="关联会话">
          {{ detail.conversationId ? '#' + detail.conversationId : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="负责人">{{ detail.assigneeId || '未分配' }}</el-descriptions-item>
        <el-descriptions-item label="SLA 截止">{{ fmtTime(detail.slaDeadline) || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ fmtTime(detail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ detail.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 流转按钮：只列出"当前状态能合法到达"的目标，避免点了才被后端拒绝 -->
      <div class="section">
        <div class="section-title">可执行的流转</div>
        <div v-if="nextStatuses.length === 0" class="muted">当前状态是终态，没有可执行的流转</div>
        <div v-else class="transitions">
          <el-button
            v-for="s in nextStatuses"
            :key="s"
            size="small"
            type="primary"
            plain
            @click="doTransition(s)"
          >
            流转到「{{ STATUS_TEXT[s] }}」
          </el-button>
        </div>
      </div>

      <div class="section">
        <div class="section-title">流转日志</div>
        <el-timeline>
          <el-timeline-item
            v-for="log in logs"
            :key="log.id"
            :timestamp="fmtTime(log.createdAt)"
            placement="top"
          >
            <div class="log-line">
              <b>{{ log.action }}</b>
              <span v-if="log.fromStatus">{{ STATUS_TEXT[log.fromStatus] || log.fromStatus }} → </span>
              <span>{{ STATUS_TEXT[log.toStatus] || log.toStatus }}</span>
              <span class="muted">（操作人 {{ log.operatorId || 'AI/系统' }}）</span>
            </div>
            <div v-if="log.remark" class="muted">{{ log.remark }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const STATUS_TEXT = {
  OPEN: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  REOPENED: '重新打开'
}
const STATUS_TYPE = {
  OPEN: 'warning',
  PROCESSING: 'primary',
  RESOLVED: 'success',
  CLOSED: 'info',
  REOPENED: 'danger'
}

// ★ 这份规则表是后端 TicketStatus.ALLOWED 的镜像，只用来"提示"下一个合法状态。
//   **真正的裁决永远在后端**——前端按钮只是省得用户白点一次，
//   绕过界面直接发请求照样会被后端拒绝（这就是"前端的校验不算校验"）。
const ALLOWED = {
  OPEN: ['PROCESSING'],
  PROCESSING: ['RESOLVED'],
  RESOLVED: ['CLOSED', 'REOPENED'],
  REOPENED: ['PROCESSING'],
  CLOSED: []
}

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref({ status: '', priority: null, page: 1, size: 10 })

const createVisible = ref(false)
const creating = ref(false)
const form = ref({ title: '', category: '', description: '', conversationId: '' })

const detailVisible = ref(false)
const detail = ref(null)
const logs = ref([])

const nextStatuses = computed(() => {
  if (!detail.value) return []
  return ALLOWED[detail.value.status] || []
})

onMounted(() => loadList(1))

async function loadList(page) {
  if (page) query.value.page = page
  loading.value = true
  try {
    // 空字符串会被后端当成"等于空字符串"来查，所以只传有值的参数
    const params = { page: query.value.page, size: query.value.size }
    if (query.value.status) params.status = query.value.status
    if (query.value.priority) params.priority = query.value.priority

    const res = await api.get('/console/tickets', { params })
    rows.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.value = { status: '', priority: null, page: 1, size: 10 }
  loadList(1)
}

function openCreate() {
  form.value = { title: '', category: '', description: '', conversationId: '' }
  createVisible.value = true
}

async function submitCreate() {
  if (!form.value.title) {
    ElMessage.warning('请填写标题')
    return
  }
  creating.value = true
  try {
    // conversationId 是数字类型，空字符串要转成 null，否则后端反序列化失败
    const body = { ...form.value }
    body.conversationId = form.value.conversationId ? Number(form.value.conversationId) : null
    await api.post('/console/tickets', body)
    ElMessage.success('创建成功')
    createVisible.value = false
    loadList(1)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

async function takeover(row) {
  try {
    await api.post(`/console/tickets/${row.id}/takeover`)
    ElMessage.success('抢单成功')
    loadList()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openDetail(id) {
  detailVisible.value = true
  try {
    detail.value = await api.get(`/console/tickets/${id}`)
    logs.value = await api.get(`/console/tickets/${id}/logs`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doTransition(target) {
  try {
    const { value } = await ElMessageBox.prompt(
      `即将把工单流转到「${STATUS_TEXT[target]}」，可填写备注`,
      '工单流转',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '备注（可留空）' }
    )
    await api.post(`/console/tickets/${detail.value.id}/transition`, {
      status: target,
      remark: value || ''
    })
    ElMessage.success('流转成功')
    await openDetail(detail.value.id)   // 重新拉详情和日志，状态和按钮会跟着变
    loadList()
  } catch (e) {
    // ElMessageBox 点"取消"时 reject 的是字符串 'cancel'/'close'，不是 Error —— 不能当报错处理
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e.message || '操作失败')
  }
}

function fmtTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.page { border: none; }
.toolbar { display: flex; align-items: center; gap: 8px; }
.spacer { flex: 1; }
.pager { margin-top: 14px; justify-content: flex-end; }

.detail .section { margin-top: 20px; }
.section-title { font-weight: 600; margin-bottom: 10px; }
.transitions { display: flex; gap: 8px; flex-wrap: wrap; }
.muted { color: #999; font-size: 12px; }
.log-line { font-size: 13px; }
</style>
