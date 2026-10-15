# 农场应用内悬浮 AI 助手实施计划

> 状态：待用户评审。本文只描述实施方案，尚未开始代码实现。

## Context

智禾农场已有完整的“农场 AI 助手”页面和稳定的 SSE 对话链路，但用户必须先进入完整页面才能提问。新增“小禾”悬浮助手，让用户在今日农场、运行概览、设备台账等工作页面中直接咨询，同时继续沿用现有的农场租户边界、只读工具、规则回退、活动摘要和灌溉人工确认流程。

## 已确认的产品决定

- 每个登录账号、每座当前农场复用一条 floating 快捷会话；首次真正发送才创建。
- floating 会话进入完整 AI 页历史列表，带“小窗”来源标记，可在完整页继续管理。
- 桌面是右下角非模态浮窗，手机是底部 sheet。
- 只响应点击，不自动弹出。
- 每次发送默认使用服务端按 `pageId + focusType + focusId` 解析的页面白名单快照，并展示本次参考资料范围。
- 小窗只提供只读农事咨询、复制、朗读、打开完整 AI 和跳转灌溉管理；不会在聊天中批准或控制设备。

## 实施阶段

### Phase 1：共享 AI 状态边界

**目标**：让完整 AI 页和小窗使用同一套运行状态。

**涉及文件**：

- 新增 `frontend/src/ai/ai-assistant-controller.js`
- 新增 `frontend/src/ai/AiConversationView.vue`
- 修改 `frontend/src/ai/FarmAssistant.vue`
- 修改 `frontend/src/ai/agent-adapter.js`
- 修改 `frontend/src/ai/agent-events.js`
- 复用 `AssistantText.vue`、`AiActivityDisclosure.vue`、`AiStreamingStatus.vue`、`AiMessageActions.vue`、`speech.js`

**关键行为**：

- 一个根级 controller 管理 conversationId、消息、run、AbortController、requestId、重试和朗读。
- compact view 不展示历史栏、分支和完整页签；full view 保留现有能力。
- 当前运行的消息增量和活动在两种 view 中保持一致。
- 农场切换/登出 abort、清空草稿和运行；页面切换只改变下一次发送的 page context。

### Phase 2：floating 壳层与响应式交互

**目标**：交付可访问的桌面浮窗和移动底板。

**涉及文件**：

- 新增 `frontend/src/ai/FloatingAssistant.vue`
- 新增 `frontend/src/ai/floating-assistant.css`
- 新增或拆分 `frontend/src/ai/SproutMark.vue`
- 修改 `frontend/src/App.vue`
- 修改 `frontend/src/ui/AppIcon.vue`（仅在缺少必要线性图标时）
- 修改主题/共享样式（只增加语义 token，不写组件专属色值）

**桌面**：入口固定右下角，面板约 408px 宽、最大高度 640px；无 backdrop、无 modal 锁定，点击外部或 Escape 收起。

**移动**：入口避开底部 tabbar；打开后为全宽 bottom sheet，带 backdrop、safe-area、独立滚动、背景滚动锁和软键盘 `visualViewport` 调整。

**可访问性**：保存 opener 焦点；打开后聚焦输入；关闭恢复焦点；移动端 focus trap；`role=dialog`、`aria-labelledby`、`aria-live=polite`、`aria-busy`、Escape 和可见关闭按钮；触控热区至少 44px；reduced motion 禁用装饰动画。

### Phase 3：快捷会话与增量数据库

**目标**：按账号/农场稳定复用 floating 会话，同时兼容现有普通会话。

**涉及文件**：

- 修改 `backend/src/main/java/app/zhinong/ai/AiConversations.java`
- 修改 `backend/src/main/resources/schema.sql`
- 修改 `tools/generate_mysql_schema.cjs`
- 重新生成 `backend/src/main/resources/schema-mysql.sql`
- 修改 `backend/src/test/java/app/zhinong/AiIntegrationTest.java`
- 修改 `tools/test_mysql_ai_upgrade.cjs`（如现有测试结构需要扩展）

**数据约定**：

```sql
surface VARCHAR(16) NULL
CHECK(surface IS NULL OR surface = 'floating')
UNIQUE(tenant_id, member_id, farm_id, surface)
```

普通会话 surface 为 NULL，继续允许多条；floating 为非空，每个账号/农场唯一。并发创建采用锁定农场 + 查找/插入 + 唯一冲突重查。分支会话 surface 置 NULL。旧会话不回填。

### Phase 4：服务端页面快照

**目标**：把页面上下文变成可验证的服务器数据，而不是信任浏览器上传的页面内容。

**涉及文件**：

