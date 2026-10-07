param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally { Pop-Location }
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        mvn -B package
        if ($LASTEXITCODE -ne 0) { throw '后端测试或构建失败' }
    } finally { Pop-Location }
}
$simulationConfig = Join-Path $projectRoot '.cache/simulation-db.json'
if (Test-Path -LiteralPath $simulationConfig) {
    & (Join-Path $PSScriptRoot 'start-simulation-db.ps1')
    $sim = Get-Content -LiteralPath $simulationConfig -Raw | ConvertFrom-Json
    $env:FARM_SIM_DATABASE_URL = $sim.url
    $env:FARM_SIM_DATABASE_USER = $sim.user
    $env:FARM_SIM_DATABASE_PASSWORD = $sim.password
}
$env:FARM_DEMO = 'true'
$env:FARM_DEMO_STREAM_ENABLED = 'true'
$passwordFile = Join-Path $projectRoot '.cache/demo-password.txt'
if (-not $env:FARM_BOOTSTRAP_PASSWORD -and (Test-Path -LiteralPath $passwordFile)) {
    $env:FARM_BOOTSTRAP_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
}
if (-not $env:FARM_BOOTSTRAP_PASSWORD) {
    if (Test-Path -LiteralPath (Join-Path $projectRoot 'backend/data/zhinong.mv.db')) {
        throw '已有演示数据库，请设置初始化时的 FARM_BOOTSTRAP_PASSWORD。脚本不会覆盖已有密码。'
    }
    $bytes = New-Object byte[] 18
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $env:FARM_BOOTSTRAP_PASSWORD = [Convert]::ToBase64String($bytes)
    New-Item -ItemType Directory -Force (Split-Path -Parent $passwordFile) | Out-Null
    Set-Content -LiteralPath $passwordFile -Value $env:FARM_BOOTSTRAP_PASSWORD -NoNewline
}
Write-Host '智禾农场：http://127.0.0.1:9175'
Write-Host '首次初始化演示账号口令（已有数据库时账号口令不变）：'
Write-Host $env:FARM_BOOTSTRAP_PASSWORD
Push-Location (Join-Path $projectRoot 'backend')
try { java '-Djava.net.useSystemProxies=true' -jar target/zhinong-platform-0.3.0.jar } finally { Pop-Location }
