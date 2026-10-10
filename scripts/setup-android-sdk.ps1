$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkCache = Join-Path $projectRoot '.cache/android-sdk'
# Versions and checksums from Google's repository2-1.xml, checked 2026-10-10.
$packages = @(
    @{ File = 'platform-35_r02.zip'; Hash = '0bb560a90a7a2cbd0dd8348224d518b638fe7949'; Folder = 'platform' },
    @{ File = 'build-tools_r35_windows.zip'; Hash = 'af059bb67cf7786f45ee0db85e2d24985df1b4b6'; Folder = 'build-tools' }
)
New-Item -ItemType Directory -Force $sdkCache | Out-Null
foreach ($package in $packages) {
    $archive = Join-Path $sdkCache $package.File
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri ('https://dl.google.com/android/repository/' + $package.File) -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash -ne $package.Hash) {
        throw "SDK archive checksum mismatch: $($package.File). Remove this cache archive and retry."
    }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $sdkCache $package.Folder) -Force
    Write-Host "Verified $($package.File)"
}
Write-Host 'Android SDK ready in .cache/android-sdk. SDK license and notices remain in the extracted packages.'
