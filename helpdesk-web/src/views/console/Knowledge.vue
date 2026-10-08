<template>
  <el-card shadow="never" class="page">
    <el-tabs v-model="activeTab">
      <!-- ===================== 文档管理 ===================== -->
      <el-tab-pane label="文档管理" name="docs">
        <div class="toolbar">
          <span class="hint">上传后立即返回，切块与向量化在后台异步处理；状态从「待处理」变为「已就绪」即可用于检索</span>
          <div class="spacer" />
          <el-button text @click="loadDocs">刷新</el-button>
          <el-button type="primary" @click="uploadVisible = true">上传文档</el-button>
        </div>

        <el-table v-loading="docLoading" :data="docs" border stripe style="margin-top: 12px">
          <el-table-column prop="id" label="ID" width="70" align="center" />
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="DOC_STATUS_TYPE[row.status] || 'info'" size="small">
                {{ DOC_STATUS_TEXT[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="chunkNum" label="块数" width="80" align="center" />
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 检索测试 ===================== -->
      <el-tab-pane label="检索测试" name="search">
        <div class="toolbar">
          <el-input
            v-model="q"
            placeholder="输入一个问题，比如：怎么退款"
            style="width: 320px"
            @keyup.enter="search"
          />
          <el-select v-model="mode" style="width: 150px">
            <el-option label="混合检索" value="hybrid" />
            <el-option label="关键词 BM25" value="keyword" />
            <el-option label="向量 kNN" value="vector" />
            <el-option label="三路对比" value="compare" />
          </el-select>
          <el-input-number v-model="k" :min="1" :max="20" style="width: 120px" />
          <el-button type="primary" :loading="searching" @click="search">检索</el-button>
        </div>

        <!-- 三路对比：同一个问题分别走三种检索，结果并排 -->
        <div v-if="mode === 'compare'" v-loading="searching" class="compare">
          <div v-for="m in ['keyword', 'vector', 'hybrid']" :key="m" class="compare-col">
            <div class="col-head">
              {{ MODE_TEXT[m] }}
              <span v-if="cost[m] !== undefined" class="cost">{{ cost[m] }} ms</span>
            </div>
            <div v-for="(r, i) in compare[m]" :key="i" class="hit">
              <div class="hit-head">
                <span class="rank">#{{ i + 1 }}</span>
                <span class="score">{{ r.score === null || r.score === undefined ? '-' : r.score.toFixed(4) }}</span>
                <span class="doc">《{{ r.docTitle }}》 第 {{ r.chunkIndex }} 块</span>
              </div>
              <div class="snippet">{{ r.content }}</div>
            </div>
            <el-empty v-if="!searching && (!compare[m] || compare[m].length === 0)"
                      description="无结果" :image-size="48" />
          </div>
        </div>

        <!-- 单模式结果 -->
        <el-table v-else v-loading="searching" :data="result" border stripe style="margin-top: 12px">
          <el-table-column type="index" label="#" width="60" align="center" />
          <el-table-column label="分数" width="110" align="center">
            <template #default="{ row }">
              {{ row.score === null || row.score === undefined ? '-' : row.score.toFixed(4) }}
            </template>
          </el-table-column>
          <el-table-column label="来源" width="220">
            <template #default="{ row }">《{{ row.docTitle }}》 第 {{ row.chunkIndex }} 块</template>
          </el-table-column>
          <el-table-column prop="content" label="命中片段" min-width="300" show-overflow-tooltip />
        </el-table>

        <div class="hint" style="margin-top: 10px">
          提示：把"三路对比"当做 W12 评测的雏形 —— 关键词擅长精确的字（订单号、编号），
          向量擅长模糊的意思（"退款" ↔ "退货流程"），混合用 RRF 把两者按排名融合。
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===================== 上传文档 ===================== -->
  <el-dialog v-model="uploadVisible" title="上传文档" width="640px">
    <el-form label-width="70px">
      <el-form-item label="标题"><el-input v-model="form.title" placeholder="文档标题" /></el-form-item>
      <el-form-item label="内容">
        <el-input v-model="form.content" type="textarea" :rows="12" placeholder="粘贴纯文本内容" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="uploadVisible = false">取消</el-button>
      <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const activeTab = ref('docs')

// ---------- 文档 ----------
const docs = ref([])
const docLoading = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const form = ref({ title: '', content: '' })

const DOC_STATUS_TEXT = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  READY: '已就绪',
  FAILED: '失败'
}
const DOC_STATUS_TYPE = {
  PENDING: 'info',
  PROCESSING: 'warning',
  READY: 'success',
  FAILED: 'danger'
}

// ---------- 检索 ----------
const q = ref('')
const mode = ref('hybrid')
const k = ref(3)
const result = ref([])
const compare = ref({ keyword: [], vector: [], hybrid: [] })
const cost = ref({})          // 各模式的耗时（前端自己掐表，后端没返回这个字段）
const searching = ref(false)

const MODE_TEXT = { keyword: '关键词 BM25', vector: '向量 kNN', hybrid: '混合 + RRF' }

onMounted(loadDocs)

async function loadDocs() {
  docLoading.value = true
  try {
    docs.value = await api.get('/console/kb/documents')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    docLoading.value = false
  }
}

async function submitUpload() {
  if (!form.value.title || !form.value.content) {
    ElMessage.warning('标题和内容都要填')
    return
  }
  uploading.value = true
  try {
    await api.post('/console/kb/documents', form.value)
    ElMessage.success('上传成功')
    uploadVisible.value = false
    form.value = { title: '', content: '' }
    loadDocs()
  } catch (e) {
    ElMessage.error(e.message)   // 标题为空时后端会返回"参数校验失败：title 标题不能为空"
  } finally {
    uploading.value = false
  }
}

async function search() {
  if (!q.value.trim()) {
    ElMessage.warning('请输入查询内容')
    return
  }
  searching.value = true
  try {
    if (mode.value === 'compare') {
      // 三路并行发（Promise.all），总耗时约等于最慢的那一路，不是三路相加
      const modes = ['keyword', 'vector', 'hybrid']
      const started = Date.now()
      const res = await Promise.all(
        modes.map((m) => api.get('/console/kb/search-test', {
          params: { q: q.value, mode: m, k: k.value }
        }))
      )
      const total = Date.now() - started
      const next = {}
      modes.forEach((m, i) => { next[m] = res[i] || [] })
      compare.value = next
      // 并行不好单独计时，这里给三路填同一个总耗时（够用来横向对比后端三种查询的快慢趋势）
      cost.value = { keyword: total, vector: total, hybrid: total }
    } else {
      const started = Date.now()
      result.value = await api.get('/console/kb/search-test', {
        params: { q: q.value, mode: mode.value, k: k.value }
      })
      cost.value = { [mode.value]: Date.now() - started }
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    searching.value = false
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
.hint { font-size: 12px; color: #999; line-height: 1.8; }

/* 三路对比：三列并排，窄屏自动换行 */
.compare { display: flex; gap: 12px; margin-top: 12px; flex-wrap: wrap; }
.compare-col {
  flex: 1;
  min-width: 280px;
  background: #f7f8fa;
  border-radius: 6px;
  padding: 10px;
}
.col-head {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
  display: flex;
  justify-content: space-between;
}
.cost { color: #999; font-weight: 400; font-size: 12px; }
.hit {
  background: #fff;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 8px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, .05);
}
.hit-head { font-size: 12px; margin-bottom: 4px; }
.rank { color: #409eff; font-weight: 600; margin-right: 6px; }
.score { color: #e6a23c; margin-right: 6px; }
.doc { color: #666; }
.snippet {
  font-size: 12px;
  color: #555;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
