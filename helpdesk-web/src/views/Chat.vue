<template>
  <div class="chat-container">
    <div class="header">
      <div class="header-left">
        <h3>AI 客服</h3>
        <!-- ★ 人工模式标识：一旦转人工就常驻显示，不再靠"每发一条消息刷一个气泡"来表达 -->
        <el-tag v-if="humanMode" size="small" type="warning">人工服务中</el-tag>
      </div>
      <div class="header-right">
        <!-- ★ W6-4：实时通道状态。坐席的消息靠这条 SSE 长连接推过来，
             连不上时这里会变灰 —— 一眼就能判断"为什么收不到坐席回复" -->
        <span :class="['live-dot', live ? 'on' : 'off']"></span>
        <span class="live-text">{{ live ? '已连接' : '未连接' }}</span>
        <!-- ★ 新对话：换一个全新会话。这是转人工之后唯一能回到 AI 的出口（见 newConversation 的说明） -->
        <el-button size="small" text @click="newConversation">新对话</el-button>
        <el-button size="small" text @click="loadOlder">更早消息</el-button>
      </div>
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

      <!-- ★ 人工模式的底部常驻区：四种状态互斥，永远只占一行。
           它和聊天气泡的区别是"它不是一个条消息" —— 发多少条消息都只有这一块，
           绝不会像气泡那样越堆越多（这就是把 waiting 当"状态"而非"消息"的落地效果） -->
      <div v-if="humanMode" class="bottom-hint">
        <!-- ① 坐席还没开口：等待提示 -->
        <div v-if="!hasAgentMessage" class="waiting-hint">人工客服接入中，坐席会尽快回复</div>

        <!-- ② 用户已反馈：静态文案 -->
        <div v-else-if="resolvedFeedback !== null" class="waiting-hint">{{ feedbackHint }}</div>

        <!-- ③ 坐席已回复、且双方都安静够久了：弹询问卡片 -->
        <div v-else-if="showFeedbackCard" class="feedback-card">
          <span>请问您的问题解决了吗？</span>
          <div class="feedback-actions">
            <el-button size="small" type="success" :loading="feedbackSubmitting"
                       @click="submitFeedback(true)">已解决</el-button>
            <el-button size="small" :disabled="feedbackSubmitting"
                       @click="submitFeedback(false)">还没解决</el-button>
          </div>
        </div>

        <!-- ④ 坐席已回复、但还没安静够：轻量占位，避免卡片突然冒出来显得突兀 -->
        <div v-else class="waiting-hint">人工客服正在为您服务</div>
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
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

// ★ W6-4：访客的"实时通道"。普通消息走 POST + fetch 流（一问一答），
//   但坐席的消息是"服务器主动推过来的"——这种方向必须用 EventSource（浏览器内置的 SSE 客户端）。
let eventSource = null
const live = ref(false)   // 通道是否连着（显示在标题栏，方便判断"为什么收不到坐席回复"）

// ★ 会话是否已转人工。这是一个"状态"，不是"一条消息" ——
//   所以它只驱动标题栏的常驻标识，绝不再插成聊天气泡（见 waiting 分支的说明）
const humanMode = ref(false)

// ★ 用户对"问题解决了吗"的反馈，三态（和后端 conversation.resolved 字段一一对应）：
//   null = 还没问/还没答（卡片该弹） | 1 = 已解决 | 0 = 还没解决
//   注意：它只是"反馈记录"，**完全不改会话状态**，人工模式照旧
const resolvedFeedback = ref(null)
const feedbackSubmitting = ref(false)   // 防止连点

// 坐席是否已经开口。用来决定底部区域显示"等待中"还是"询问卡片" ——
// 坐席一句话都还没说就问"解决了吗"会很奇怪
const hasAgentMessage = computed(() => messages.value.some((m) => m.senderType === 'AGENT'))

// 答完之后底部显示的静态文案（和后端插进会话的那条 SYSTEM 消息保持一致）
const feedbackHint = computed(() => {
  if (resolvedFeedback.value === 1) return '感谢您的反馈，本会话已结束'
  if (resolvedFeedback.value === 0) return '收到，我们会继续为您跟进'
  return ''
})

// ===== ★ 静默计时：双方都停止发言一段时间后，才弹"解决了吗" =====
// 用**本地时钟**而不是消息里的 createdAt 来计时：
//   后端返回的是 LocalDateTime（"2026-09-30T21:12:33"，没有时区），
//   拿来算时间差容易踩时区的坑；而"距离上次消息过了多久"本来就是我们这台机器的感受。
const IDLE_MS = 30 * 1000   // 静默阈值。想快速看效果就调成 5*1000

