param([switch]$SkipBuild, [string]$BindAddress = '0.0.0.0')
$ErrorActionPreference = 'Stop'
# Same backend and database as desktop; this only changes the listening address.
$previousBind = $env:FARM_BIND
try {
    $env:FARM_BIND = $BindAddress
    Write-Host 'Mobile connection mode: stop the previous project instance on port 9175 first.'
    Write-Host 'Use the same trusted network on phone and computer; enter the computer address with :9175 in the app.'
    Write-Host 'This reuses the existing database and does not modify firewall rules.'
    & (Join-Path $PSScriptRoot 'start-demo.ps1') -SkipBuild:$SkipBuild
} finally { $env:FARM_BIND = $previousBind }
