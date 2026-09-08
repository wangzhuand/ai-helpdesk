<template>
  <div class="chat-container">
    <div class="header">
      <h3>AI 客服</h3>
      <el-button size="small" text @click="loadOlder">加载更早的消息</el-button>
    </div>

    <div class="message-list" ref="listRef">
      <div v-for="msg in messages" :key="msg.id"
           :class="['bubble', msg.senderType === 'VISITOR' ? 'right' : 'left']">
        <div class="meta">{{ senderName(msg.senderType) }}</div>
        <div class="content">{{ msg.content }}</div>
      </div>
    </div>

    <div class="input-bar">
      <el-input
        v-model="text"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        resize="none"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        :disabled="sending"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="sending" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const messages = ref([])
const text = ref('')
const conversationId = ref(null)
const listRef = ref(null)

onMounted(async () => {
  try {
    // 复用本地保存的会话 id，没有就新建一个（对应"创建会话"接口）
    let cid = localStorage.getItem('conversationId')
    if (!cid) {
      cid = await api.post('/v1/conversations')
      localStorage.setItem('conversationId', cid)
    }
    conversationId.value = Number(cid)
    // 拉历史消息；后端返回"新的在前"，展示时反过来
    const list = await api.get(`/v1/conversations/${cid}/messages`, { params: { size: 50 } })
    messages.value = list.reverse()
    scrollToBottom()   // 打开页面直接滑到最新消息
  } catch (e) {
    ElMessage.error(e.message)
  }
})

const sending = ref(false)

// 滚到底部（气泡新内容出现后调用）
function scrollToBottom() {
  nextTick().then(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function send() {
  const content = text.value.trim()
  if (!content || sending.value) return
  text.value = ''
  sending.value = true

  // 打字机状态：等 visitor 事件（我的消息回执）到了，再插 AI 占位气泡
  const charQueue = []
  let aiIndex = -1            // AI 气泡在 messages 里的下标（-1 = 还没插）
  let timer = null

  // 插入 AI 占位气泡并启动节奏器（每 15ms 吐一个字）
  const startAiBubble = () => {
    messages.value.push({ id: 'temp-' + Date.now(), senderType: 'AI', content: '正在输入…', createdAt: '' })
    aiIndex = messages.value.length - 1
    scrollToBottom()
    timer = setInterval(() => {
      if (aiIndex < 0 || charQueue.length === 0) return
      const ai = messages.value[aiIndex]
      if (!ai) return
      // ★ 必须通过 messages.value[下标] 改——这才是响应式对象；
      //   用局部变量改不会触发界面更新（Vue3 经典坑，记牢）
      if (ai.content === '正在输入…') ai.content = ''
      ai.content += charQueue.shift()
      scrollToBottom()
    }, 15)
  }

  try {
    // 用 fetch 发 POST 并读取流式响应（axios 不适合流式，绕开它）
    const resp = await fetch(`/api/v1/conversations/${conversationId.value}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: content })
    })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })

      // SSE 帧用空行分隔：切出完整帧，最后一段可能不完整留到下一轮
      const frames = buf.split('\n\n')
      buf = frames.pop()
      for (const frame of frames) {
        const ev = parseSse(frame)
        if (!ev) continue

        if (ev.event === 'visitor') {
          // ① 先显示"我"的消息
          messages.value.push(JSON.parse(ev.data))
          scrollToBottom()
        } else if (ev.event === 'token') {
          // ② 第一个 token 来时插入 AI 气泡（在"我"的消息下方），字入队
          if (aiIndex < 0) startAiBubble()
          charQueue.push(...ev.data)
        } else if (ev.event === 'done') {
          clearInterval(timer)
          timer = null
          const final = JSON.parse(ev.data)     // 完整消息（含数据库 id）
          if (aiIndex >= 0) {
            messages.value[aiIndex] = final     // 用正式消息替换占位气泡
          } else {
            messages.value.push(final)
          }
          aiIndex = -1
          scrollToBottom()
        } else if (ev.event === 'error') {
          clearInterval(timer)
          timer = null
          if (aiIndex < 0) startAiBubble()
          messages.value[aiIndex].content = ev.data   // 把错误提示显示在气泡里
          scrollToBottom()
        }
      }
    }
  } catch (e) {
    clearInterval(timer)
    timer = null
    if (aiIndex >= 0) messages.value[aiIndex].content = '发送失败：' + e.message
    ElMessage.error(e.message)
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

// 把一段 SSE 文本解析成 { event, data }，没有 data 就返回 null
function parseSse(frame) {
  let event = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

// 游标分页：拿当前最早一条消息的 id 当游标，要更老的一批
async function loadOlder() {
  if (messages.value.length === 0) return
  const oldestId = messages.value[0].id
  try {
    const older = await api.get(`/v1/conversations/${conversationId.value}/messages`, {
      params: { lastId: oldestId, size: 20 }
    })
    if (older.length === 0) {
      ElMessage.info('没有更早的消息了')
      return
    }
    messages.value = [...older.reverse(), ...messages.value]
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function senderName(type) {
  return { VISITOR: '我', AI: 'AI 客服', AGENT: '坐席', SYSTEM: '系统' }[type] || type
}
</script>

<style scoped>
.chat-container {
  max-width: 480px;
  margin: 40px auto;
  height: 80vh;
  display: flex;
  flex-direction: column;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  overflow: hidden;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #eee;
}
.header h3 { margin: 0; }
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fa;
}
.bubble { margin-bottom: 12px; max-width: 80%; }
.bubble.right { margin-left: auto; }
.bubble .meta { font-size: 12px; color: #999; margin-bottom: 4px; }
.bubble.right .meta { text-align: right; }
.bubble .content {
  padding: 10px 14px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,.06);
  word-break: break-word;
  white-space: pre-wrap;
}
.bubble.right .content { background: #409eff; color: #fff; }
.input-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;      /* 按钮贴着多行输入框底部 */
  padding: 12px 16px;
  border-top: 1px solid #eee;
  background: #fff;
}
/* 输入框占满剩余宽度（Element Plus 内部元素，需要 :deep 穿透作用域） */
.input-bar :deep(.el-input),
.input-bar :deep(.el-textarea) {
  flex: 1;
}
.input-bar :deep(.el-textarea__inner) {
  box-shadow: none;
}
.input-bar .el-button {
  flex-shrink: 0;
}
</style>
