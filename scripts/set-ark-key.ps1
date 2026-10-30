$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$secretDir = Join-Path $projectRoot '.runtime/secrets'
New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
$key = Read-Host 'Ark API Key' -AsSecureString
ConvertFrom-SecureString -SecureString $key |
    Set-Content -LiteralPath (Join-Path $secretDir 'ark.dpapi') -Encoding utf8
Write-Output 'Saved with Windows DPAPI for the current Windows user.'
