param([string]$UnityCli = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $UnityCli) {
    # Prefer the official standalone install when present: MSIX path virtualization
    # can prevent external unpackers from finding the licensing module directory.
    $standaloneCli = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Unity/bin/unity.exe' } else { '' }
    if ($standaloneCli -and (Test-Path -LiteralPath $standaloneCli -PathType Leaf)) { $UnityCli = $standaloneCli }
}
if (-not $UnityCli) {
    $command = Get-Command unity -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { $UnityCli = $command.Source }
}
if (-not $UnityCli -or -not (Test-Path -LiteralPath $UnityCli -PathType Leaf)) { throw '请安装 Unity CLI 并加入 PATH，或通过 -UnityCli 指定本机 Unity CLI 路径。' }
New-Item -ItemType Directory -Force -Path (Join-Path $root '.cache') | Out-Null
$log = Join-Path $root '.cache/farm-scene-build.log'
$source = Join-Path $root '.cache/farm-scene/Build'
$artifacts = @('farm-scene.loader.js', 'farm-scene.data', 'farm-scene.framework.js', 'farm-scene.wasm')
# A failed or incomplete invocation must not publish an earlier build as its result.
foreach ($path in @($log) + @($artifacts | ForEach-Object { Join-Path $source $_ })) {
    if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
}
& $UnityCli build (Join-Path $root 'unity/FarmScene') --target WebGL --execute-method FarmBuilder.Build --allow-dirty-build --no-tail --log-file $log --json --non-interactive
if ($LASTEXITCODE -ne 0) { throw 'Unity 三维场景构建失败，请查看 .cache/farm-scene-build.log。' }
if (-not (Test-Path -LiteralPath $log -PathType Leaf)) { throw 'Unity 未生成本次构建日志，停止发布构建产物。' }
if (Select-String -LiteralPath $log -Pattern 'Shader error in|error CS\d+' -Quiet) { throw '场景存在着色器或脚本错误，停止发布构建产物。' }
foreach ($name in $artifacts) {
    $path = Join-Path $source $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) { throw "Unity 本次构建缺少有效产物 $name，停止发布。" }
}
$destination = Join-Path $root 'frontend/public/farm-scene/Build'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
foreach ($name in $artifacts) { Copy-Item -LiteralPath (Join-Path $source $name) -Destination $destination -Force }
Write-Host '三维场景已生成。接着运行前端构建或启动预览。'
