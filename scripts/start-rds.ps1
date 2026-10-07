param(
    [string]$ConfigPath = '', [string]$MySqlClient = '',
    [switch]$Direct, [string]$DirectAddress = '', [string]$BindAddress = '',
    [switch]$Probe, [switch]$InitializeEmptyDatabase, [switch]$Demo, [switch]$SkipBuild,
    [switch]$AllowUnencryptedConnection
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'rds-config.ps1')
if (-not $ConfigPath) { $ConfigPath = Join-Path $projectRoot 'rds.txt' }
$config = Read-RdsConfig $ConfigPath
if (-not $MySqlClient) { $MySqlClient = (Get-Command mysql.exe -ErrorAction Stop).Source }
$address = $config.host
$sslMode = if ($AllowUnencryptedConnection) { 'DISABLED' } else { 'REQUIRED' }
if ($AllowUnencryptedConnection) { Write-Host 'Using explicitly requested unencrypted MySQL transport for this run.' }
if ($Direct -or $DirectAddress) {
    $connection = Resolve-RdsDirect $config $DirectAddress $BindAddress
    $address = $connection.address; $BindAddress = $connection.bind
}
$names = @('MYSQL_PWD','FARM_DATABASE_URL','FARM_DATABASE_USER','FARM_DATABASE_PASSWORD',
    'FARM_DATABASE_BIND_ADDRESS','FARM_DATABASE_INIT','FARM_DEMO','FARM_DEMO_RICH','FARM_DEMO_STREAM_ENABLED',
    'FARM_BOOTSTRAP_PASSWORD','FARM_SIM_DATABASE_URL','FARM_SIM_DATABASE_USER','FARM_SIM_DATABASE_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:MYSQL_PWD = $config.password
    $arguments = @('--no-defaults',"--host=$address","--port=$($config.port)","--user=$($config.user)",
        "--database=$($config.database)",'--protocol=TCP',"--ssl-mode=$sslMode",'--connect-timeout=10',
        '--batch','--skip-column-names',"--execute=SELECT VERSION(); SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE(); SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('tenants','members','asset_profiles','device_integrations','alert_field_issues'); SHOW SESSION STATUS LIKE 'Ssl_cipher';")
    if ($BindAddress) { $arguments += "--bind-address=$BindAddress" }
    $ErrorActionPreference = 'Continue'
    $output = & $MySqlClient @arguments 2>&1
    $probeExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($probeExit -ne 0) {
        $code = [regex]::Match(($output | Out-String), 'ERROR \d+').Value
        throw "RDS preflight failed ($code, transport=$sslMode). Check server transport settings, whitelist and direct routing. Credentials and endpoint were not logged."
    }
    $lines = @($output | ForEach-Object { $_.ToString() })
    $tableCount = [int]$lines[1]
    $encrypted = [bool]($lines | Where-Object { $_ -match '^Ssl_cipher\s+\S+' })
    if (-not $encrypted -and -not $AllowUnencryptedConnection) { throw 'An encrypted MySQL session is required.' }
    Write-Host "RDS connection verified. Encrypted: $encrypted; server version: $($lines[0]); tables: $tableCount."
    if ($Probe) { return }
    if ($InitializeEmptyDatabase -and $tableCount -ne 0) { throw 'Initialization is restricted to an empty independent database. Existing tables will not be modified.' }
    if (-not $InitializeEmptyDatabase -and ($tableCount -eq 0 -or [int]$lines[2] -ne 5)) { throw 'Database schema is not ready. Use -InitializeEmptyDatabase only for an independent empty database; review migrations for existing databases.' }
    if (-not $SkipBuild) {
        Push-Location (Join-Path $projectRoot 'frontend')
        try { npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }; npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' } } finally { Pop-Location }
        Push-Location (Join-Path $projectRoot 'backend')
        try { mvn.cmd -B package; if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' } } finally { Pop-Location }
    }
    $env:FARM_DATABASE_URL = "jdbc:mysql://${address}:$($config.port)/$($config.database)?sslMode=$sslMode&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8&rewriteBatchedStatements=true"
    $env:FARM_DATABASE_USER = $config.user; $env:FARM_DATABASE_PASSWORD = $config.password
    $env:FARM_DATABASE_BIND_ADDRESS = $BindAddress
    $env:FARM_DATABASE_INIT = if ($InitializeEmptyDatabase) { 'always' } else { 'never' }
    $env:FARM_DEMO = $Demo.ToString().ToLowerInvariant(); $env:FARM_DEMO_RICH = $env:FARM_DEMO
    $env:FARM_DEMO_STREAM_ENABLED = $env:FARM_DEMO
    foreach ($name in @('FARM_SIM_DATABASE_URL','FARM_SIM_DATABASE_USER','FARM_SIM_DATABASE_PASSWORD')) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    $passwordPath = Join-Path $projectRoot '.cache/rds-bootstrap-password.txt'
    if ($InitializeEmptyDatabase -and -not $env:FARM_BOOTSTRAP_PASSWORD) {
        $bytes = New-Object byte[] 24
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $env:FARM_BOOTSTRAP_PASSWORD = [Convert]::ToBase64String($bytes)
        New-Item -ItemType Directory -Force (Split-Path -Parent $passwordPath) | Out-Null
        [IO.File]::WriteAllText($passwordPath, $env:FARM_BOOTSTRAP_PASSWORD)
        Write-Host 'New account password saved locally in .cache/rds-bootstrap-password.txt.'
    }
    $port = if ($env:FARM_PORT) { $env:FARM_PORT } else { '9175' }
    Write-Host "Starting http://127.0.0.1:$port with MySQL. MQTT remains disabled."
    # Run a private copy so a live Windows process does not lock the next build.
    $runtimeJar = Join-Path $projectRoot ".cache/rds-runtime-$PID.jar"
    New-Item -ItemType Directory -Force (Split-Path -Parent $runtimeJar) | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/target/zhinong-platform-0.3.0.jar') -Destination $runtimeJar
    Push-Location (Join-Path $projectRoot 'backend')
    try { java -jar $runtimeJar --spring.profiles.active=mysql --farm.mqtt.enabled=false; if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' } } finally {
        Pop-Location
        Remove-Item -LiteralPath $runtimeJar -ErrorAction SilentlyContinue
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
