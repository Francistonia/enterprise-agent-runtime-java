$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if (-not $env:RUNTIME_SECRET_DEMO_MCP) { $env:RUNTIME_SECRET_DEMO_MCP = 'local-demo-token' }
& mvn -B -ntp '-Dstart-class=com.example.mcpdemo.DemoMcpApplication' spring-boot:run
