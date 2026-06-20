<template>
  <main class="app-shell">
    <header class="top-bar">
      <div class="brand">
        <div class="brand-mark">RAG</div>
        <div>
          <p class="eyebrow">Enterprise Knowledge Base</p>
          <h1>企业文档问答平台</h1>
        </div>
      </div>
      <div class="mode-switch">
        <button :class="{ active: mode === 'user' }" @click="mode = 'user'">用户端</button>
        <button :class="{ active: mode === 'admin' }" @click="mode = 'admin'">管理端</button>
      </div>
    </header>

    <section v-if="mode === 'user'" class="user-layout">
      <aside class="side-panel">
        <section class="panel-block">
          <div class="section-title">
            <span>知识范围</span>
            <span class="status-dot"></span>
          </div>
          <label>
            <span>部门</span>
            <select v-model="departmentId" @change="onScopeChange">
              <option v-for="department in departments" :key="department.id" :value="department.id">
                {{ department.name }}
              </option>
            </select>
          </label>
          <label>
            <span>模块</span>
            <select v-model="moduleCode" @change="clearConversation">
              <option v-for="module in scopedModules" :key="module.code" :value="module.code">
                {{ module.name }}
              </option>
            </select>
          </label>
        </section>
      </aside>

      <section class="chat-panel">
        <header class="chat-header">
          <div>
            <p class="eyebrow">Ask with context</p>
            <h2>{{ currentDepartmentName }} / {{ currentModuleName }}</h2>
          </div>
          <button class="ghost-action" @click="clearConversation">清空会话</button>
        </header>

        <div ref="conversationRef" class="conversation">
          <div v-if="!messages.length" class="empty-state">
            <div class="assistant-avatar">AI</div>
            <h3>选择部门和模块后直接提问</h3>
            <p>用户端只保留知识范围切换和对话，回答会基于对应部门、模块的文档分片生成。</p>
            <div class="prompt-grid">
              <button v-for="prompt in examplePrompts" :key="prompt" @click="usePrompt(prompt)">
                {{ prompt }}
              </button>
            </div>
          </div>

          <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
            <div class="avatar">{{ message.role === 'user' ? '我' : 'AI' }}</div>

            <div class="bubble">
              <div v-if="message.role === 'assistant' && message.sources?.length" class="source-layer">
                <button
                  class="source-dot"
                  :class="{ active: openSourceMessageId === message.id }"
                  aria-label="show sources"
                  title="show sources"
                  type="button"
                  @click.stop="toggleSources(message.id)"
                ></button>
                <div v-if="openSourceMessageId === message.id" class="source-popover" @click.stop>
                  <div class="source-popover-title">Sources</div>
                  <details v-for="(source, index) in message.sources" :key="`${source.documentId}-${source.chunkIndex}-${index}`" class="source-row">
                    <summary>
                      <strong :title="source.fileName || 'Unknown source'">{{ source.fileName || 'Unknown source' }}</strong>
                      <span>Chunk {{ source.chunkIndex ?? '-' }}</span>
                      <span>Score {{ formatScore(source.score) }}</span>
                    </summary>
                    <p class="source-content">{{ source.content || 'No chunk content.' }}</p>
                  </details>
                </div>
              </div>
              <div class="message-meta">
                <strong>{{ message.role === 'user' ? '我' : '文档助手' }}</strong>
                <span>{{ message.time }}</span>
              </div>
              <p>{{ message.content }}</p>
            </div>
          </article>

          <article v-if="streaming" class="message assistant">
            <div class="avatar">AI</div>
            <div class="bubble">
              <div class="typing"><span></span><span></span><span></span></div>
            </div>
          </article>
        </div>

        <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>

        <form class="composer" @submit.prevent="streamChat">
          <textarea
            v-model.trim="question"
            placeholder="输入你的问题"
            rows="1"
            @keydown.enter.exact.prevent="streamChat"
          ></textarea>
          <button class="send-action" :disabled="streaming || !question">发送</button>
        </form>
      </section>
    </section>

    <section v-else class="admin-layout">
      <section class="admin-main">
        <section class="toolbar-panel">
          <div>
            <p class="eyebrow">Document Admin</p>
            <h2>文档列表与分片结果</h2>
          </div>
          <div class="admin-nav">
            <button :class="{ active: adminPage === 'documents' }" @click="setAdminPage('documents')">首页</button>
            <button :class="{ active: adminPage === 'departments' }" @click="setAdminPage('departments')">部门管理</button>
            <button :class="{ active: adminPage === 'modules' }" @click="setAdminPage('modules')">模块管理</button>
            <button :class="{ active: adminPage === 'fileUpload' }" @click="setAdminPage('fileUpload')">文件上传</button>
            <button :class="{ active: adminPage === 'manualUpload' }" @click="setAdminPage('manualUpload')">手动上传</button>
          </div>
        </section>

        <section v-if="adminPage === 'documents'" class="admin-page document-home">
          <section class="scope-panel">
            <div>
              <p class="eyebrow">Current Scope</p>
              <h3>{{ currentDepartmentName }} / {{ currentModuleName }}</h3>
            </div>
            <div class="scope-row">
              <select v-model="departmentId" @change="onScopeChange">
                <option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}</option>
              </select>
              <select v-model="moduleCode" @change="loadDocuments">
                <option v-for="module in scopedModules" :key="module.code" :value="module.code">{{ module.name }}</option>
              </select>
            </div>
          </section>

          <p v-if="uploadResult" class="success-note">
            已入库 {{ uploadResult.fileName }}，生成 {{ uploadResult.chunkCount }} 个分片。
          </p>
          <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>

          <section class="document-grid">
            <aside class="document-list">
              <div class="section-title">
                <span>文档列表</span>
                <button class="text-action" @click="loadDocuments">刷新</button>
              </div>
              <button
                v-for="document in documents"
                :key="document.documentId"
                :class="{ active: selectedDocumentId === document.documentId }"
                @click="selectDocument(document.documentId)"
              >
                <strong>{{ document.fileName }}</strong>
                <span>{{ document.chunkCount }} 个分片 · {{ formatDate(document.createdAt) }}</span>
              </button>
              <div v-if="!documents.length" class="empty-list">暂无文档</div>
            </aside>

            <section class="chunk-panel">
              <div class="section-title">
                <span>分片结果</span>
                <button class="danger-action" :disabled="!selectedDocumentId" @click="deleteSelectedDocument">删除文档</button>
              </div>
              <div v-if="selectedDocumentId" class="add-chunk-card">
                <div class="chunk-head">
                  <strong>新增分片</strong>
                  <button class="primary-action" :disabled="uploading || !newChunkContent.trim()" @click="addChunkToSelectedDocument">
                    {{ uploading ? '正在入库...' : '新增并入库' }}
                  </button>
                </div>
                <textarea v-model="newChunkContent" rows="4" placeholder="输入要追加到当前文档的新分片内容"></textarea>
              </div>
              <article v-for="chunk in chunks" :key="chunk.chunkId" class="chunk-card">
                <div class="chunk-head">
                  <strong>Chunk {{ chunk.chunkIndex }}</strong>
                  <select v-model="chunk.status">
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="DISABLED">DISABLED</option>
                  </select>
                </div>
                <textarea v-model="chunk.content" rows="6"></textarea>
                <div class="chunk-actions">
                  <span>{{ chunk.updatedAt ? formatDate(chunk.updatedAt) : '-' }}</span>
                  <div class="chunk-action-buttons">
                    <button class="row-delete" @click="deleteChunk(chunk)">删除分片</button>
                    <button class="primary-action" @click="saveChunk(chunk)">保存分片</button>
                  </div>
                </div>
              </article>
              <div v-if="selectedDocumentId && !chunks.length" class="empty-list">该文档暂无分片</div>
              <div v-if="!selectedDocumentId" class="empty-list">请选择左侧文档查看分片</div>
            </section>
          </section>
        </section>

        <section v-else-if="adminPage === 'departments'" class="admin-page management-page">
          <section class="management-summary">
            <div class="summary-tile">
              <span>部门总数</span>
              <strong>{{ departments.length }}</strong>
            </div>
            <div class="summary-tile">
              <span>模块总数</span>
              <strong>{{ modules.length }}</strong>
            </div>
            <div class="summary-tile">
              <span>当前范围</span>
              <strong>{{ currentDepartmentName }}</strong>
            </div>
          </section>

          <section class="management-body">
            <section class="management-list">
              <div class="management-heading">
                <div>
                  <p class="eyebrow">Catalog</p>
                  <h3>已有部门</h3>
                </div>
                <div class="management-heading-actions">
                  <span class="count-badge">{{ departments.length }} 项</span>
                  <button class="primary-action" type="button" @click="openNewDepartmentDialog">新建部门</button>
                </div>
              </div>
              <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
              <div class="management-table department-table">
                <div class="management-row table-head">
                  <span>部门</span>
                  <span>编码</span>
                  <span>模块</span>
                  <span>操作</span>
                </div>
                <div v-for="department in departments" :key="department.id" class="management-row">
                  <button class="entity-main" type="button" @click="editDepartment(department)">
                    <span class="entity-icon">部</span>
                    <span class="entity-copy">
                      <strong>{{ department.name }}</strong>
                      <small>{{ department.description || '暂无说明' }}</small>
                    </span>
                  </button>
                  <code>{{ department.id }}</code>
                  <span class="count-badge">{{ departmentModuleCount(department.id) }} 个模块</span>
                  <button class="row-delete" type="button" @click="deleteDepartment(department.id)">删除</button>
                </div>
                <div v-if="!departments.length" class="empty-list">暂无部门</div>
              </div>
            </section>
          </section>
        </section>

        <section v-else-if="adminPage === 'modules'" class="admin-page management-page">
          <section class="management-summary">
            <div class="summary-tile">
              <span>模块总数</span>
              <strong>{{ modules.length }}</strong>
            </div>
            <div class="summary-tile">
              <span>覆盖部门</span>
              <strong>{{ departmentsWithModules }}</strong>
            </div>
            <div class="summary-tile">
              <span>当前模块</span>
              <strong>{{ currentModuleName }}</strong>
            </div>
          </section>

          <section class="management-body">
            <section class="management-list">
              <div class="management-heading">
                <div>
                  <p class="eyebrow">Catalog</p>
                  <h3>已有模块</h3>
                </div>
                <div class="management-heading-actions">
                  <span class="count-badge">{{ modules.length }} 项</span>
                  <button class="primary-action" type="button" @click="openNewModuleDialog">新建模块</button>
                </div>
              </div>
              <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
              <div class="management-table module-table">
                <div class="management-row table-head">
                  <span>模块</span>
                  <span>所属部门</span>
                  <span>编码</span>
                  <span>操作</span>
                </div>
                <div v-for="module in modules" :key="`${module.departmentId}-${module.code}`" class="management-row">
                  <button class="entity-main" type="button" @click="editModule(module)">
                    <span class="entity-icon module-icon">模</span>
                    <span class="entity-copy">
                      <strong>{{ module.name }}</strong>
                      <small>{{ module.description || '暂无说明' }}</small>
                    </span>
                  </button>
                  <span class="department-pill">{{ departmentNameById(module.departmentId) }}</span>
                  <code>{{ module.code }}</code>
                  <button class="row-delete" type="button" @click="deleteModule(module)">删除</button>
                </div>
                <div v-if="!modules.length" class="empty-list">暂无模块</div>
              </div>
            </section>
          </section>
        </section>

        <section v-else-if="adminPage === 'fileUpload'" class="admin-page upload-page">
          <section class="scope-panel">
            <div>
              <p class="eyebrow">File Upload</p>
              <h3>文件上传</h3>
            </div>
            <div class="scope-row">
              <select v-model="departmentId" @change="onScopeChange">
                <option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}</option>
              </select>
              <select v-model="moduleCode" @change="loadDocuments">
                <option v-for="module in scopedModules" :key="module.code" :value="module.code">{{ module.name }}</option>
              </select>
            </div>
          </section>

          <label class="file-drop">
            <input type="file" @change="onFileChange" />
            <span class="file-icon">DOC</span>
            <span class="file-copy">
              <strong>{{ selectedFileName || '选择文档上传' }}</strong>
              <small>上传后自动切分并写入向量库，可回到首页查看和修改分片内容。</small>
            </span>
          </label>
          <div class="page-actions">
            <button class="ghost-action" @click="setAdminPage('documents')">返回首页</button>
            <button class="primary-action" :disabled="!file || uploading" @click="uploadDocument">
              {{ uploading ? '正在上传...' : '上传并入库' }}
            </button>
          </div>
          <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
        </section>

        <section v-else-if="adminPage === 'manualUpload'" class="admin-page upload-page">
          <section class="scope-panel">
            <div>
              <p class="eyebrow">Manual Upload</p>
              <h3>手动上传</h3>
            </div>
            <div class="scope-row">
              <select v-model="departmentId" @change="onScopeChange">
                <option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}</option>
              </select>
              <select v-model="moduleCode" @change="loadDocuments">
                <option v-for="module in scopedModules" :key="module.code" :value="module.code">{{ module.name }}</option>
              </select>
            </div>
          </section>

          <div class="manual-upload-form">
            <label>
              <span>文档名称</span>
              <input v-model.trim="manualDocument.fileName" placeholder="例如：客服质检规则" />
            </label>
            <div class="section-title">
              <span>自定义分片</span>
              <button class="text-action" @click="addManualChunk">添加分片</button>
            </div>
            <div class="manual-chunk-list">
              <div v-for="(chunk, index) in manualDocument.chunks" :key="index" class="manual-chunk-row">
                <div class="chunk-head">
                  <strong>Chunk {{ index }}</strong>
                  <button class="row-delete" :disabled="manualDocument.chunks.length === 1" @click="removeManualChunk(index)">删除</button>
                </div>
                <textarea v-model="manualDocument.chunks[index]" rows="4" placeholder="输入这一段要单独入库检索的内容"></textarea>
              </div>
            </div>
          </div>
          <div class="page-actions">
            <button class="ghost-action" @click="setAdminPage('documents')">返回首页</button>
            <button class="primary-action" :disabled="uploading || !canUploadManual" @click="uploadManualDocument">
              {{ uploading ? '正在入库...' : '创建并入库' }}
            </button>
          </div>
          <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
        </section>
      </section>
    </section>

    <div v-if="departmentDialogOpen" class="modal-backdrop" @click.self="closeDepartmentDialog">
      <form class="management-editor modal-panel" @submit.prevent="saveDepartment">
        <div class="management-heading">
          <div>
            <p class="eyebrow">Department</p>
            <h3>{{ departmentForm.id ? '编辑部门' : '新建部门' }}</h3>
          </div>
          <button class="ghost-action" type="button" @click="closeDepartmentDialog">关闭</button>
        </div>
        <label><span>部门编码</span><input v-model.trim="departmentForm.id" placeholder="hr" /></label>
        <label><span>部门名称</span><input v-model.trim="departmentForm.name" placeholder="人力资源部" /></label>
        <label><span>部门说明</span><textarea v-model.trim="departmentForm.description" rows="4" placeholder="填写该部门的知识范围或使用说明"></textarea></label>
        <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
        <div class="modal-actions">
          <button class="ghost-action" type="button" @click="resetDepartmentForm">清空</button>
          <button class="primary-action" type="submit" :disabled="!departmentForm.id || !departmentForm.name">保存部门</button>
        </div>
      </form>
    </div>

    <div v-if="moduleDialogOpen" class="modal-backdrop" @click.self="closeModuleDialog">
      <form class="management-editor modal-panel" @submit.prevent="saveModule">
        <div class="management-heading">
          <div>
            <p class="eyebrow">Module</p>
            <h3>{{ moduleForm.code ? '编辑模块' : '新建模块' }}</h3>
          </div>
          <button class="ghost-action" type="button" @click="closeModuleDialog">关闭</button>
        </div>
        <label>
          <span>所属部门</span>
          <select v-model="moduleForm.departmentId">
            <option v-for="department in departments" :key="department.id" :value="department.id">
              {{ department.name }}
            </option>
          </select>
        </label>
        <label><span>模块编码</span><input v-model.trim="moduleForm.code" placeholder="attendance" /></label>
        <label><span>模块名称</span><input v-model.trim="moduleForm.name" placeholder="考勤管理" /></label>
        <label><span>模块说明</span><textarea v-model.trim="moduleForm.description" rows="4" placeholder="填写模块对应的业务资料或检索范围"></textarea></label>
        <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
        <div class="modal-actions">
          <button class="ghost-action" type="button" @click="resetModuleForm">清空</button>
          <button class="primary-action" type="submit" :disabled="!moduleForm.departmentId || !moduleForm.code || !moduleForm.name">保存模块</button>
        </div>
      </form>
    </div>
  </main>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'

