<template>
  <div class="workbench">
    <!-- ===================== 左：会话列表 ===================== -->
    <el-card class="panel list-panel" shadow="never">
      <div class="panel-head">
        <!-- 这里的值直接就是后端 mine 参数的取值：false = 待接管池，true = 我的会话 -->
        <el-radio-group v-model="tab" size="small" @change="onTabChange">
          <el-radio-button value="false">待接管池</el-radio-button>
          <el-radio-button value="true">我的会话</el-radio-button>
        </el-radio-group>
        <el-button size="small" text @click="loadList(false)">刷新</el-button>
      </div>

      <div v-loading="listLoading" class="conv-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          :class="['conv-item', current && c.id === current.id ? 'active' : '']"
          @click="select(c)"
        >
          <div class="line1">
            <span class="cid">会话 #{{ c.id }}</span>
            <el-tag v-if="!c.agentId" size="small" type="warning">待接管</el-tag>
            <el-tag v-else size="small" type="success">已接管</el-tag>
          </div>
          <div class="line2">{{ fmtTime(c.lastMessageAt) || '暂无消息' }}</div>
        </div>

        <el-empty
          v-if="!listLoading && conversations.length === 0"
          :description="tab === 'true' ? '你还没有接管任何会话' : '暂无可接管的会话'"
          :image-size="56"
        />
      </div>
    </el-card>

    <!-- ===================== 右：对话区 ===================== -->
    <el-card class="panel chat-panel" shadow="never">
      <el-empty v-if="!current" description="从左侧选择一个会话开始服务" />

      <template v-else>
        <div class="chat-head">
          <div>
            <span class="cid">会话 #{{ current.id }}</span>
            <span class="sub">
              {{ current.agentId ? '负责坐席 ID：' + current.agentId : '尚未分配坐席' }}
            </span>
          </div>
          <el-button v-if="!current.agentId" type="primary" size="small" @click="takeover">
            接管会话
          </el-button>
        </div>

        <div ref="listRef" class="msg-list">
          <div v-for="m in messages" :key="m.id" :class="['bubble', bubbleClass(m)]">
            <div class="meta">{{ senderName(m.senderType) }} · {{ fmtTime(m.createdAt) }}</div>
            <div class="content">{{ m.content }}</div>
          </div>
        </div>

        <div class="input-bar">
          <el-input
            v-model="text"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            resize="none"
            placeholder="回复访客，Enter 发送，Shift+Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <el-button type="primary" :loading="sending" @click="send">发送</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const tab = ref('false')            // 'false' = 待接管池，'true' = 我的会话
const conversations = ref([])
const current = ref(null)           // 当前选中的会话
const messages = ref([])
const text = ref('')
const listLoading = ref(false)
const sending = ref(false)
const listRef = ref(null)

// ★ 轮询兜底：坐席侧目前**没有**推送通道——后端只把坐席消息推给访客（SseSessionRegistry），
//   访客发来的消息没有反向推送。所以用 5 秒一次的轮询撑住演示。
//   W7 上 RocketMQ 后改成事件通知，这里换成订阅即可（这是路线图上排好的活）。
let pollTimer = null

onMounted(async () => {
  await loadList(false)
  pollTimer = setInterval(tick, 5000)
})

