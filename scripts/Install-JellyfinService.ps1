<#
.SYNOPSIS
  Runs Jellyfin as a Windows service, so it starts at boot without anyone logging in.
.DESCRIPTION
  Jellyfin's Windows build starts from a tray icon by default, which needs a logged-in user. This registers
  the service "Jellyfin Server" with the nssm.exe wrapper that ships in Jellyfin's folder (Jellyfin 10.9+
  has no --service option of its own) and points it at your existing settings, so libraries and users are
  untouched. Windows restarts it if it crashes. Safe to run again.
.PARAMETER Uninstall
  Remove the service (your Jellyfin settings stay).
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\Install-JellyfinService.ps1
#>
param(
    [string]$JellyfinDir = "C:\Program Files\Jellyfin\Server",
    [string]$DataDir     = "C:\ProgramData\Jellyfin\Server",
    [string]$ServiceName = "JellyfinServer",
    [int]$Port = 8096,
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole("Administrators")
if (-not $isAdmin) { throw "Run this in an administrator PowerShell (Start > PowerShell > right-click > Run as administrator)." }

$nssm = Join-Path $JellyfinDir "nssm.exe"
$exe  = Join-Path $JellyfinDir "jellyfin.exe"
if (-not (Test-Path $exe))  { throw "jellyfin.exe not found in $JellyfinDir. Install Jellyfin first, or pass -JellyfinDir." }
if (-not (Test-Path $nssm)) { throw "nssm.exe not found in $JellyfinDir. Jellyfin's installer ships it; download it from https://nssm.cc if yours lacks it." }

function Nssm { param([Parameter(ValueFromRemainingArguments)]$a); $o = & $nssm @a 2>&1; ($o -join " ") -replace "`0", "" }

$existing = Get-Service $ServiceName -ErrorAction SilentlyContinue

if ($Uninstall) {
    if (-not $existing) { Write-Host "No service named $ServiceName."; exit 0 }
    Nssm stop $ServiceName | Out-Null
    Nssm remove $ServiceName confirm | Out-Null
    Write-Host "Service removed. Jellyfin's tray icon starts it from now on (after login)."
    exit 0
}

if (-not (Test-Path $DataDir)) { throw "Settings folder $DataDir not found. Run Jellyfin once first, or pass -DataDir." }

if (-not $existing) {
    # The tray app may have started Jellyfin already; the service needs its port.
    Get-Process jellyfin -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Nssm install $ServiceName $exe "--datadir `"$DataDir`"" | Out-Null
    Write-Host "Service created."
} else {
    Write-Host "Service already exists; updating its settings."
}
Nssm set $ServiceName AppDirectory $JellyfinDir | Out-Null
Nssm set $ServiceName DisplayName "Jellyfin Server" | Out-Null
Nssm set $ServiceName Description "Jellyfin media server. Starts at boot; no login needed. Managed with nssm.exe in $JellyfinDir." | Out-Null
Nssm set $ServiceName Start SERVICE_AUTO_START | Out-Null
Nssm set $ServiceName AppExit Default Restart | Out-Null
Nssm set $ServiceName AppStopMethodConsole 15000 | Out-Null

if ((Get-Service $ServiceName).Status -ne "Running") { Start-Service $ServiceName }

Write-Host "Waiting for Jellyfin to answer on port $Port..."
$up = $false
for ($i = 0; $i -lt 30 -and -not $up; $i++) {
    Start-Sleep 2
    try { Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/System/Info/Public" -TimeoutSec 3 | Out-Null; $up = $true } catch {}
}
$svc = Get-Service $ServiceName
if ($up) {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } | Select-Object -First 1).IPAddress
    Write-Host "Done. '$($svc.DisplayName)' is $($svc.Status) and starts with Windows." -ForegroundColor Green
    if ($ip) { Write-Host "Other devices reach it at http://${ip}:$Port" }
    Write-Host "Start/stop by hand: Start-Service $ServiceName / Stop-Service $ServiceName"
} else {
    Write-Warning "The service is $($svc.Status) but Jellyfin isn't answering yet. Look in $DataDir\log for the newest log file."
}
