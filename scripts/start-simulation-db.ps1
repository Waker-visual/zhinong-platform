param([switch]$AllowUnconfigured)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.cache/simulation-db.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw '请先运行 scripts/setup-simulation-db.ps1' }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not $config.ready -and -not $AllowUnconfigured) { throw '数据库初始化未完成，请重新运行 setup-simulation-db.ps1' }
$listener = Get-NetTCPConnection -LocalPort $config.port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if (-not $process.CommandLine -or -not $process.CommandLine.Contains($config.dataDir)) { throw '模拟数据库端口被其他进程占用，请勿停止其他数据库。' }
    Write-Host "模拟 MySQL 已运行：127.0.0.1:$($config.port)"
    return
}
$arguments = @('--no-defaults', "--basedir=`"$($config.mysqlHome)`"", "--datadir=`"$($config.dataDir)`"", "--port=$($config.port)", '--bind-address=127.0.0.1', '--mysqlx=OFF', '--skip-log-bin', "--log-error=`"$($config.logFile)`"")
Start-Process -FilePath (Join-Path $config.mysqlHome 'bin/mysqld.exe') -ArgumentList $arguments -WindowStyle Hidden | Out-Null
for ($attempt=0; $attempt -lt 40; $attempt++) {
    Start-Sleep -Milliseconds 500
    if (Get-NetTCPConnection -LocalPort $config.port -State Listen -ErrorAction SilentlyContinue) { Write-Host "模拟 MySQL 已启动：127.0.0.1:$($config.port)"; return }
}
throw "MySQL 未就绪，请查看 $($config.logFile)"