const API_BASE = 'http://localhost:8080'
const mode = ref('user')
const adminPage = ref('documents')
const departments = ref([])
const modules = ref([])
const departmentId = ref('hr')
const moduleCode = ref('attendance')
const departmentForm = ref({ id: '', name: '', description: '' })
const moduleForm = ref({ code: '', name: '', departmentId: 'hr', description: '' })
const file = ref(null)
const manualDocument = ref({ fileName: '', chunks: [''] })
const question = ref('')
const sessionId = ref(createSessionId())
const messages = ref([])
const documents = ref([])
const chunks = ref([])
const selectedDocumentId = ref('')
const newChunkContent = ref('')
const uploadResult = ref(null)
const uploading = ref(false)
const streaming = ref(false)
const errorMessage = ref('')
const conversationRef = ref(null)
const openSourceMessageId = ref('')
const departmentDialogOpen = ref(false)
const moduleDialogOpen = ref(false)

const examplePrompts = ['这个模块有哪些流程要求？', '请总结当前制度的关键点', '遇到异常情况应该怎么处理？']

const scopedModules = computed(() => modules.value.filter(module => module.departmentId === departmentId.value))
const selectedFileName = computed(() => file.value?.name || '')
const canUploadManual = computed(() => manualDocument.value.chunks.some(chunk => chunk.trim()))
const currentDepartmentName = computed(() => departments.value.find(item => item.id === departmentId.value)?.name || departmentId.value)
const currentModuleName = computed(() => scopedModules.value.find(item => item.code === moduleCode.value)?.name || moduleCode.value)
const departmentsWithModules = computed(() => new Set(modules.value.map(module => module.departmentId)).size)

