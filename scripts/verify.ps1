param([string]$MavenRepository = '', [string]$MavenSettings = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($MavenSettings) { $MavenSettings = (Resolve-Path -LiteralPath $MavenSettings).Path }
Push-Location $projectRoot
try {
    Push-Location backend
    try {
        $mavenArgs = @('-B')
        if ($MavenRepository) { $mavenArgs += "-Dmaven.repo.local=$MavenRepository" }
        if ($MavenSettings) { $mavenArgs += @('-s', $MavenSettings) }
        & mvn @mavenArgs test
        if ($LASTEXITCODE -ne 0) { throw '后端测试未通过' }
    } finally { Pop-Location }
    Push-Location frontend
    try {
        node --test src/ai/agent-events.test.js
        if ($LASTEXITCODE -ne 0) { throw 'Agent 事件模型测试未通过' }
        node --test src/ai/markdown.test.js
        if ($LASTEXITCODE -ne 0) { throw 'Markdown 渲染器测试未通过' }
        node --test src/ai/follow-up-model.test.js src/ai/follow-up-request.test.js
        if ($LASTEXITCODE -ne 0) { throw '对话引导与异步隔离测试未通过' }
        node --test src/ai/companion-model.test.js
        if ($LASTEXITCODE -ne 0) { throw '禾苗助手互动状态测试未通过' }
        node --test src/screen/screen-model.test.js
        if ($LASTEXITCODE -ne 0) { throw '展示大屏数据模型测试未通过' }
        node --test src/screen/scene-model.test.js
        if ($LASTEXITCODE -ne 0) { throw '三维农场数据转换测试未通过' }
        node --test src/screen/scene/scene-model.test.js
        if ($LASTEXITCODE -ne 0) { throw '三维农场场景数据模型测试未通过' }
        node --test src/screen/screen-refresh.test.js src/api.test.js
        if ($LASTEXITCODE -ne 0) { throw '请求取消、超时与大屏渐进刷新测试未通过' }
        node --test src/screen/satellite-model.test.js
        if ($LASTEXITCODE -ne 0) { throw '大屏卫星地图坐标测试未通过' }
        node --test src/workspace/tile-request.test.js
        if ($LASTEXITCODE -ne 0) { throw '卫星瓦片并发与取消测试未通过' }
        node --test src/mobile/alert-state.test.js
        if ($LASTEXITCODE -ne 0) { throw '手机告警选择与异步保存测试未通过' }
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建未通过' }
    } finally { Pop-Location }
    node --test tools/tests/check_project.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '项目检查工具测试未通过' }
    node --test tools/tests/coordinates.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '地图坐标回归测试未通过' }
    node --test tools/tests/mobile.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '手机端会话与指令测试未通过' }
    & (Join-Path $PSScriptRoot 'verify-android.ps1')
    node --test tools/tests/model-config.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '模型服务配置表单测试未通过' }
    node --test tools/tests/ai-diagnostics.test.cjs
    if ($LASTEXITCODE -ne 0) { throw 'AI 诊断码文案测试未通过' }
    node tools/check_project.cjs scan
    if ($LASTEXITCODE -ne 0) { throw '项目公开范围或敏感信息检查未通过' }
    node tools/generate_mysql_schema.cjs --check
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 表结构与项目定义不一致' }
} finally { Pop-Location }
