# 智禾农场桌面工作台 UI/UX 重构实施计划

> 状态：待用户评审，未开始实施（2026-11-16）。
> 依据：[Intent](../intents/2026-11-16-workbench-redesign-intent.md) 与[规格](../specs/2026-11-16-workbench-redesign-design.md)。

## Context

工作台功能完整，但默认配色为黑白、首屏没有主体、层级扁平、导航 14 项平铺。本计划在不改后端的前提下，分阶段把桌面工作台重构为“田园米白”视觉与六模块导航，并以“今日农场”为样板页先行确认。

## 已确认的产品决定

- 视觉方向：田园米白（米白底、田野深绿、金色点缀、浅色侧栏）。
- 范围：桌面工作台优先；大屏只做小修；手机端下一轮。
- 纯前端：只用现有接口字段，不新增指标。
- 导航：合并为六个一级模块，原页面成为模块内页签，页面 `id` 不变。

## 工作方式

- 分支 `claude/smart-farm-frontend-redesign-920475`，基于 `origin/main` `c4cf783`。只在本 worktree 内改动。
- 开发时后端用已构建的 JAR（9175 端口），前端用 `npm run dev`（4273 端口，代理 `/api`），不必每次重新打包。
- 每个阶段一组小提交；阶段结束运行 `scripts/verify.ps1`，并用截图脚本输出 1440×900 浅色、深色与 375px 截图到 Git 忽略的 `.cache/redesign/`。
- 每个阶段开始前 `git fetch`，确认 `origin/main` 没有与本阶段文件冲突的新提交。
- 任何新增或修改界面汉字的阶段，在该阶段内运行 `python tools/subset_fonts.py` 重新生成字体子集并提交，保证每个阶段结束时 `check_fonts.cjs` 都是绿的。
- 本机访问 npm 源不稳定：本计划不新增任何依赖。

## 实施阶段

### Phase 0：基线与护栏

**目标**：知道改动前哪些检查是绿的，留下可对比的基线。

- 运行 `scripts/verify.ps1`，记录结果；已有失败项单独列出，不算在本次回归里。
- 运行受导航影响的浏览器验收脚本（`tools/test_individual_farm.cjs`、`tools/test_floating_assistant.cjs`、`tools/test_farm_portfolio.cjs`），记录基线。
- 把走查截图脚本整理为 `.cache/redesign/capture.cjs`（不入库），输出基线截图。手机端入口经 `npm run dev` 的 `/mobile/` 截取（JAR 不提供该路径）；展示大屏四个页签在应用浅色与深色下各截一组。
- 编写对比度核算脚本（读取 `theme.css` 令牌，计算规格 §1.3 的组合），放在 `tools/check_contrast.cjs`，并加入 `scripts/verify.ps1`。

**完成标志**：基线结果与截图齐备；对比度脚本能对现有令牌出数。

### Phase 1：设计令牌与基础样式

**目标**：全站换上田园米白，结构不动。

**涉及文件**：

- `frontend/src/account/theme.css`：按规格 §1.1、§1.2 更新浅色与深色角色色；`--accent-forest` 系列改为田野绿；新增字号、间距令牌。
- `frontend/src/style.css`：正文、标题、按钮、输入、`.panel`、`.badge`、表格、`.empty-state`、`.skeleton` 对齐规格 §3。
- `frontend/src/account/SettingsDialog.vue`：主题色“默认”项的色样与文案。
- `frontend/src/workspace/presentation.js`、`DataChart.vue`：图表序列色与网格线取令牌。

**关键行为**：

- 语义别名名称不变，组件无需逐个改引用。
- 存储值 `FOREST` 不变，不做数据迁移。
- 登录页有独立作用域令牌。展示大屏只覆盖了部分令牌，手机端入口直接共用 `theme.css`：两者都会受影响，须与基线截图逐张比对，发现退化就在各自作用域内补令牌，不改其结构。

**完成标志**：对比度脚本全部达标；桌面、手机端与展示大屏截图无黑白残留、无对比度不足的文字；`verify.ps1` 通过。

### Phase 2：应用外壳与六模块导航

**目标**：侧栏、顶栏、标题区与模块页签到位。

**涉及文件**：

- `frontend/src/catalog.js`：新增 `modules`（规格 §2.1）。
- `frontend/src/App.vue`：侧栏渲染模块；新增 `currentModule`、`moduleTabs` 与“模块上次停留页”记忆；标题区加入 `nav.module-tabs`；命令面板按模块分组。`navigate()`、权限判断与数据加载逻辑不改。
- `frontend/src/ui/shell.css`：侧栏、顶栏、标题区、页签、底部标签栏样式。
- `frontend/src/ui/AppIcon.vue`：补充模块图标。
- `tools/test_individual_farm.cjs` 等：经由侧栏进入页面的步骤改为“先点模块、再点页签”，或改用命令面板。

**关键行为**：

- 可见性规则沿用 `visibleMenus`：管理员专属页与平台管理员行为不变。
- 临时密码拦截时模块与页签同样禁用。
- `App.vue` 模板中的 `<div v-if="!identity" class="login-layout">` 与 `<div v-else` 两个标记原样保留（字体首屏切片依赖它们）。
- 展示大屏页签是动作，不改变当前页。

