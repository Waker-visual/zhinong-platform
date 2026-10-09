# 农场 AI Agent 界面实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为农场 AI 助手交付可观察、可恢复、可审阅的 Agent 对话界面，并接入真实流式事件与安全的活动摘要。

**Architecture:** 先以前端事件 reducer 和同步适配器建立稳定的 UI 状态机，再增加 Spring MVC SSE 接口和 OpenAI 兼容上游流式转译。活动摘要单独持久化，正文和旧同步接口保持兼容；前端只展示安全摘要，不展示模型内部思维。

**Tech Stack:** Vue 3.5、Vite、原生 CSS 主题令牌、Spring Boot MVC、`SseEmitter`、Java `HttpClient`、H2/MySQL additive DDL、现有 `scripts/verify.ps1`。

**Spec:** `docs/superpowers/specs/2026-10-09-ai-agent-interface-design.md`

## Global Constraints

- 不引入 React、Next.js、Tailwind 或新的 AI UI 运行时依赖。
- 所有业务查询、活动存储和流式运行必须带当前会话推导出的租户条件。
- 模型原始凭据、完整工具参数和内部思维不得进入 Git、数据库或前端事件。
- 旧 `/api/ai/conversations/{id}/messages` 保持可用，流式失败时必须能回退。
- 现有 `requestId` 幂等规则、规则回答和人工确认灌溉接口必须保留。
- 每项任务都要有对应的前端构建、后端测试或浏览器验收证据。

## Review Focus

- 用户向上滚动阅读时，流式增量不能强行把滚动位置拉到底部；由 Task 2 的滚动测试覆盖。
- 断线或模型超时时，不能写入半截 assistant 成功消息；由 Task 5 的运行状态测试覆盖。
- 相同 `requestId` 重试不能产生重复用户消息、assistant 消息或活动；由 Task 5 的幂等测试覆盖。
- 跨租户读取消息活动必须返回 404/拒绝；由 Task 5 的隔离测试覆盖。
- reduced motion 下状态仍可理解但动画必须停止；由 Task 3 的浏览器检查覆盖。

---

### Task 1: 建立 Agent 状态 reducer 与同步适配器

**Files:**
- Create: `frontend/src/ai/agent-events.js`
- Create: `frontend/src/ai/agent-adapter.js`
- Modify: `frontend/src/ai/FarmAssistant.vue`
- Test: `frontend/src/ai/agent-events.test.js`

**Interfaces:**
- `reduceAgentEvent(state, event) -> state`
- `createAgentRun(question, requestId) -> AgentRunState`
- `syncConversationAdapter({ farmId, conversationId, question, requestId }) -> AsyncGenerator<AgentRunEvent>`

- [ ] **Step 1: Write reducer tests** for ordered deltas, activity status transitions, completion and error.
- [ ] **Step 2: Run the focused test** and verify the missing reducer fails.
- [ ] **Step 3: Implement the reducer and synchronous adapter** so the current POST response emits a submitted activity, a completed response and a final run event.
- [ ] **Step 4: Connect `FarmAssistant.vue` to the adapter** while preserving existing conversation reload and `pendingRequest` behavior.
- [ ] **Step 5: Run the focused frontend test and `npm run build`**.
- [ ] **Step 6: Commit** `feat: add agent event state model`.

### Task 2: Add conversation rendering, disclosures and scroll anchoring

**Files:**
- Create: `frontend/src/ai/AiActivityDisclosure.vue`
- Create: `frontend/src/ai/AiActivityRow.vue`
- Create: `frontend/src/ai/AiStreamingStatus.vue`
- Modify: `frontend/src/ai/FarmAssistant.vue`
- Modify: `frontend/src/ai/assistant.css`
- Test: `frontend/src/ai/agent-events.test.js`

**Interfaces:**
- `AiActivityDisclosure` accepts `activities`, `runStatus`, and emits `toggle`.
- `AiStreamingStatus` accepts `status`, `diagnostic`, and `canRetry`.
- `FarmAssistant` owns `isNearBottom`, `followOutput`, and `scrollToLatest`.

- [ ] **Step 1: Add reducer tests** for preserving prior messages while a new assistant run is active.
- [ ] **Step 2: Implement activity disclosure markup** with semantic `<details>` behavior, explicit status labels, and safe detail text.
- [ ] **Step 3: Add streaming message placeholder and skeleton rows** using existing `.skeleton` tokens.
- [ ] **Step 4: Replace unconditional smooth scroll** with a 72px bottom threshold, a jump-to-latest button, and reduced-motion-aware behavior.
- [ ] **Step 5: Add retry and cancel presentation** without changing the existing API contract yet.
- [ ] **Step 6: Run frontend tests, build, and browser-check desktop/mobile layouts**.
- [ ] **Step 7: Commit** `feat: add agent activity disclosure UI`.

### Task 3: Add AI-specific loading, motion and accessibility polish

**Files:**
- Modify: `frontend/src/ai/assistant.css`
- Modify: `frontend/src/ui/shell.css` only if a shared token is needed
- Test: `frontend/src/ai/agent-events.test.js`

