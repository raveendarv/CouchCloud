<#
.SYNOPSIS
  Lets you run commands on this laptop from another computer over SSH, key only, home network only.
.DESCRIPTION
  Turns on Windows' built-in OpenSSH server, allows it for Private networks only, disables password login
  and installs one public key. Whoever holds the matching private key gets an administrator shell on this
  machine, so only do this on your own network and remove it (-Disable) when you no longer need it.
.PARAMETER PublicKey
  The contents of the other computer's public key file (id_ed25519.pub): "ssh-ed25519 AAAA... comment".
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\Enable-RemoteAdmin.ps1 -PublicKey "ssh-ed25519 AAAA... me@laptop"
#>
param(
    [string]$PublicKey,
    [switch]$Disable
)
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole("Administrators")
if (-not $isAdmin) { throw "Run this in an administrator PowerShell (Start > PowerShell > right-click > Run as administrator)." }

$keys = "C:\ProgramData\ssh\administrators_authorized_keys"
$cfg  = "C:\ProgramData\ssh\sshd_config"
$rule = "OpenSSH (home network only)"

if ($Disable) {
    Stop-Service sshd -ErrorAction SilentlyContinue
    Set-Service sshd -StartupType Disabled -ErrorAction SilentlyContinue
    Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Write-Host "SSH is off and its firewall rule is gone. The key file $keys is still there; delete it if you won't need it again."
    exit 0
}
if (-not $PublicKey -or $PublicKey -notmatch '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256) ') { throw "Pass -PublicKey with the other computer's public key (the contents of its .pub file)." }

if ((Get-WindowsCapability -Online -Name "OpenSSH.Server*").State -ne "Installed") {
    Write-Host "Installing Windows' OpenSSH server..."
    Add-WindowsCapability -Online -Name "OpenSSH.Server~~~~0.0.1.0" | Out-Null
}
Set-Service sshd -StartupType Automatic
Start-Service sshd
Disable-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -ErrorAction SilentlyContinue   # the all-networks rule the installer adds
if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $rule -Direction Inbound -Protocol TCP -LocalPort 22 -Action Allow -Profile Private | Out-Null
}
# Key only: no password guessing.
$conf = Get-Content $cfg
$conf = $conf | Where-Object { $_ -notmatch '^\s*#?\s*PasswordAuthentication\s' }
$conf += "PasswordAuthentication no"
Set-Content $cfg $conf -Encoding ASCII

if (-not (Test-Path $keys) -or -not (Select-String -Path $keys -SimpleMatch $PublicKey -Quiet)) { Add-Content -Path $keys -Value $PublicKey }
icacls $keys /inheritance:r /grant "Administrators:F" /grant "SYSTEM:F" | Out-Null
Restart-Service sshd

$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } | Select-Object -First 1).IPAddress
Write-Host "SSH is on, key only, home network only." -ForegroundColor Green
Write-Host "From the other computer:  ssh $env:USERNAME@$ip"
Write-Host "Turn it off again with:   .\Enable-RemoteAdmin.ps1 -Disable"