**完成标志**：14 个原页面全部两次点击内可达；键盘可遍历模块与页签；浏览器验收脚本通过；`verify.ps1` 通过。

### Phase 3：样板页“今日农场”

**目标**：按规格 §4.1 完成首屏重排，作为后续页面的样式基准。

**涉及文件**：

- 新增 `frontend/src/fieldwork/PlotOverview.vue` 与 `plot-overview-model.js`（地块状态归并，附 `plot-overview-model.test.js` 并列入 `verify.ps1`）。
- `frontend/src/fieldwork/DailyFarm.vue`、`daily-farm.css`：头图带、首屏主体、农事队列与现场问题。
- `frontend/src/ai/FarmBriefing.vue`、`briefing.css`：紧凑形态对齐新卡片。

**关键行为**：

- 对话框流程（上报、安排、回执、受阻、复核）与事件不变。
- 无地块边界、无农场、加载中、读取失败四种状态各有明确呈现。
- 三种角色（管理员、操作员、查看者）首屏均合理。

**完成标志**：1440×900 首屏同时出现地块示意、优先任务、四项指标与简报；提供浅色、深色、375px 截图。

> **确认关卡**：把 Phase 1–3 的截图与基线并排交给用户。用户确认样板页后才进入 Phase 4；有调整意见则先改样板页与规格。

### Phase 4：铺开其余页面

每个子阶段独立提交、独立截图。

| 子阶段 | 页面 | 主要文件 | 规格 |
| --- | --- | --- | --- |
| 4a | 通用列表页，含种植计划“周期进度” | `App.vue` 表格部分、`style.css`、`fieldwork/production.css` | §4.7 |
| 4b | 农场概览与单农场工作台 | `workspace/FarmHub.vue`、`FarmWorkspace.vue`、`FarmMap.vue`、`FarmOperationsPanel.vue`、`workspace.css` | §4.2 |
| 4c | 运行概览 | `workspace/OperationsOverview.vue`、`operations.css` | §4.3 |
| 4d | 设备台账与设备详情 | `workspace/DeviceManager.vue`、`DeviceDetail.vue`、`DeviceControl.vue` | §4.4 |
| 4e | 农场 AI 助手 | `ai/FarmAssistant.vue`、`assistant.css`、`evidence-decisions.css`、`IrrigationWorkspace.vue` | §4.5 |
| 4f | 经营模拟 | `simulation/SimulationPage.vue` | §4.6 |
| 4g | 小禾停靠位置、提醒中心、设置对话框、展示大屏小修 | `ai/floating-assistant-model.js`、`floating-assistant.css`、`workspace/alerts.css`、`screen/display.css` | §4.8、§4.9 |

**关键行为**：规格 §4 中标注“保留”的类名与数据属性不改；文案替换按规格 §6 一次完成并全站检索确认。

### Phase 5：收尾

- 最后一次重新生成界面字体子集并确认 `node tools/check_fonts.cjs` 通过。
- `npm run build` 后 `node tools/check_bundle.cjs` 通过。
- 视觉打磨：逐页过 1440×900、1920×1080、375px、深色、减少动态效果；按规格 §7 第 5 条自查清单修正。
- 更新文档：`docs/新版功能与操作指南.md`、`docs/单农场地图与作业指南.md`、`docs/农场AI助手使用说明.md` 中涉及导航路径的说明；`docs/images/workspace-current.png` 等预览图；README 界面预览；CHANGELOG。
- 运行 `/code-review` 自查改动，处理发现的问题。
- `scripts/verify.ps1` 全量通过；推送分支并在 CHANGELOG 记录状态。

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 改令牌波及展示大屏与手机端 | 手机端共用 `theme.css`，大屏只覆盖部分令牌；Phase 0 留基线，Phase 1 结束逐张比对并在各自作用域内补令牌 |
| 地块边界数据过于简单，撑不起首屏主体 | 样板页先用真实数据渲染评估，不足则按规格 §4.1 改用地块卡片阵列 |
| 导航调整使浏览器验收脚本失效 | Phase 0 记录基线，Phase 2 同步修改脚本；页面可访问名称保持不变 |
| `App.vue` 脚本逻辑复杂，改动引入回归 | 只新增模块相关的计算属性与一个记忆表；不改 `navigate()` 与加载逻辑 |
| 新增汉字未进字体子集 | 每个涉及文案的阶段内重新生成子集（本机已装 fonttools 4.63） |
| 地图面板改为随主题变化后控件对比度不足 | 影像区叠加物继续使用 `--on-image` 令牌；对比度脚本覆盖 |
| 并行会话同时改 `frontend/` | 每阶段开始前 `fetch` 并检查冲突；小步提交；不强推 |
| 本机 npm 源不通 | 不新增依赖；依赖已从同锁文件的 worktree 复制 |

## 不在本计划内

后端与数据库改动、手机端、展示大屏结构改版、登录页、新业务能力、UI 组件库。详见 Intent §5。