onUnmounted(() => {
  // 组件卸载（切到工单页/退出登录）必须停掉定时器，否则它会在后台一直发请求
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

async function tick() {
  await loadList(true)
  if (current.value) {
    // 用列表里的新对象刷新 current：接管后 agentId 会变，右侧头部要跟着变
    const fresh = conversations.value.find((c) => c.id === current.value.id)
    if (fresh) current.value = fresh
    await loadMessages(current.value.id, true)
  }
}

function onTabChange() {
  // 切 tab 就把选择清掉：待接管池和我的会话本来就没有交集，留着选中项反而容易误发消息
  current.value = null
  messages.value = []
  loadList(false)
}

async function loadList(silent) {
  if (!silent) listLoading.value = true
  try {
    const list = await api.get('/console/conversations', { params: { mine: tab.value } })
    conversations.value = list || []
  } catch (e) {
    // 轮询失败不弹提示（后端重启那几秒会疯狂弹），只保留手动刷新时的报错
    if (!silent) ElMessage.error(e.message)
  } finally {
    listLoading.value = false
  }
}

async function select(c) {
  current.value = c
  messages.value = []
  await loadMessages(c.id, false)
}

async function loadMessages(conversationId, silent) {
  try {
    const list = await api.get(`/console/conversations/${conversationId}/messages`, {
      params: { size: 50 }
    })
    const arr = (list || []).slice().reverse()   // 后端返回"新的在前"，展示要反过来

    // 轮询时不要无脑整体替换：那会打断坐席正在看的滚动位置，气泡也会闪一下。
    // 只有当"最后一条变了"才整体刷新（其余情况没有新消息，什么都不用做）
    const last = messages.value[messages.value.length - 1]
    if (arr.length === messages.value.length && last && arr.length > 0 && arr[arr.length - 1].id === last.id) {
      return
    }
    messages.value = arr
    scrollToBottom()
  } catch (e) {
    if (!silent) ElMessage.error(e.message)
  }
}

async function takeover() {
  try {
    await api.post(`/console/conversations/${current.value.id}/takeover`)
    ElMessage.success('接管成功')
    // 接管后这条会话就从"待接管池"消失了 —— 主动切到"我的会话"，免得坐席以为会话丢了
    tab.value = 'true'
    await loadList(false)
    const fresh = conversations.value.find((c) => c.id === current.value.id)
    if (fresh) current.value = fresh
  } catch (e) {
    ElMessage.error(e.message)   // 被别的坐席抢先接管时，后端会返回"会话已被其他坐席接管，请刷新"
  }
}

async function send() {
  const content = text.value.trim()
  if (!content || !current.value || sending.value) return
  sending.value = true
  try {
    const id = await api.post(`/console/conversations/${current.value.id}/messages`, {
      message: content
    })
    text.value = ''
    // 本地先追加气泡，不用等下一次轮询。
    // 消息已经确定落库（接口把 id 返回来了），所以这一步是"用后端的 id 造气泡"，不是乐观猜测。
    messages.value.push({
      id,
      conversationId: current.value.id,
      senderType: 'AGENT',
      content,
      createdAt: new Date().toISOString()
    })
    scrollToBottom()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sending.value = false
  }
}

function scrollToBottom() {
  nextTick().then(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function senderName(type) {
  return { VISITOR: '访客', AGENT: '我（坐席）', AI: 'AI 客服', SYSTEM: '系统' }[type] || type
}

// 站席视角：访客在左、自己在右；AI 和系统消息是"旁观者"，用灰色气泡区分开
function bubbleClass(m) {
  if (m.senderType === 'AGENT') return 'right'
  if (m.senderType === 'VISITOR') return 'left'
  return 'left other'
}

// 后端 LocalDateTime 序列化成 "2026-09-30T21:12:33"，直接展示有点丑，换成空格分隔
function fmtTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.workbench {
  display: flex;
  gap: 16px;
  height: calc(100vh - 92px);   /* 顶栏 + 内边距 */
}

.panel { border: none; }
.list-panel { width: 280px; flex-shrink: 0; display: flex; flex-direction: column; }
.chat-panel { flex: 1; display: flex; flex-direction: column; }

/* 让卡片内容区变成 flex 列，好把"滚动区"和"底部输入框"分开 */
.list-panel :deep(.el-card__body),
.chat-panel :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 12px;
  min-height: 0;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.conv-list { flex: 1; overflow-y: auto; }
.conv-item {
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
}
.conv-item:hover { background: #f5f7fa; }
.conv-item.active { background: #ecf5ff; border-color: #b3d8ff; }
.line1 { display: flex; align-items: center; justify-content: space-between; }
.cid { font-weight: 600; font-size: 13px; }
.line2 { font-size: 12px; color: #999; margin-top: 4px; }

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid #eee;
}
.chat-head .sub { font-size: 12px; color: #999; margin-left: 10px; }

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 4px;
  background: #f7f8fa;
  border-radius: 6px;
  margin: 10px 0;
}
.bubble { margin-bottom: 12px; max-width: 80%; }
.bubble.right { margin-left: auto; }
.bubble .meta { font-size: 12px; color: #999; margin-bottom: 4px; }
.bubble.right .meta { text-align: right; }
.bubble .content {
  padding: 10px 14px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, .06);
  word-break: break-word;
  white-space: pre-wrap;
}
/* 坐席自己的消息用主色区分（访客是白底） */
.bubble.right .content { background: #409eff; color: #fff; }
/* AI / 系统消息用灰底小字，一眼看出"这不是人在说话" */
.bubble.other .content { background: #f0f2f5; color: #666; font-size: 13px; }

.input-bar { display: flex; gap: 8px; align-items: flex-end; }
.input-bar :deep(.el-textarea) { flex: 1; }
.input-bar :deep(.el-textarea__inner) { box-shadow: none; }
</style>