let tickTimer = null                       // 每秒跳一次的钟
const nowTick = ref(Date.now())            // 当前时间（每秒更新，驱动下面那个 computed 重算）
let lastActivityAt = Date.now()            // 最后一次"有人发言"的时刻（本地时钟）

// 消息列表末尾一换（= 有新消息插进来了），就把计时清零。
// 为什么盯"最后一条的 id"而不是"长度"：往上翻历史会改变长度（loadOlder 是前插），
// 但那不算"有人发言"，不该重置计时。
watch(
  () => {
    const last = messages.value[messages.value.length - 1]
    return last ? last.id : null
  },
  () => { lastActivityAt = Date.now() }
)

// 卡片是否该出现。四个条件缺一不可，否则会乱弹：
//   ① 已转人工  ② 用户还没反馈  ③ 最后一句是坐席说的  ④ 安静够久了
// ③ 是关键：如果访客刚问了个新问题、坐席还没回，这时候问"解决了吗"很莫名
const showFeedbackCard = computed(() => {
  if (!humanMode.value) return false
  if (resolvedFeedback.value !== null) return false
  const last = messages.value[messages.value.length - 1]
  if (!last || last.senderType !== 'AGENT') return false
  return nowTick.value - lastActivityAt >= IDLE_MS
})

// ★ W5-③：哪些引用被展开了，key 形如 "消息id-第几条"
const expandedRefs = ref({})

// 组件卸载时回收资源（切路由、关页面都会触发）
onUnmounted(() => {
  stopTyping()
  closeLive()   // ★ W6-4：关掉 SSE 长连接，否则切走之后连接还挂在后端登记表里
  if (tickTimer) {
    clearInterval(tickTimer)   // ★ 静默计时的钟也得停，否则它在后台每秒跳个不停
    tickTimer = null
  }
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

    // ★ 刷新后从历史推导"是否已在人工模式"：只要历史里有坐席发过言，就一定转过人工了。
    //   这是**语义判断**（坐席开口 = 人工已介入），不是去匹配某句文案 ——
    //   文案会改，语义不会。这样就不用为"查会话状态"再加一个接口。
    if (messages.value.some((m) => m.senderType === 'AGENT')) {
      humanMode.value = true
    }
    // ★ W6-4：必须在"历史赋值"之后再连实时通道。
    //   若反过来先连，推送来的消息会被上面那句 messages.value = ... 整段覆盖掉
    //   （赋值是"替换整个数组"，不是追加）
    openLive(conversationId.value)
  } catch (e) {
    ElMessage.error(e.message)
  }

  // ★ 每秒跳一下钟：驱动 showFeedbackCard 重算，判断"双方已经安静多久了"。
  //   只更新一个时间戳、不碰消息列表，开销可以忽略。
  nowTick.value = Date.now()
  tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
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
        } else if (ev.event === 'waiting') {
          // ★ 人工模式。注意后端是**每收到一条消息**都会回这个事件
          //   （因为那个人工分支里根本没调模型，"没人答"这个事实每条消息都成立）。
          //   所以它的语义是【状态】而不是【一条提示消息】：
          //     · 只点亮标题栏的"人工客服接入中"标识（常驻、不刷屏）
          //     · 绝不插成聊天气泡 —— 否则每发一条就刷一条"已转接人工客服"
          //   "已为您转接人工客服，请稍后"那句一次性提示由后端的 SYSTEM 消息负责
          //   （它落库了，只出现一次，刷新页面也还在）
          stopTyping()
          humanMode.value = true
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

// ★ W6-4：连上访客实时通道（坐席消息的下发通道）
function openLive(cid) {
  closeLive()   // 先关掉可能存在的旧连接，避免留下野连接
  eventSource = new EventSource(`/api/v1/conversations/${cid}/stream`)

  // 后端建好连接后立刻回一个 connected 事件（相当于"握手成功"）
  eventSource.addEventListener('connected', () => {
    live.value = true
  })

  // 坐席回复：后端 registry.push(id, "agent", 消息)
  eventSource.addEventListener('agent', (e) => {
    appendIncoming(e.data)
  })

  // 系统提示，比如接管时的"客服已接入，正在为您服务"
  eventSource.addEventListener('system', (e) => {
    appendIncoming(e.data)
  })

  // 断线时浏览器会自动重连（EventSource 内置行为，不用自己写重试）。
  // 这里只更新状态、不弹提示 —— 否则后端重启那几秒会疯狂弹红条。
  eventSource.onerror = () => {
    live.value = false
  }
}

function closeLive() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  live.value = false
}

