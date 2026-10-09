param([string]$MavenRepository = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    Push-Location backend
    try {
        if ($MavenRepository) { mvn -B "-Dmaven.repo.local=$MavenRepository" test }
        else { mvn -B test }
        if ($LASTEXITCODE -ne 0) { throw '后端测试未通过' }
    } finally { Pop-Location }
    Push-Location frontend
    try {
        node --test src/ai/agent-events.test.js
        if ($LASTEXITCODE -ne 0) { throw 'Agent 事件模型测试未通过' }
        node --test src/ai/markdown.test.js
        if ($LASTEXITCODE -ne 0) { throw 'Markdown 渲染器测试未通过' }
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建未通过' }
    } finally { Pop-Location }
    node --test tools/tests/check_project.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '项目检查工具测试未通过' }
    node --test tools/tests/coordinates.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '地图坐标回归测试未通过' }
    node --test tools/tests/model-config.test.cjs
    if ($LASTEXITCODE -ne 0) { throw '模型服务配置表单测试未通过' }
    node --test tools/tests/ai-diagnostics.test.cjs
    if ($LASTEXITCODE -ne 0) { throw 'AI 诊断码文案测试未通过' }
    node tools/check_project.cjs scan
    if ($LASTEXITCODE -ne 0) { throw '项目公开范围或敏感信息检查未通过' }
    node tools/generate_mysql_schema.cjs --check
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 表结构与项目定义不一致' }
} finally { Pop-Location }
