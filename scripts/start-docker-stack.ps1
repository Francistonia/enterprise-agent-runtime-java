$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
& (Join-Path $PSScriptRoot 'download-embedding-model.ps1')
New-Item -ItemType Directory -Path '.runtime/oceanbase-init' -Force | Out-Null
$envPath = Join-Path $projectRoot '.runtime/stack.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(18)).ToLowerInvariant()
    $storagePassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(18)).ToLowerInvariant()
    $adminPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(18)).ToLowerInvariant()
    @("LOCAL_DB_PASSWORD=$dbPassword", "LOCAL_STORAGE_PASSWORD=$storagePassword", "LOCAL_ADMIN_PASSWORD=$adminPassword") |
        Set-Content -LiteralPath $envPath -Encoding utf8
}
$ddl = Get-Content -LiteralPath 'db/oceanbase.sql' -Raw
$ddl = 'CREATE DATABASE IF NOT EXISTS ragflow_doc;' + [Environment]::NewLine + $ddl.Replace('VECTOR(1024)', 'VECTOR(512)')
[IO.File]::WriteAllText((Join-Path $projectRoot '.runtime/oceanbase-init/001-runtime.sql'), $ddl, [Text.UTF8Encoding]::new($false))
& docker compose --env-file $envPath -f deploy/compose.yml up -d
if ($LASTEXITCODE -ne 0) { throw 'Docker stack startup failed; inspect .runtime logs and docker compose logs.' }
Write-Output 'Stack started. RAGFlow: http://127.0.0.1:18082; embeddings: http://127.0.0.1:18083; OceanBase: 127.0.0.1:12881.'