watch(scopedModules, value => {
  if (value.length && !value.some(module => module.code === moduleCode.value)) {
    moduleCode.value = value[0].code
  }
})

onMounted(async () => {
  await loadCatalog()
  await loadDocuments()
})

async function api(path, options = {}) {
  const res = await fetch(`${API_BASE}${path}`, options)
  const text = await res.text()
  if (!res.ok) {
    throw new Error(text || `请求失败：${res.status}`)
  }
  return text ? JSON.parse(text) : null
}

async function loadCatalog() {
  departments.value = await api('/api/rag/departments')
  modules.value = await api('/api/rag/modules')
  if (departments.value.length && !departments.value.some(item => item.id === departmentId.value)) {
    departmentId.value = departments.value[0].id
  }
  if (scopedModules.value.length && !scopedModules.value.some(item => item.code === moduleCode.value)) {
    moduleCode.value = scopedModules.value[0].code
  }
  moduleForm.value.departmentId = departmentId.value
}

async function loadDocuments() {
  const params = new URLSearchParams({ departmentId: departmentId.value, moduleCode: moduleCode.value })
  documents.value = await api(`/api/rag/documents?${params}`)
  if (selectedDocumentId.value && !documents.value.some(item => item.documentId === selectedDocumentId.value)) {
    selectedDocumentId.value = ''
    chunks.value = []
  }
}

