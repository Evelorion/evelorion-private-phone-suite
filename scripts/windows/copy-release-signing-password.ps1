$ErrorActionPreference = "Stop"

. "$PSScriptRoot\ReleaseSigningCredential.ps1"

$secret = Get-EvelorionReleaseSigningSecret
try {
    Set-Clipboard -Value $secret
    Write-Host "Release signing password copied to the Windows clipboard."
} finally {
    $secret = $null
}
