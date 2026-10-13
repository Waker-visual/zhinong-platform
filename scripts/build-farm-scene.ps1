param([string]$UnityCli = 'X:\Program Files\Unity Hub\resources\unity.exe')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath $UnityCli)) { throw '请通过 -UnityCli 指定本机 Unity CLI 路径。' }
New-Item -ItemType Directory -Force -Path (Join-Path $root '.cache') | Out-Null
& $UnityCli build (Join-Path $root 'unity/FarmScene') --target WebGL --execute-method FarmBuilder.Build --allow-dirty-build --no-tail --log-file (Join-Path $root '.cache/farm-scene-build.log') --json --non-interactive
if ($LASTEXITCODE -ne 0) { throw 'Unity 三维场景构建失败，请查看 .cache/farm-scene-build.log。' }
if (Select-String -LiteralPath (Join-Path $root '.cache/farm-scene-build.log') -Pattern 'Shader error in|error CS\d+' -Quiet) { throw '场景存在着色器或脚本错误，停止发布构建产物。' }
$source = Join-Path $root '.cache/farm-scene/Build'
$destination = Join-Path $root 'frontend/public/farm-scene/Build'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -Path (Join-Path $source '*') -Destination $destination -Force
Write-Host '三维场景已生成。接着运行前端构建或启动预览。'
