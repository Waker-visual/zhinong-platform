param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root '.cache/farm-scene-package' }
$files = @(
    'frontend/public/farm-scene/index.html',
    'frontend/public/farm-scene/Build/farm-scene.loader.js',
    'frontend/public/farm-scene/Build/farm-scene.framework.js',
    'frontend/public/farm-scene/Build/farm-scene.wasm',
    'frontend/public/farm-scene/Build/farm-scene.data',
    'docs/WebGL开发包使用指南.md',
    'docs/WebGL资产来源说明.md'
)
foreach ($file in $files) {
    $source = Join-Path $root $file
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Get-Item -LiteralPath $source).Length -eq 0) {
        throw "缺少完整场景文件：$file。请先运行 scripts/build-farm-scene.ps1。"
    }
}
$revision = git -C $root rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw '无法记录源码版本' }
$output = New-Item -ItemType Directory -Force -Path $OutputDirectory
$stage = Join-Path $output.FullName ('stage-' + [guid]::NewGuid().ToString('N'))
$entries = foreach ($file in $files) {
    $source = Join-Path $root $file
    $destination = Join-Path $stage $file
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
    [ordered]@{ path = $file; bytes = (Get-Item -LiteralPath $destination).Length; sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$manifest = [ordered]@{
    formatVersion = 1
    packagedFromCommit = $revision.Trim()
    packagedAtUtc = [DateTime]::UtcNow.ToString('o')
    unityVersion = '6000.6.5f1'
    files = @($entries)
}
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $stage 'farm-scene-manifest.json'), ($manifest | ConvertTo-Json -Depth 5), $utf8)
$archive = Join-Path $output.FullName 'zhinong-farm-scene-webgl.zip'
if (Test-Path -LiteralPath $archive) { throw "输出包已存在，请指定新的 -OutputDirectory：$archive" }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $archive -CompressionLevel Optimal
$checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($archive + '.sha256'), "$checksum  zhinong-farm-scene-webgl.zip`n", $utf8)
Write-Host "开发包：$archive"
Write-Host "SHA256：$checksum"
