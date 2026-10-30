$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if (-not $env:RUNTIME_SECRET_SANDBOX_WORKER) {
    throw 'Set RUNTIME_SECRET_SANDBOX_WORKER in the environment of both runtime and worker.'
}
& mvn -B -ntp '-Dstart-class=com.example.sandboxworker.SandboxWorkerApplication' spring-boot:run
