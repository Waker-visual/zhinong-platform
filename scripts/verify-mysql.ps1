param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.cache/simulation-db.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw '先按说明准备本机独立 MySQL 测试环境；本脚本不会连接云库。' }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'start-simulation-db.ps1')
$mysql = Join-Path $config.mysqlHome 'bin/mysql.exe'
$client = Join-Path $projectRoot '.cache/simulation-mysql/root-client.cnf'
$suffix = [Guid]::NewGuid().ToString('N').Substring(0,12)
$testDb = 'zhinong_test_' + $suffix
$testUser = 'zhinong_test_' + $suffix
$secretBytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
$testPassword = [Convert]::ToBase64String($secretBytes)
$created = $false
Push-Location $projectRoot
try {
    "CREATE DATABASE $testDb CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE USER '$testUser'@'127.0.0.1' IDENTIFIED BY '$testPassword'; GRANT ALL ON $testDb.* TO '$testUser'@'127.0.0.1';" | & $mysql "--defaults-extra-file=$client" --batch
    if ($LASTEXITCODE -ne 0) { throw '独立测试数据库准备失败。' }
    $created = $true
    node tools/test_mysql_ai_upgrade.cjs $mysql $client $testDb
    if ($LASTEXITCODE -ne 0) { throw 'MySQL AI schema upgrade test failed.' }
    $env:FARM_MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:$($config.port)/${testDb}?sslMode=DISABLED&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    $env:FARM_MYSQL_TEST_USER = $testUser
    $env:FARM_MYSQL_TEST_PASSWORD = $testPassword
    mvn -B -f backend/pom.xml '-Dtest=MySqlIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 接口和数据回归测试未通过。' }
} finally {
    if ($created -and $testDb -match '^zhinong_test_[a-f0-9]{12}$' -and $testUser -eq $testDb) {
        "DROP DATABASE $testDb; DROP USER '$testUser'@'127.0.0.1';" | & $mysql "--defaults-extra-file=$client" --batch
    }
    Remove-Item Env:FARM_MYSQL_TEST_URL,Env:FARM_MYSQL_TEST_USER,Env:FARM_MYSQL_TEST_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