- 新增 `backend/src/main/java/app/zhinong/ai/AiContextSnapshotResolver.java`
- 修改 `backend/src/main/java/app/zhinong/ai/AiConversations.java`
- 修改 `backend/src/main/java/app/zhinong/ai/AiStreamService.java`
- 修改 `backend/src/main/java/app/zhinong/ai/AiStreamController.java`（仅在请求映射需要调整时）
- 修改 `backend/src/main/java/app/zhinong/ai/AiController.java`（仅保留兼容接口所需调整）
- 增加 `AiContextSnapshotResolverTest` 或在 `AiIntegrationTest` 中加入矩阵测试

**请求**：`Question` 增加可选 `pageId`、`focusType`、`focusId`。缺失/未知/不允许的组合由服务端拒绝；focus 查询必须同时带 tenant、farm 和 id 条件。

**白名单**：

- `daily`：今日任务、现场问题和农场摘要的限量事实。
- `operations`：设备上报健康、墒情趋势、任务/问题计数。
- `dashboard`：当前农场工作台摘要。
- `cameras`：当前设备/机位的安全描述和状态。
- `devices`：当前设备焦点的最新读数、有效性和告警摘要。
- `plots`、`plantings`、`tasks`、`production`：当前农场的限量列表摘要。
- `ai`：当前农场 AI 分析摘要。
- `display`、`simulation`、平台管理页面：只保留页面标签，或直接不开放小窗上下文。

解析出的 snapshot 只在本次模型请求中使用；数据库只保存安全的 source/context 活动摘要，不保存完整 snapshot JSON。

### Phase 5：协议、导航与 UI 来源说明

**目标**：让浮窗知道当前页面、显示资料来源，并能平稳跳回完整页面。

**涉及文件**：

- 修改 `frontend/src/App.vue`
- 修改 `frontend/src/ai/agent-adapter.js`
- 修改 `frontend/src/api.js`（仅在请求字段/响应字段适配需要时）
- 修改 `frontend/src/ai/FarmAssistant.vue`
- 新增或修改来源摘要组件
- 修改 `docs/农场AI助手使用说明.md`

**接口**：

```text
POST /api/ai/conversations
{ farmId, surface?: "floating" }

POST /api/ai/conversations/{id}/stream
POST /api/ai/conversations/{id}/messages
{ question, requestId, pageId?, focusType?, focusId? }
```

会话列表返回 `surface`；助手回答的 activities 返回安全来源标签。打开完整 AI 只导航并选中同一 conversationId；灌溉入口只导航到已有建议并执行原有重新校验。

### Phase 6：验证、浏览器验收与说明

**前端纯测试**：controller 的 shortcut reuse/reset、页面上下文映射、停止/重试、来源摘要和 compact view 文案。

**后端测试**：

- floating 会话同账号/农场并发复用。
- 普通会话仍可多条。
- 不同成员互不复用。
- 分支清除 surface。
- page/focus 白名单、跨租户/跨农场 focus、未知组合和模型调用次数。
- 同步和流式收到同一份安全 snapshot。
- activity 不包含原始请求、完整参数、密钥或未授权数据。

**浏览器**：扩展 `tools/test_ai_workspace.cjs` 或新建专门验收脚本，覆盖桌面非模态、390px sheet、360px/768px、键盘、深色、reduced motion、无横向溢出、农场切换、平台账号隐藏、打开完整 AI、灌溉跳转和朗读按钮。

最后运行 `scripts/verify.ps1`、项目浏览器验收脚本、MySQL schema check 和敏感信息扫描。

## 风险与处理

- **状态分叉**：由根级 controller 和共享 view 消除。
- **页面数据越权**：只接收意图，服务端 resolver 重新按 tenant/farm 查询；禁止复用旧跨农场工具。
- **快捷会话重复创建**：数据库唯一键 + 农场锁 + 冲突重查。
- **旧数据库升级**：H2 additive DDL、MySQL generator upgrade entry 和重复初始化测试同时更新。
- **移动键盘遮挡**：`visualViewport` 监听 + `dvh` + composer 可见性测试。
- **浮窗遮挡业务内容**：桌面不模态、固定尺寸、可关闭；移动端通过 sheet 和背景锁定集中输入。

## 非目标

不在浏览器直连任何模型服务；不改变现有 SSE 协议/规则回退/幂等；不在小窗执行设备控制或灌溉批准；不上传 DOM、不接受任意客户端上下文 JSON；不做告警自动弹窗、拖拽定位、语音输入、跨设备同步或后台主动推送。

## 完成定义

- 桌面和手机均可从当前业务页点击打开小禾并完成一次流式/规则回退问答。
- floating 会话在刷新后仍能恢复，并在完整 AI 页可见、可继续和可管理。
- 页面资料来源可解释，跨租户/跨农场请求无法影响模型上下文。
- 切换农场/登出不会串用旧草稿、消息或请求。
- `scripts/verify.ps1`、MySQL schema 检查和浏览器验收通过。
