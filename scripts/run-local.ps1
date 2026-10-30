param([int]$Port = 18080, [switch]$Docker, [switch]$Jar)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$originalArkKey = $env:ARK_API_KEY
if ($Docker) {
    Get-Content -LiteralPath '.runtime/stack.env' | ForEach-Object {
        if ($_ -match '^(LOCAL_[A-Z_]+)=(.+)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
    }
    $env:SPRING_PROFILES_ACTIVE = 'local,docker-local'
    $env:RUNTIME_SECRET_EMBEDDING = 'local-embedding'
} else { $env:SPRING_PROFILES_ACTIVE = 'local' }
if (-not $env:RUNTIME_SECRET_DEMO_MCP) { $env:RUNTIME_SECRET_DEMO_MCP = 'local-demo-token' }
$localUserPassword = if ($env:LOCAL_USER_PASSWORD) { $env:LOCAL_USER_PASSWORD } else { 'local-user' }
$env:RUNTIME_SECRET_LOCAL_A2A = "alice:$localUserPassword"
$ragflowKeyPath = Join-Path $projectRoot '.runtime/secrets/ragflow.dpapi'
if (Test-Path -LiteralPath $ragflowKeyPath) {
    $ragflowKey = ConvertTo-SecureString -String ((Get-Content -LiteralPath $ragflowKeyPath -Raw).Trim())
    $env:RUNTIME_SECRET_RAGFLOW = [System.Net.NetworkCredential]::new('', $ragflowKey).Password
}
$secretPath = Join-Path $projectRoot '.runtime/secrets/ark.dpapi'
if (-not $env:ARK_API_KEY -and (Test-Path -LiteralPath $secretPath)) {
    $encrypted = (Get-Content -LiteralPath $secretPath -Raw).Trim()
    $secureKey = ConvertTo-SecureString -String $encrypted
    $env:ARK_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
}
if (-not $env:ARK_API_KEY) {
    throw 'Set ARK_API_KEY, or run scripts/set-ark-key.ps1 first.'
}
$env:PORT = "$Port"
try {
    if ($Jar) {
        & java -jar target/enterprise-agent-runtime-0.1.0-SNAPSHOT.jar
    } else {
        & mvn -B -ntp spring-boot:run
    }
    if ($LASTEXITCODE -ne 0) { throw "Runtime exited with code $LASTEXITCODE" }
} finally {
    if ($originalArkKey) { $env:ARK_API_KEY = $originalArkKey }
    else { Remove-Item Env:ARK_API_KEY -ErrorAction SilentlyContinue }
}
