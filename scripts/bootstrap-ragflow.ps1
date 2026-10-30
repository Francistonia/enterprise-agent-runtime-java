param([string]$BaseUrl = 'http://127.0.0.1:18082')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$stack = @{}
Get-Content -LiteralPath (Join-Path $projectRoot '.runtime/stack.env') | ForEach-Object {
    if ($_ -match '^([^=]+)=(.+)$') { $stack[$matches[1]] = $matches[2] }
}
$account = @{email='runtime@runtime.local';password=('rt-' + $stack.LOCAL_ADMIN_PASSWORD)}
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body ($account | ConvertTo-Json) -SkipHttpErrorCheck -ResponseHeadersVariable loginHeaders
if ($login.code -ne 0) {
    $register = $account.Clone(); $register.nickname = 'Local Runtime'
    $result = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/users" -ContentType 'application/json' -Body ($register | ConvertTo-Json) -SkipHttpErrorCheck
    if ($result.code -ne 0) { throw "RAGFlow registration failed (code $($result.code))." }
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body ($account | ConvertTo-Json) -SkipHttpErrorCheck -ResponseHeadersVariable loginHeaders
}
if ($login.code -ne 0 -or -not $loginHeaders.Authorization) { throw 'RAGFlow login failed.' }
$headers = @{Authorization=[string]($loginHeaders.Authorization | Select-Object -First 1)}
function Invoke-Ragflow([string]$Method, [string]$Path, $Body = $null) {
    $params = @{Method=$Method;Uri="$BaseUrl/api/v1$Path";Headers=$headers;ContentType='application/json';SkipHttpErrorCheck=$true;TimeoutSec=90}
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Depth 12 -Compress }
    $result = Invoke-RestMethod @params
    if ($result.code -ne 0) { throw "RAGFlow $Method $Path failed (code $($result.code)): $($result.message)" }
    return $result.data
}
$provider = 'OpenAI-API-Compatible'
$providers = @(Invoke-Ragflow GET '/providers')
if (-not ($providers | Where-Object { $_.provider_name -eq $provider -or $_.name -eq $provider })) {
    Invoke-Ragflow PUT '/providers' @{provider_name=$provider} | Out-Null
}
$instances = @(Invoke-Ragflow GET "/providers/$provider/instances")
if (-not ($instances | Where-Object { $_.instance_name -eq 'local-embedding' -or $_.name -eq 'local-embedding' })) {
    Invoke-Ragflow POST "/providers/$provider/instances" @{
        instance_name='local-embedding';api_key='local-embedding';base_url='http://embedding:80/v1'
        model_info=@(@{model_name='BAAI/bge-small-zh-v1.5';model_type=@('embedding');max_tokens=512;extra=@{max_dimension=512}})
    } | Out-Null
}
$models = @(Invoke-Ragflow GET '/models?type=embedding')
$model = $models | Where-Object { $_.instance_name -eq 'local-embedding' } | Select-Object -First 1
if (-not $model.model_id) { throw 'RAGFlow did not return the configured embedding model ID.' }
$keys = @(Invoke-Ragflow GET '/system/keys')
$key = $keys | Select-Object -First 1
if (-not $key.token) { $key = Invoke-Ragflow POST '/system/keys' @{} }
if (-not $key.token) { throw 'RAGFlow did not return an API token.' }
$secretDir = Join-Path $projectRoot '.runtime/secrets'
New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
ConvertTo-SecureString -String $key.token -AsPlainText -Force | ConvertFrom-SecureString |
    Set-Content -LiteralPath (Join-Path $secretDir 'ragflow.dpapi')
$headers = @{Authorization="Bearer $($key.token)"}
$datasets = Invoke-Ragflow GET '/datasets?name=runtime-company'
$dataset = @($datasets.datasets; $datasets | Where-Object { $_.name -eq 'runtime-company' }) | Where-Object { $_.name -eq 'runtime-company' } | Select-Object -First 1
if (-not $dataset.id) {
    $dataset = Invoke-Ragflow POST '/datasets' @{name='runtime-company';parse_type=1;parser_id='general';embedding_model=$model.model_id}
}
if (-not $dataset.id) { throw 'RAGFlow did not return a dataset ID.' }
@{baseUrl=$BaseUrl;datasetId=$dataset.id;modelId=$model.model_id;account=$account.email} | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $projectRoot '.runtime/ragflow-bootstrap.json')
Write-Output "RAGFlow ready: dataset $($dataset.id), local BGE embedding; API key stored with Windows DPAPI."
