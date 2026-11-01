<#
.SYNOPSIS
  Lets other devices on your home network reach Jellyfin.
.DESCRIPTION
  Adds one Windows Firewall rule for Jellyfin's port, for Private (home) networks only: on public Wi-Fi the
  port stays closed. Optionally marks the current network Private, which Windows needs before it applies
  Private rules. Nothing is opened to the internet.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\Allow-JellyfinFirewall.ps1 -MakeNetworkPrivate
#>
param(
    [int]$Port = 8096,
    [switch]$MakeNetworkPrivate,
    [switch]$Remove
)
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole("Administrators")
if (-not $isAdmin) { throw "Run this in an administrator PowerShell (Start > PowerShell > right-click > Run as administrator)." }

$name = "Jellyfin (home network only)"
if ($Remove) {
    Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Write-Host "Firewall rule removed."; exit 0
}
if ($MakeNetworkPrivate) {
    Get-NetConnectionProfile | Where-Object NetworkCategory -eq "Public" | ForEach-Object {
        Set-NetConnectionProfile -InterfaceIndex $_.InterfaceIndex -NetworkCategory Private
        Write-Host "Network '$($_.Name)' is now Private."
    }
}
$profiles = Get-NetConnectionProfile | Select-Object -ExpandProperty NetworkCategory -Unique
if ($profiles -contains "Public") {
    Write-Warning "Your current network is marked Public; Windows ignores Private rules there. Re-run with -MakeNetworkPrivate (only on your own Wi-Fi)."
}
if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
    Write-Host "Firewall rule added: TCP $Port, Private networks only."
} else {
    Write-Host "Firewall rule already there."
}
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } | Select-Object -First 1).IPAddress
if ($ip) { Write-Host "Other devices reach Jellyfin at http://${ip}:$Port" -ForegroundColor Green }
