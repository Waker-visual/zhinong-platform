$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testClasses = Join-Path $projectRoot '.cache/android-tests'
New-Item -ItemType Directory -Force $testClasses | Out-Null
javac --release 8 -encoding UTF-8 -d $testClasses "$projectRoot/android/src/app/zhinong/mobile/ServerAddress.java" "$projectRoot/android/tests/ServerAddressTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Android address validation test compilation failed' }
java -cp $testClasses ServerAddressTest
if ($LASTEXITCODE -ne 0) { throw 'Android address validation failed' }