- [ ] **Step 1: Add CSS tests or DOM assertions** for `aria-busy`, status labels, and hidden decorative animation.
- [ ] **Step 2: Add shimmer text, caret, status dots, activity connector and skeleton layouts** using theme tokens only.
- [ ] **Step 3: Add `@media (prefers-reduced-motion: reduce)` overrides** for caret, shimmer and disclosure transitions.
- [ ] **Step 4: Verify focus rings, keyboard disclosure, `role="log"`, polite status announcements and mobile wrapping.**
- [ ] **Step 5: Commit** `feat: polish agent loading and motion states`.

### Task 4: Add additive activity persistence schema

**Files:**
- Modify: `backend/src/main/resources/schema.sql`
- Modify: `backend/src/main/resources/schema-mysql.sql`
- Modify: `docs/农场AI助手使用说明.md`
- Test: `backend/src/test/java/app/zhinong/AiIntegrationTest.java`

- [ ] **Step 1: Add tenant-scoped `ai_message_activities` DDL** with composite foreign keys, sequence ordering, status checks and safe summary columns.
- [ ] **Step 2: Add integration assertions** that completed activities reload with the conversation and that old messages return an empty activity list.
- [ ] **Step 3: Run the focused Maven test** and verify the additive schema works on H2.
- [ ] **Step 4: Update the AI usage guide** with the event and activity response shape.
- [ ] **Step 5: Commit** `feat: persist assistant activity summaries`.

### Task 5: Implement streaming endpoint and upstream event translation

**Files:**
- Create: `backend/src/main/java/app/zhinong/ai/AgentEvent.java`
- Create: `backend/src/main/java/app/zhinong/ai/AiStreamService.java`
- Modify: `backend/src/main/java/app/zhinong/ai/AiConversations.java`
- Modify: `backend/src/main/java/app/zhinong/ai/LlmGateway.java`
- Modify: `frontend/src/api.js`
- Modify: `frontend/src/ai/agent-adapter.js`
- Test: `backend/src/test/java/app/zhinong/AiIntegrationTest.java`

**Interfaces:**
- `POST /api/ai/conversations/{id}/stream` emits `AgentEvent` JSON as SSE.
- `AiStreamService.stream(conversationId, Question, SseEmitter)` owns authorization, idempotency, upstream cancellation and final persistence.
- `LlmGateway.stream(...)` emits provider-neutral text and safe tool activity callbacks.

- [ ] **Step 1: Add failing integration tests** for event ordering, final persistence, duplicate request IDs, timeout/error, and cross-tenant access.
- [ ] **Step 2: Implement `AgentEvent` serialization** with sequence numbers and explicit event types.
- [ ] **Step 3: Implement SSE endpoint authorization and disconnect handling** without holding a database transaction open for the whole stream.
- [ ] **Step 4: Implement OpenAI-compatible SSE parsing** in `LlmGateway`, bounded by the existing timeout and semaphore.
- [ ] **Step 5: Persist only completed user/assistant messages and safe activity summaries**; emit an error event for partial runs without marking success.
- [ ] **Step 6: Add `streamJson` to `api.js`** with bearer auth, timeout, line parsing, and normalized error handling.
- [ ] **Step 7: Switch the adapter to streaming when available** and fall back to the existing POST endpoint when the stream is unavailable.
- [ ] **Step 8: Run focused backend tests and `scripts/verify.ps1`**.
- [ ] **Step 9: Commit** `feat: stream agent conversation events`.

### Task 6: Wire safe tool and approval activities

**Files:**
- Modify: `backend/src/main/java/app/zhinong/ai/AiStreamService.java`
- Modify: `backend/src/main/java/app/zhinong/ai/AiConversations.java`
- Modify: `frontend/src/ai/FarmAssistant.vue`
- Modify: `frontend/src/ai/AiActivityRow.vue`
- Modify: `docs/农场AI助手使用说明.md`
- Test: `backend/src/test/java/app/zhinong/AiIntegrationTest.java`

- [ ] **Step 1: Add tool activity fixtures** for farm summary, pending tasks, open issues and weather context.
- [ ] **Step 2: Render tool rows** with human-readable labels, counts and source summaries; never expose raw arguments or credentials.
- [ ] **Step 3: Add irrigation approval activity** that routes to existing proposal/approve APIs and keeps confirmation explicit.
- [ ] **Step 4: Test completed, failed and cancelled activity paths**.
- [ ] **Step 5: Commit** `feat: expose safe agent tool and approval activities`.

### Task 7: Full verification and preview acceptance

**Files:**
- Modify: `docs/农场AI助手使用说明.md` if final behavior changed
- Test: existing backend/frontend checks plus browser preview

- [ ] **Step 1: Run `scripts/verify.ps1` and record test totals.**
- [ ] **Step 2: Rebuild the packaged backend and restart `scripts/start-demo.ps1 -SkipBuild`.**
- [ ] **Step 3: Verify first-load skeleton, empty chat, completed chat, streaming, disclosure open/close, jump-to-latest, retry, mobile layout, dark theme and reduced motion in the browser.**
- [ ] **Step 4: Inspect browser console logs and git diff for credentials, raw prompts or accidental generated files.**
- [ ] **Step 5: Commit documentation adjustments and confirm a clean worktree.**

