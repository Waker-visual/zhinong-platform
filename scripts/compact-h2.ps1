[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    # H2 文件库基础路径（不含 .mv.db），默认是 start-demo 使用的 backend/data/zhinong。
    [string]$Database = '',
    [string]$User = 'sa',
    [string]$H2Version = '2.3.232',
    [string]$MavenRepository = '',
    [switch]$NoBackup
)
# 离线压缩 H2 文件：演示遥测清理只释放文件内的页，SHUTDOWN COMPACT 才把文件缩回实际大小。
# 必须先停止应用；脚本默认先备份，并在执行前请求确认（-WhatIf 只预览，-Confirm:$false 跳过确认）。
# 口令取自环境变量 FARM_DATABASE_PASSWORD（与应用相同），不从命令行参数读取。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Database) { $Database = Join-Path $projectRoot 'backend/data/zhinong' }
$Database = $Database -replace '\.mv\.db$', ''
$file = "$Database.mv.db"
if (-not (Test-Path -LiteralPath $file)) { throw "未找到 H2 数据库文件：$file" }
$file = (Resolve-Path -LiteralPath $file).Path
$base = $file -replace '\.mv\.db$', ''

# 应用运行时持有文件锁；独占打开失败即说明尚未停止。
try { [System.IO.File]::Open($file, 'Open', 'ReadWrite', 'None').Dispose() }
catch { throw "数据库正在被使用：请先停止应用（含 start-demo 窗口）后再压缩。$file" }

if (-not $MavenRepository) { $MavenRepository = Join-Path $HOME '.m2/repository' }
$jar = Join-Path $MavenRepository "com/h2database/h2/$H2Version/h2-$H2Version.jar"
if (-not (Test-Path -LiteralPath $jar)) { throw "未找到 H2 $H2Version：$jar。请先在 backend 目录运行 mvn -B package，或用 -H2Version / -MavenRepository 指定。" }

$before = (Get-Item -LiteralPath $file).Length
Write-Host ("压缩前：{0:N1} MB  {1}" -f ($before / 1MB), $file)
if (-not $PSCmdlet.ShouldProcess($file, 'SHUTDOWN COMPACT')) { return }

if (-not $NoBackup) {
    $backup = "$file.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item -LiteralPath $file -Destination $backup
    Write-Host "已备份：$backup"
}
$arguments = @('-cp', $jar, 'org.h2.tools.Shell', '-url', "jdbc:h2:file:$base;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE", '-user', $User)
if ($env:FARM_DATABASE_PASSWORD) { $arguments += @('-password', $env:FARM_DATABASE_PASSWORD) }
$arguments += @('-sql', 'SHUTDOWN COMPACT')
# Shell 在 SHUTDOWN 之后仍会访问连接并打印 already closed；此时压缩已完成，这一行不是错误。
& java @arguments | Where-Object { $_ -notmatch 'Database is already closed' }
if ($LASTEXITCODE -ne 0) { throw 'SHUTDOWN COMPACT 执行失败；原文件未改动时可直接重试，备份仍保留。' }
$after = (Get-Item -LiteralPath $file).Length
Write-Host ("压缩后：{0:N1} MB（减少 {1:N1} MB）" -f ($after / 1MB), (($before - $after) / 1MB))
