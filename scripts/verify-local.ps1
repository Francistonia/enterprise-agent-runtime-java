param([string]$BaseUrl='http://127.0.0.1:18080', [switch]$Docker, [switch]$Model)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$password='local-admin'
if ($Docker) {
    Get-Content '.runtime/stack.env' | ForEach-Object { if ($_ -match '^LOCAL_ADMIN_PASSWORD=(.+)$') {$password=$matches[1]} }
}
$admin=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$password")))}
$userPassword=if($env:LOCAL_USER_PASSWORD){$env:LOCAL_USER_PASSWORD}else{'local-user'}
$alice=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("alice:$userPassword")))}
$bob=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("bob:$userPassword")))}
function Api([string]$Method,[string]$Path,$Body=$null,$Headers=$admin) {
    $p=@{Method=$Method;Uri="$BaseUrl$Path";Headers=$Headers;ContentType='application/json';TimeoutSec=90}
    if($null -ne $Body){$p.Body=$Body | ConvertTo-Json -Depth 16 -Compress}
    Invoke-RestMethod @p
}
function Check($Condition,[string]$Message) {if(-not $Condition){throw $Message};Write-Output "PASS $Message"}
function Run([string]$Message) {
    $run=Api POST '/api/agents/assistant/runs' @{message=$Message}
    $deadline=[DateTime]::UtcNow.AddSeconds(210)
    do {
        Start-Sleep -Seconds 2
        $run=Api GET "/api/runs/$($run.id)"
    } while($run.state -in @('QUEUED','RUNNING') -and [DateTime]::UtcNow -lt $deadline)
    if($run.state -ne 'COMPLETED'){throw "Agent run failed: $($run.state) $($run.error)"}
    return $run
}
$hits=Api POST '/api/knowledge-bases/company/search' @{query='员工带薪年假有多少天';topK=3} $alice
Check ($hits.content -match '十天') 'custom knowledge retrieval returns the leave policy'
$tools=Api POST '/api/mcp/orders/discover' @{}
Check (($tools | ConvertTo-Json -Depth 10) -match 'get_order') 'real MCP discovery'
if($Docker) {
    $command='python -c "import os; print(6*7); print(os.getuid())"'
    $result=Api POST '/api/sandbox/execute' @{command=$command;timeoutSeconds=15} $alice
    Check ($result.exitCode -eq 0 -and $result.stdout -match '42' -and $result.stdout -match '65534') 'quoted Python command executes as non-root'
    $result=Api POST '/api/sandbox/execute' @{command='sleep 10';timeoutSeconds=1} $alice
    Check ($result.timedOut -and $result.exitCode -eq 124) 'sandbox timeout is enforced'
    $result=Api POST '/api/sandbox/execute' @{command='python -c "print(1, end=chr(10)*100000)"';timeoutSeconds=15} $alice
    Check ($result.exitCode -eq 0 -and $result.truncated -and $result.stdout.Length -le 65536) 'sandbox output is bounded'
    $cfg=Get-Content '.runtime/ragflow-bootstrap.json' -Raw | ConvertFrom-Json
    try {
        Api POST '/api/knowledge-bases' @{id='company-ragflow';engine='RAGFLOW';readers=@('*');ragflowUrl=$cfg.baseUrl;datasetId=$cfg.datasetId;credential=@{mode='BEARER';tokenRef='RUNTIME_SECRET_RAGFLOW'}} | Out-Null
    } catch {if([int]$_.Exception.Response.StatusCode -ne 409){throw}}
    $doc='smoke-'+[Guid]::NewGuid().ToString('N')
    try {
        Api POST '/api/knowledge-bases/company-ragflow/documents' @{id=$doc;title='差旅制度联调';text='境内出差每日餐补为八十元。报销必须提供出差审批单和交通发票。';sourceUrl='https://example.com/policies/travel';readers=@('user:alice')} | Out-Null
        $deadline=[DateTime]::UtcNow.AddSeconds(180)
        do {
            Start-Sleep -Seconds 3
            $hits=$null
            try {$hits=Api POST '/api/knowledge-bases/company-ragflow/search' @{query='出差每天餐补是多少';topK=10} $alice}
            catch {if([int]$_.Exception.Response.StatusCode -ne 503){throw}}
            $found=$hits | Where-Object {$_.documentId -eq $doc -and $_.content -match '八十元'}
        } while(-not $found -and [DateTime]::UtcNow -lt $deadline)
        Check ([bool]$found) 'RAGFlow upload, parsing, embedding and retrieval'
        $denied=Api POST '/api/knowledge-bases/company-ragflow/search' @{query='出差每天餐补是多少';topK=10} $bob
        Check (-not ($denied | Where-Object {$_.documentId -eq $doc})) 'RAGFlow document ACL excludes Bob'
        Api PUT "/api/knowledge-bases/company-ragflow/documents/$doc/readers" @{readers=@('user:bob')} | Out-Null
        $revoked=Api POST '/api/knowledge-bases/company-ragflow/search' @{query='出差每天餐补是多少';topK=10} $alice
        Check (-not ($revoked | Where-Object {$_.documentId -eq $doc})) 'RAGFlow ACL revocation takes effect immediately'
    } finally {Api DELETE "/api/knowledge-bases/company-ragflow/documents/$doc" | Out-Null}
    Check (-not (docker ps -aq --filter 'name=^runtime-')) 'sandbox containers are cleaned up'
}
if($Model) {
    $run=Run '请调用 install_mcp 工具为当前 agent 安装 orders MCP，完成后简短回复。'
    $order=Api POST '/api/agents/assistant/mcp/orders/tools/get_order' @{arguments=@{orderId='TEST-42'}} $alice
    Check (($order | ConvertTo-Json -Depth 10) -match 'TEST-42') 'natural language installs a working MCP connector'
    $run=Run '请先查询 company 知识库，然后回答每年有几天带薪年假，并引用来源。'
    Check ($run.output -match '十天|10' -and $run.evidence.Count -gt 0) 'real model performs grounded knowledge search'
    $run=Run '请调用 uninstall_mcp 工具卸载当前 agent 的 orders MCP，完成后简短回复。'
    $status=0
    try {Api POST '/api/agents/assistant/mcp/orders/tools/get_order' @{arguments=@{orderId='TEST-42'}} $alice | Out-Null}
    catch {$status=[int]$_.Exception.Response.StatusCode}
    Check ($status -in @(403,404,409)) 'natural language uninstalls and revokes MCP tools'
    $task=Api POST '/api/a2a/peers/helper/invoke' @{message='只回复 A2A_OK'} $alice
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    while($task.status.state -notin @('completed','failed','canceled') -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 2
        $task=Api GET "/api/a2a/peers/helper/tasks/$($task.id)" $null $alice
    }
    Check ($task.status.state -eq 'completed' -and ($task | ConvertTo-Json -Depth 16) -match 'A2A_OK') 'authorized A2A invocation completes'
}
Write-Output 'Local verification completed.'
