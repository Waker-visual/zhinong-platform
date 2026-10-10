param([string]$SdkPath = '', [string]$JdkPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $SdkPath) { $SdkPath = Join-Path $projectRoot '.cache/android-sdk' }
if (-not $JdkPath) { throw 'Set JAVA_HOME or pass -JdkPath (JDK 17 or newer).' }
$androidJar = Get-ChildItem -LiteralPath $SdkPath -Recurse -Filter android.jar | Where-Object { $_.Directory.Name -eq 'android-35' } | Select-Object -First 1 -ExpandProperty FullName
$aapt = Get-ChildItem -LiteralPath $SdkPath -Recurse -Filter aapt2.exe | Where-Object { $_.Directory.Name -in @('35.0.0', 'android-15') } | Select-Object -First 1 -ExpandProperty FullName
if (-not $androidJar -or -not $aapt) { throw 'Android SDK not found. Run scripts/setup-android-sdk.ps1 or pass -SdkPath.' }
$buildTools = Split-Path -Parent $aapt
$work = Join-Path (Join-Path $projectRoot '.cache/android-build') ([Guid]::NewGuid().ToString('N'))
$signing = Join-Path $projectRoot '.cache/android-signing'
$output = Join-Path $projectRoot 'artifacts/android'
foreach ($folder in @($work, $signing, $output, "$work/gen", "$work/classes", "$work/dex")) {
    New-Item -ItemType Directory -Force $folder | Out-Null
}
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Android build failed: $Executable" }
}
$java = Join-Path $JdkPath 'bin/java.exe'
$javac = Join-Path $JdkPath 'bin/javac.exe'
Invoke-Checked $aapt @('compile', '--dir', "$projectRoot/android/res", '-o', "$work/resources.zip")
Invoke-Checked $aapt @('link', '-o', "$work/base.apk", '--manifest', "$projectRoot/android/AndroidManifest.xml", '-I', $androidJar, '--java', "$work/gen", "$work/resources.zip")
$sources = @(Get-ChildItem -LiteralPath "$projectRoot/android/src", "$work/gen" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
Invoke-Checked $javac (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $androidJar, '-d', "$work/classes") + $sources)
Invoke-Checked (Join-Path $JdkPath 'bin/jar.exe') @('--create', '--file', "$work/classes.jar", '-C', "$work/classes", '.')
Invoke-Checked $java @('-cp', "$buildTools/lib/d8.jar", 'com.android.tools.r8.D8', '--lib', $androidJar, '--min-api', '26', '--output', "$work/dex", "$work/classes.jar")
Copy-Item -LiteralPath "$work/base.apk" -Destination "$work/unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$work/unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    Get-ChildItem -LiteralPath "$work/dex" -Filter '*.dex' | ForEach-Object {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $_.Name) | Out-Null
    }
} finally { $zip.Dispose() }
Invoke-Checked "$buildTools/zipalign.exe" @('-f', '4', "$work/unsigned.apk", "$work/aligned.apk")
$store = Join-Path $signing 'development.p12'
$passwordFile = Join-Path $signing 'password.private.txt'
if ((Test-Path -LiteralPath $store) -and -not (Test-Path -LiteralPath $passwordFile)) { throw 'Existing signing key has no password file. Restore the private signing files before rebuilding.' }
if (-not (Test-Path -LiteralPath $passwordFile)) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    Set-Content -LiteralPath $passwordFile -Value ([Convert]::ToBase64String($bytes)) -NoNewline
}
$previousPassword = $env:ZHIHE_ANDROID_SIGNING_PASSWORD
try {
    $env:ZHIHE_ANDROID_SIGNING_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
    if (-not (Test-Path -LiteralPath $store)) {
        Invoke-Checked (Join-Path $JdkPath 'bin/keytool.exe') @('-genkeypair', '-keystore', $store, '-storetype', 'PKCS12', '-alias', 'zhihe-development', '-storepass:env', 'ZHIHE_ANDROID_SIGNING_PASSWORD', '-keypass:env', 'ZHIHE_ANDROID_SIGNING_PASSWORD', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-dname', 'CN=Zhihe Local Development', '-noprompt')
    }
    $appManifest = [xml](Get-Content -LiteralPath "$projectRoot/android/AndroidManifest.xml" -Raw)
    $appVersion = $appManifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
    $apkName = "zhihe-mobile-$appVersion.apk"
    $apk = Join-Path $output $apkName
    Invoke-Checked $java @('-jar', "$buildTools/lib/apksigner.jar", 'sign', '--ks', $store, '--ks-key-alias', 'zhihe-development', '--ks-pass', 'env:ZHIHE_ANDROID_SIGNING_PASSWORD', '--key-pass', 'env:ZHIHE_ANDROID_SIGNING_PASSWORD', '--out', $apk, "$work/aligned.apk")
    Invoke-Checked $java @('-jar', "$buildTools/lib/apksigner.jar", 'verify', '--verbose', $apk)
    Invoke-Checked "$buildTools/zipalign.exe" @('-c', '4', $apk)
    $sha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$apk.sha256" -Value "$sha  $apkName"
    Write-Host "APK: $apk"
    Write-Host "SHA256: $sha"
} finally { $env:ZHIHE_ANDROID_SIGNING_PASSWORD = $previousPassword }
