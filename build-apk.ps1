param(
    [switch]$SkipCore,
    [switch]$DebugBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndkDir = Get-ChildItem "$sdk\ndk" -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndkDir) { throw "Android NDK not found in $sdk\ndk" }
$ndk = $ndkDir.FullName
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
$gradle = if (Get-Command gradle -ErrorAction SilentlyContinue) {
    (Get-Command gradle).Source
} else {
    "$env:LOCALAPPDATA\Programs\gradle-8.14.3\bin\gradle.bat"
}

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_NDK_HOME = $ndk
$env:JAVA_HOME = $jdk
$env:PATH = "$env:LOCALAPPDATA\Programs\Go\bin;$env:USERPROFILE\go\bin;$jdk\bin;$env:PATH"

if (-not $SkipCore) {
    Write-Host "==> Building Go core (dpich.aar)..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force "$root\android\app\libs" | Out-Null
    $tmpOut = "$env:TEMP\dpich.aar"
    Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue
    Push-Location "$root\core\bridge"
    & gomobile bind "-target=android/arm64,android/arm,android/amd64" -androidapi 26 -javapkg com.dpidetector.core "-ldflags=-s -w" -o $tmpOut .
    $ec = $LASTEXITCODE
    Pop-Location
    if ($ec -ne 0) { throw "gomobile bind failed with exit code $ec" }
    Copy-Item $tmpOut "$root\android\app\libs\dpich.aar" -Force
}

Write-Host "==> Building Android APK..." -ForegroundColor Cyan
$task = if ($DebugBuild) { "assembleDebug" } else { "assembleRelease" }
Push-Location "$root\android"
& $gradle $task --no-daemon
$ec = $LASTEXITCODE
Pop-Location
if ($ec -ne 0) { throw "Gradle build failed with exit code $ec" }

$outDir = "$root\dist"
New-Item -ItemType Directory -Force $outDir | Out-Null
$apkDir = if ($DebugBuild) { "$root\android\app\build\outputs\apk\debug" } else { "$root\android\app\build\outputs\apk\release" }
Get-ChildItem $apkDir -Filter "*.apk" | ForEach-Object {
    Copy-Item $_.FullName "$outDir\$($_.Name)" -Force
    Write-Host "APK: $outDir\$($_.Name) ($([Math]::Round($_.Length / 1MB, 2)) MB)" -ForegroundColor Green
}
