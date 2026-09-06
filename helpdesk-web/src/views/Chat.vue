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
      <el-input v-model="text" placeholder="输入消息，回车发送" @keyup.enter="send" />
      <el-button type="primary" @click="send">发送</el-button>
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
  } catch (e) {
    ElMessage.error(e.message)
  }
})

async function send() {
  const content = text.value.trim()
  if (!content) return
  text.value = ''
  try {
    const saved = await api.post(`/v1/conversations/${conversationId.value}/messages`, { message: content })
    messages.value.push(saved)
    await nextTick()
    listRef.value.scrollTop = listRef.value.scrollHeight
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
}
.bubble.right .content { background: #409eff; color: #fff; }
.input-bar {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #eee;
  background: #fff;
}
</style>
