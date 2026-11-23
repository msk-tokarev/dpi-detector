# Author: by Ivan Tokarev
# Registers the Go dpich Native Messaging Host for Google Chrome on Windows (HKCU — no admin required).
param(
    [Parameter(Mandatory = $true)]
    [string]$ExtensionId
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$exe = "$root\dist\dpich-native-host.exe"
if (-not (Test-Path $exe)) {
    throw "Native host binary not found: $exe (build it with: cd core\native-host; go build -o ..\..\dist\dpich-native-host.exe .)"
}

$manifestDir = "$env:LOCALAPPDATA\DpiDetectorNative"
New-Item -ItemType Directory -Force $manifestDir | Out-Null
$manifestPath = "$manifestDir\com.dpidetector.dpich.json"

$manifest = @{
    name            = "com.dpidetector.dpich"
    description     = "DPI Detector Native Messaging Host (by Ivan Tokarev)"
    path            = (Resolve-Path $exe).Path
    type            = "stdio"
    allowed_origins = @("chrome-extension://$ExtensionId/")
} | ConvertTo-Json -Depth 4

Set-Content -Path $manifestPath -Value $manifest -Encoding utf8

$regPath = "HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.dpidetector.dpich"
New-Item -Path $regPath -Force | Out-Null
Set-ItemProperty -Path $regPath -Name "(Default)" -Value $manifestPath

Write-Host "Registered Chrome Native Messaging Host: $regPath -> $manifestPath" -ForegroundColor Green
