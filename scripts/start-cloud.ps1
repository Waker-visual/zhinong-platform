param([switch]$SkipBuild, [string]$ConfigPath = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ConfigPath) { $ConfigPath = Join-Path $projectRoot 'config/database.properties' }
if (-not (Test-Path -LiteralPath $ConfigPath)) { throw '请复制 config/database.example.properties 为 config/database.properties，并填写自己的数据库连接信息。' }
$ConfigPath = (Resolve-Path -LiteralPath $ConfigPath).Path
if ($ConfigPath -eq (Join-Path $projectRoot 'config/database.example.properties')) { throw '请使用 Git 忽略的本机配置副本，不要填写公开模板。' }
$configText = [IO.File]::ReadAllText($ConfigPath)
foreach ($key in @('host','name','username','password')) {
    if ($configText -notmatch ('(?m)^[ \t]*farm\.database\.' + $key + '[ \t]*=[ \t]*\S+')) { throw "数据库配置尚未填写：farm.database.$key" }
}
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'frontend')
    try { npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }; npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw '前端构建失败' } } finally { Pop-Location }
    Push-Location (Join-Path $projectRoot 'backend')
    try { mvn -B package; if ($LASTEXITCODE -ne 0) { throw '后端验证或打包失败' } } finally { Pop-Location }
}
$savedEnvironment = @{}
foreach ($name in @('SPRING_PROFILES_ACTIVE','SPRING_CONFIG_ADDITIONAL_LOCATION','FARM_DEMO','FARM_DEMO_RICH','FARM_RESEARCH_HISTORY','FARM_DEMO_PORTFOLIO','FARM_DEMO_LIVE','FARM_DEMO_STREAM_ENABLED','FARM_BOOTSTRAP_PASSWORD','FARM_DATABASE_URL','FARM_DATABASE_USER','FARM_DATABASE_PASSWORD','FARM_DATABASE_BIND_ADDRESS','FARM_DATABASE_INIT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
# This launcher uses the selected private file, not a prior start-rds session's environment.
Remove-Item Env:FARM_DATABASE_URL,Env:FARM_DATABASE_USER,Env:FARM_DATABASE_PASSWORD,Env:FARM_DATABASE_BIND_ADDRESS -ErrorAction SilentlyContinue
$env:FARM_DATABASE_INIT = 'always'
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:' + $ConfigPath.Replace('\','/')
$env:FARM_DEMO = 'true'
$env:FARM_DEMO_RICH = 'true'
$env:FARM_RESEARCH_HISTORY = 'true'
if (-not $env:FARM_DEMO_PORTFOLIO) { $env:FARM_DEMO_PORTFOLIO = 'true' }
if (-not $env:FARM_DEMO_STREAM_ENABLED) { $env:FARM_DEMO_STREAM_ENABLED = 'true' }
if (-not $env:FARM_DEMO_LIVE) { $env:FARM_DEMO_LIVE = 'true' }
$passwordFile = Join-Path $projectRoot '.cache/demo-password.txt'
if (-not $env:FARM_BOOTSTRAP_PASSWORD -and (Test-Path -LiteralPath $passwordFile)) { $env:FARM_BOOTSTRAP_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim() }
if (-not $env:FARM_BOOTSTRAP_PASSWORD) {
    $randomBytes = New-Object byte[] 18
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
    $env:FARM_BOOTSTRAP_PASSWORD = [Convert]::ToBase64String($randomBytes)
    New-Item -ItemType Directory -Force (Split-Path -Parent $passwordFile) | Out-Null
    Set-Content -LiteralPath $passwordFile -Value $env:FARM_BOOTSTRAP_PASSWORD -NoNewline
}
Write-Host '启动 MySQL 模式：http://127.0.0.1:9175（连接失败不会回退到本地数据库）'
Write-Host '首次初始化口令保存在 .cache/demo-password.txt；已有云端账号的口令不变。'
Push-Location (Join-Path $projectRoot 'backend')
try { java '-Djava.net.useSystemProxies=true' -jar target/zhinong-platform-0.3.0.jar } finally { Pop-Location }
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