async function selectDocument(documentId) {
  selectedDocumentId.value = documentId
  newChunkContent.value = ''
  chunks.value = await api(`/api/rag/documents/${documentId}/chunks`)
}

function setAdminPage(page) {
  adminPage.value = page
  errorMessage.value = ''
  if (page === 'documents') loadDocuments()
}

function onScopeChange() {
  if (scopedModules.value.length) moduleCode.value = scopedModules.value[0].code
  clearConversation()
  loadDocuments()
}

function openNewDepartmentDialog() {
  resetDepartmentForm()
  departmentDialogOpen.value = true
}

function closeDepartmentDialog() {
  departmentDialogOpen.value = false
  errorMessage.value = ''
}

function editDepartment(department) {
  departmentForm.value = { ...department }
  departmentDialogOpen.value = true
  errorMessage.value = ''
}

function resetDepartmentForm() {
  departmentForm.value = { id: '', name: '', description: '' }
  errorMessage.value = ''
}

async function saveDepartment() {
  errorMessage.value = ''
  try {
    await api('/api/rag/departments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(departmentForm.value)
    })
    departmentForm.value = { id: '', name: '', description: '' }
    departmentDialogOpen.value = false
    await loadCatalog()
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function deleteDepartment(id) {
  errorMessage.value = ''
  try {
    await api(`/api/rag/departments/${id}`, { method: 'DELETE' })
    await loadCatalog()
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error.message
  }
}

function openNewModuleDialog() {
  resetModuleForm()
  moduleDialogOpen.value = true
}

function closeModuleDialog() {
  moduleDialogOpen.value = false
  errorMessage.value = ''
}

function editModule(module) {
  moduleForm.value = { ...module }
  moduleDialogOpen.value = true
  errorMessage.value = ''
}

function resetModuleForm() {
  moduleForm.value = { code: '', name: '', departmentId: departmentId.value, description: '' }
  errorMessage.value = ''
}

async function saveModule() {
  errorMessage.value = ''
  try {
    await api('/api/rag/modules', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(moduleForm.value)
    })
    moduleForm.value = { code: '', name: '', departmentId: departmentId.value, description: '' }
    moduleDialogOpen.value = false
    await loadCatalog()
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function deleteModule(module) {
  errorMessage.value = ''
  try {
    await api(`/api/rag/departments/${module.departmentId}/modules/${module.code}`, { method: 'DELETE' })
    await loadCatalog()
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error.message
  }
}

function onFileChange(event) {
  file.value = event.target.files?.[0] || null
  errorMessage.value = ''
}

async function uploadDocument() {
  if (!file.value) return
  uploading.value = true
  errorMessage.value = ''
  try {
    const form = new FormData()
    form.append('file', file.value)
    form.append('departmentId', departmentId.value)
    form.append('moduleCode', moduleCode.value)
    form.append('uploadedBy', 'admin')
    uploadResult.value = await api('/api/rag/documents', { method: 'POST', body: form })
    file.value = null
    await loadCatalog()
    await loadDocuments()
    await selectDocument(uploadResult.value.documentId)
    setAdminPage('documents')
  } catch (error) {
    errorMessage.value = error.message || '文档上传失败'
  } finally {
    uploading.value = false
  }
}

function addManualChunk() {
  manualDocument.value.chunks.push('')
}

function removeManualChunk(index) {
  if (manualDocument.value.chunks.length === 1) return
  manualDocument.value.chunks.splice(index, 1)
}

async function uploadManualDocument() {
  const manualChunks = manualDocument.value.chunks.map(chunk => chunk.trim()).filter(Boolean)
  if (!manualChunks.length) return
  uploading.value = true
  errorMessage.value = ''
  try {
    uploadResult.value = await api('/api/rag/documents/manual', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fileName: manualDocument.value.fileName || '手动创建文档',
        departmentId: departmentId.value,
        moduleCode: moduleCode.value,
        uploadedBy: 'admin',
        chunks: manualChunks
      })
    })
    manualDocument.value = { fileName: '', chunks: [''] }
    await loadCatalog()
    await loadDocuments()
    await selectDocument(uploadResult.value.documentId)
    setAdminPage('documents')
  } catch (error) {
    errorMessage.value = error.message || '手动文档入库失败'
  } finally {
    uploading.value = false
  }
}

