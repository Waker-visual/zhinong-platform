param([string]$MySqlHome = '', [int]$Port = 3317)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $projectRoot '.cache'
$configPath = Join-Path $cachePath 'simulation-db.json'
$utf8 = New-Object System.Text.UTF8Encoding($false)
function New-LocalSecret {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).Replace('+','A').Replace('/','B').TrimEnd('=')
}
if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($config.ready) { & (Join-Path $PSScriptRoot 'start-simulation-db.ps1'); return }
} else {
    if (-not $MySqlHome) { $MySqlHome = Split-Path -Parent (Split-Path -Parent (Get-Command mysqld.exe -ErrorAction Stop).Source) }
    $MySqlHome = (Resolve-Path -LiteralPath $MySqlHome).Path
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw '端口已占用，请用 -Port 指定独立端口。' }
    $dataDir = Join-Path $cachePath 'simulation-mysql/data'
    if (Test-Path -LiteralPath $dataDir) { throw '已有未登记的数据目录；为保护数据，不会自动覆盖。' }
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    $logFile = Join-Path $cachePath 'simulation-mysql/mysql.log'
    & (Join-Path $MySqlHome 'bin/mysqld.exe') --no-defaults --initialize-insecure "--basedir=$MySqlHome" "--datadir=$dataDir" "--log-error=$logFile"
    if ($LASTEXITCODE -ne 0) { throw "初始化失败，请查看 $logFile" }
    $config = [pscustomobject]@{ready=$false;mysqlHome=$MySqlHome;dataDir=$dataDir;logFile=$logFile;port=$Port;user='zhinong_sim';password=(New-LocalSecret);rootPassword=(New-LocalSecret);url="jdbc:mysql://127.0.0.1:$Port/zhinong_simulation?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=true"}
    [IO.File]::WriteAllText($configPath,($config | ConvertTo-Json),$utf8)
}
& (Join-Path $PSScriptRoot 'start-simulation-db.ps1') -AllowUnconfigured
$mysql = Join-Path $config.mysqlHome 'bin/mysql.exe'
$clientPath = Join-Path $cachePath 'simulation-mysql/root-client.cnf'
$clientText = "[client]`nhost=127.0.0.1`nport=$($config.port)`nuser=root`npassword=$($config.rootPassword)`nprotocol=TCP`n"
[IO.File]::WriteAllText($clientPath,$clientText,$utf8)
# Passwords are passed on standard input or through an ignored local option file, never argv.
$ErrorActionPreference = 'Continue'
"SELECT 1;" | & $mysql "--defaults-extra-file=$clientPath" --batch --skip-column-names 2>$null | Out-Null
$probeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($probeExit -ne 0) {
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($config.rootPassword)';" | & $mysql --no-defaults "--host=127.0.0.1" "--port=$($config.port)" "--user=root" --protocol=TCP --batch
    if ($LASTEXITCODE -ne 0) { throw '无法设置此独立实例的 root 密码；未更改其他 MySQL 实例。' }
}
$sql = "CREATE DATABASE IF NOT EXISTS zhinong_simulation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'zhinong_sim'@'127.0.0.1' IDENTIFIED BY '$($config.password)'; ALTER USER 'zhinong_sim'@'127.0.0.1' IDENTIFIED BY '$($config.password)'; GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX, REFERENCES ON zhinong_simulation.* TO 'zhinong_sim'@'127.0.0.1';"
$sql | & $mysql "--defaults-extra-file=$clientPath" --batch
if ($LASTEXITCODE -ne 0) { throw '模拟数据库或专用用户配置失败。' }
$config.ready = $true
[IO.File]::WriteAllText($configPath,($config | ConvertTo-Json),$utf8)
Write-Host "独立模拟数据库已就绪：127.0.0.1:$($config.port)/zhinong_simulation。凭据保存在本地 .cache，不加入 Git。"