// 把服务端推来的消息追加到列表
// 按 id 去重：EventSource 重连、或后端重复推送时，不会出现两条一样的消息
function appendIncoming(raw) {
  let msg
  try {
    msg = JSON.parse(raw)
  } catch (e) {
    return
  }
  if (msg && msg.id && messages.value.some((m) => m.id === msg.id)) return
  // 坐席真的开口了 —— 那这个会话一定在人工模式（顺手把标识点亮）
  if (msg && msg.senderType === 'AGENT') humanMode.value = true
  messages.value.push(msg)
  scrollToBottom()
}

// ★ 提交"问题解决了吗"的反馈
// 后端这个接口用的是 @RequestParam（只有一个布尔值，不值得包一层 JSON body），
// 所以这里必须走 query 参数：axios 的第三个参数 { params } 而不是第二个参数当 body
async function submitFeedback(resolved) {
  if (feedbackSubmitting.value) return
  feedbackSubmitting.value = true
  try {
    await api.post(`/v1/conversations/${conversationId.value}/resolved`, null, {
      params: { resolved }
    })
    // 本地立刻记下"已反馈"，卡片当帧收起，不用等下一次刷新。
    // 后端对重复提交是幂等的（resolved 不为 null 就直接返回），所以就算刷新后卡片
    // 又弹一次、用户再点一遍，也不会重复流转工单 —— 这个取舍是刻意接受的。
    resolvedFeedback.value = resolved ? 1 : 0
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    feedbackSubmitting.value = false
  }
}

// ★ 新对话：换一个全新的会话
// 为什么必须有：会话 id 存在 localStorage 里被永久复用，而**会话状态一旦变成 AGENT 就再也回不去**
// （后端没有任何把它改回 AI 的代码）。也就是说访客被转人工之后，在这个浏览器里
// 之后发的每条消息都会走"人工分支"、拿不到 AI 回复 —— 新对话是唯一的出口。
async function newConversation() {
  try {
    await ElMessageBox.confirm(
      '将开启一个全新的会话，AI 会重新接待你。当前会话的记录不会丢失（坐席后台仍可查看）。',
      '开启新对话',
      { confirmButtonText: '开启', cancelButtonText: '取消' }
    )
  } catch (e) {
    return   // 点了取消。注意 ElMessageBox 取消时 reject 的是字符串 'cancel'，不是 Error
  }

  try {
    // 1. 先断开旧会话的实时通道 —— EventSource 的地址里带着 conversationId，
    //    不先关掉的话新会话的坐席消息永远推不过来（旧连接还挂在后端登记表里）
    closeLive()
    // 2. 建新会话（后端会同时建一个新的访客）
    const cid = await api.post('/v1/conversations')
    localStorage.setItem('conversationId', cid)
    conversationId.value = Number(cid)
    // 3. 把上一场的残留状态全部清干净
    messages.value = []
    expandedRefs.value = {}
    charQueue = []
    aiIndex = -1
    pendingReferences = []
    humanMode.value = false
    resolvedFeedback.value = null      // 新会话 = 还没反馈过
    feedbackSubmitting.value = false
    stopTyping()
    if (abortController) {
      abortController.abort()   // 万一还有半截流没读完
      abortController = null
    }
    // 4. 新会话是空的，不用拉历史，直接连新的实时通道
    openLive(conversationId.value)
    ElMessage.success('已开启新对话')
  } catch (e) {
    ElMessage.error(e.message)
  }
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
.header h3 { margin: 0; font-size: 16px; }
.header-left { display: flex; align-items: center; gap: 8px; }
/* ★ W6-4：实时通道状态指示 */
.header-right { display: flex; align-items: center; gap: 6px; }
.live-text { font-size: 12px; color: #999; }
.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
}
.live-dot.on { background: #67c23a; }   /* 连上 = 绿 */
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fa;
}
/* ★ 人工模式的底部常驻区：居中、不抢眼 */
.bottom-hint { padding: 4px 0; }
.waiting-hint {
  text-align: center;
  font-size: 12px;
  color: #b0b8c4;
  padding: 6px 0;
}
/* 询问卡片：贴在对话下方，一眼能答，不打断浏览 */
.feedback-card {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  font-size: 13px;
  color: #606266;
  box-shadow: 0 1px 2px rgba(0, 0, 0, .05);
}
.feedback-actions { display: flex; gap: 8px; }
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