async function saveChunk(chunk) {
  errorMessage.value = ''
  try {
    const updated = await api(`/api/rag/chunks/${chunk.chunkId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: chunk.content, status: chunk.status })
    })
    Object.assign(chunk, updated)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function addChunkToSelectedDocument() {
  const content = newChunkContent.value.trim()
  if (!selectedDocumentId.value || !content) return
  uploading.value = true
  errorMessage.value = ''
  try {
    const created = await api(`/api/rag/documents/${selectedDocumentId.value}/chunks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    })
    chunks.value.push(created)
    newChunkContent.value = ''
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error.message || '新增分片失败'
  } finally {
    uploading.value = false
  }
}

async function deleteChunk(chunk) {
  errorMessage.value = ''
  try {
    await api(`/api/rag/chunks/${chunk.chunkId}`, { method: 'DELETE' })
    chunks.value = chunks.value.filter(item => item.chunkId !== chunk.chunkId)
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error.message || '删除分片失败'
  }
}

async function deleteSelectedDocument() {
  if (!selectedDocumentId.value) return
  errorMessage.value = ''
  try {
    await api(`/api/rag/documents/${selectedDocumentId.value}`, { method: 'DELETE' })
    selectedDocumentId.value = ''
    chunks.value = []
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function streamChat() {
  const askedQuestion = question.value
  if (!askedQuestion || streaming.value) return
  openSourceMessageId.value = ''
  messages.value.push({ id: crypto.randomUUID(), role: 'user', content: askedQuestion, time: nowTime() })
  question.value = ''
  errorMessage.value = ''
  streaming.value = true
  await scrollToBottom()

  const assistantMessage = { id: crypto.randomUUID(), role: 'assistant', content: '', sources: [], time: nowTime() }
  messages.value.push(assistantMessage)

  try {
    const res = await fetch(`${API_BASE}/api/rag/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        departmentId: departmentId.value,
        moduleCode: moduleCode.value,
        sessionId: sessionId.value,
        question: askedQuestion
      })
    })
    if (!res.ok) throw new Error(`生成失败：${res.status}`)
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const events = buffer.split('\n\n')
      buffer = events.pop() || ''
      for (const eventText of events) handleSseEvent(eventText, assistantMessage)
      await scrollToBottom()
    }
    if (!assistantMessage.content) assistantMessage.content = '没有收到可展示的回答。'
  } catch (error) {
    assistantMessage.content = '生成回答失败，请确认后端服务和模型配置可用。'
    errorMessage.value = error.message
  } finally {
    streaming.value = false
    await scrollToBottom()
  }
}

function handleSseEvent(eventText, assistantMessage) {
  const lines = eventText.split('\n')
  let event = 'message'
  let data = ''
  for (const line of lines) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data += line.slice(5)
  }
  if (event === 'done' || data === '[DONE]') return
  if (event === 'sources') {
    try {
      assistantMessage.sources = JSON.parse(data)
    } catch {
      assistantMessage.sources = []
    }
    return
  }
  assistantMessage.content += data
}

function clearConversation() {
  sessionId.value = createSessionId()
  messages.value = []
  errorMessage.value = ''
  openSourceMessageId.value = ''
}

function createSessionId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function toggleSources(messageId) {
  openSourceMessageId.value = openSourceMessageId.value === messageId ? '' : messageId
}

function usePrompt(prompt) {
  question.value = prompt
}

function departmentModuleCount(id) {
  return modules.value.filter(module => module.departmentId === id).length
}

function departmentNameById(id) {
  return departments.value.find(department => department.id === id)?.name || id
}

function nowTime() {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())
}

function formatDate(value) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

function formatScore(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '-'
  return Number(value).toFixed(3)
}

async function scrollToBottom() {
  await nextTick()
  if (conversationRef.value) conversationRef.value.scrollTop = conversationRef.value.scrollHeight
}
</script>
