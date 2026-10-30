param([string]$BaseUrl = 'http://127.0.0.1:18080', [switch]$Docker)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if ($Docker) {
    Get-Content -LiteralPath '.runtime/stack.env' | ForEach-Object {
        if ($_ -match '^(LOCAL_[A-Z_]+)=(.+)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
    }
}
$password = if ($env:LOCAL_ADMIN_PASSWORD) { $env:LOCAL_ADMIN_PASSWORD } else { 'local-admin' }
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$password"))
$headers = @{ Authorization = "Basic $auth" }
function Add-Resource([string]$Path, $Body) {
    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 12) | Out-Null
    } catch {
        if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
    }
}
Add-Resource '/api/knowledge-bases' @{id='company';engine='CUSTOM';readers=@('*')}
Add-Resource '/api/knowledge-bases/company/documents' @{
    id='leave-policy';title='示例年假制度';text='示例制度：正式员工每年享有十天带薪年假，请提前向直属主管申请。'
    sourceUrl='https://example.com/policies/leave';readers=@('*')
}
Add-Resource '/api/agents' @{
    id='specialist';systemPrompt='你是专业协作助手。简洁完成收到的任务。';readers=@('*');knowledgeBases=@();peers=@();sandbox=$false;maxIterations=8
}
Add-Resource '/api/a2a/peers' @{
    id='helper';endpoint="$BaseUrl/agents/specialist/a2a";readers=@('*')
    credential=@{mode='BASIC';tokenRef='RUNTIME_SECRET_LOCAL_A2A'}
}
Add-Resource '/api/agents' @{
    id='assistant';systemPrompt='你是企业助手。知识问题先检索 company 知识库并引用来源。用户要求管理 MCP 时调用对应工具。'
    readers=@('*');knowledgeBases=@('company');peers=@('helper');sandbox=$true;maxIterations=12
}
Add-Resource '/api/mcp' @{
    id='orders';url='http://127.0.0.1:8081/mcp';allowedTools=@('get_order');readers=@('*')
    credential=@{mode='BEARER';tokenRef='RUNTIME_SECRET_DEMO_MCP'}
}
Write-Output 'Created company knowledge base, assistant agent, and orders MCP catalog entry.'
