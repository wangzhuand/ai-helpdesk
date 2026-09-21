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

        <!-- ★ W5-③：AI 回答的引用来源。点击文档名展开/收起内容片段
             （改成点击而不是悬停：不依赖弹层组件，稳定且手机上也能用） -->
        <div v-if="msg.senderType !== 'VISITOR' && dedupRefs(msg).length" class="refs">
          <div class="refs-label">参考来源（点击展开片段）</div>
          <div v-for="(r, i) in dedupRefs(msg)" :key="i">
            <span class="ref-item" @click="toggleRef(msg.id, i)">《{{ r.docTitle }}》</span>
            <!-- 兜底：万一后端发的是全文（content）而不是截断片段（snippet），也照样能显示 -->
            <div v-if="expandedRefs[msg.id + '-' + i]" class="ref-snippet">{{ r.snippet || r.content || '（这一条没有片段）' }}</div>
          </div>
        </div>
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
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const messages = ref([])
const text = ref('')
const conversationId = ref(null)
const listRef = ref(null)

// 流式播放状态：从 send() 内部提升到组件作用域。
// 为什么：原来是 send() 的局部变量，组件卸载时外部拿不到它们，
// 于是"每 15ms 一次的定时器"和"fetch 的读取循环"会变成没人回收的孤儿。
let timer = null            // 打字机节奏器
let charQueue = []          // 待播放的字符队列
let aiIndex = -1            // AI 气泡在 messages 里的下标（-1 = 还没插）
let abortController = null  // 用来掐断正在进行的流式请求
let pendingReferences = []  // ★ W5-③：本次回答的引用来源（references 事件先到，气泡后出现，所以要先暂存）

// ★ W5-③：哪些引用被展开了，key 形如 "消息id-第几条"
const expandedRefs = ref({})

// 组件卸载时回收资源（切路由、关页面都会触发）
onUnmounted(() => {
  stopTyping()
  if (abortController) {
    abortController.abort()   // 掐断 fetch；后端感知到断开后会取消 LLM 调用
    abortController = null
  }
})

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

// 统一收尾，免得在 done / error / catch 三个分支里重复写 clearInterval
function stopTyping() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

// ★ W5-③：同一个文档的多个块只保留一条引用。
// 用户关心的是"参考了哪几篇文档"，不是"哪几个块"；
// 3 个块恰好来自同一篇时，原来会显示 3 条一样的文档名。
function dedupRefs(msg) {
  const refs = (msg && msg.references) ? msg.references : []
  const seen = new Set()
  const out = []
  for (const r of refs) {
    const key = r.documentId + '#' + r.docTitle
    if (seen.has(key)) continue
    seen.add(key)
    out.push(r)
  }
  return out
}

// ★ W5-③：展开/收起某一条引用的片段
function toggleRef(msgId, i) {
  const key = msgId + '-' + i
  expandedRefs.value[key] = !expandedRefs.value[key]
}

// 插入 AI 占位气泡并启动节奏器（每 15ms 吐一个字）
function startAiBubble() {
  messages.value.push({
    id: 'temp-' + Date.now(),
    senderType: 'AI',
    content: '正在输入…',
    createdAt: '',
    references: pendingReferences   // ★ W5-③：把已经收到的引用挂到气泡上
  })
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

async function send() {
  const content = text.value.trim()
  if (!content || sending.value) return
  text.value = ''
  sending.value = true

  // 每次发送前重置播放状态（以前是局部变量，天然是干净的，现在得手动重置）
  charQueue = []
  aiIndex = -1
  pendingReferences = []
  stopTyping()
  abortController = new AbortController()

  try {
    // 用 fetch 发 POST 并读取流式响应（axios 不适合流式，绕开它）
    const resp = await fetch(`/api/v1/conversations/${conversationId.value}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: content }),
      signal: abortController.signal
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
        } else if (ev.event === 'references') {
          // ★ W5-③：引用来源。它比第一个 token 先到，所以先暂存，
          //   等 AI 气泡出现时再挂上去（见 startAiBubble）
          pendingReferences = JSON.parse(ev.data) || []
        } else if (ev.event === 'token') {
          // ② 第一个 token 来时插入 AI 气泡（在"我"的消息下方），字入队
          if (aiIndex < 0) startAiBubble()
          charQueue.push(...ev.data)
        } else if (ev.event === 'done') {
          stopTyping()
          const final = JSON.parse(ev.data)     // 完整消息（含数据库 id）
          // ★ W5-③：后端发来的最终消息里没有引用，替换气泡时要把引用补上
          final.references = pendingReferences
          if (aiIndex >= 0) {
            messages.value[aiIndex] = final     // 用正式消息替换占位气泡
          } else {
            messages.value.push(final)
          }
          aiIndex = -1
          scrollToBottom()
        } else if (ev.event === 'error') {
          stopTyping()
          if (aiIndex < 0) startAiBubble()
          messages.value[aiIndex].content = ev.data   // 把错误提示显示在气泡里
          scrollToBottom()
        }
      }
    }
  } catch (e) {
    stopTyping()
    // 组件卸载导致的主动中断不是错误，不弹提示（否则切路由时会闪一个红条）
    if (e.name === 'AbortError') return
    if (aiIndex >= 0) messages.value[aiIndex].content = '发送失败：' + e.message
    ElMessage.error(e.message)
  } finally {
    sending.value = false
    abortController = null
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
/* ★ W5-③：引用来源样式 */
.bubble .refs {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.9;
}
.bubble .refs-label { color: #b0b8c4; }
.bubble .ref-item {
  color: #6b7f9e;
  cursor: pointer;
  border-bottom: 1px dashed #c8d3e0;
}
.bubble .ref-item:hover { color: #409eff; }
.bubble .ref-snippet {
  margin: 4px 0 6px 0;
  padding: 6px 8px;
  background: #f2f5f9;
  border-left: 2px solid #cdd9e8;
  border-radius: 4px;
  color: #667;
  line-height: 1.7;
  word-break: break-word;
}
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
