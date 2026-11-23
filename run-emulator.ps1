$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$emu = "$sdk\emulator\emulator.exe"
$adb = "$sdk\platform-tools\adb.exe"
$apk = "$root\dist\dpi-detector-x86_64.apk"

if (-not (Test-Path $apk)) {
    throw "APK not found: $apk (run .\build-apk.ps1 first)"
}

& $emu -accel-check 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Android Emulator Hypervisor Driver (AEHD) is not installed." -ForegroundColor Yellow
    Write-Host "Installing AEHD (UAC prompt will appear)..." -ForegroundColor Cyan
    $installer = "$sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$installer`"" -Verb RunAs -Wait
}

Write-Host "==> Starting emulator (dpi_pixel)..." -ForegroundColor Cyan
Start-Process -FilePath $emu -ArgumentList "-avd", "dpi_pixel", "-netdelay", "none", "-netspeed", "full"

Write-Host "==> Waiting for Android to finish booting..." -ForegroundColor Cyan
& $adb wait-for-device
do {
    Start-Sleep -Seconds 2
    $boot = (& $adb shell getprop sys.boot_completed 2>$null).Trim()
} while ($boot -ne "1")

Write-Host "==> Installing DPI Detector APK..." -ForegroundColor Cyan
& $adb install -r $apk

Write-Host "==> Launching DPI Detector..." -ForegroundColor Green
& $adb shell am start -n "com.dpidetector.app/.MainActivity"
