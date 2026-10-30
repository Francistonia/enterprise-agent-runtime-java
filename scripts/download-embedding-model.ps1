# Fetch a pinned public embedding model in parallel ranges and verify its published LFS SHA-256.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelDir = Join-Path $projectRoot '.runtime/models/bge-small'
$chunkDir = Join-Path $modelDir 'chunks'
New-Item -ItemType Directory -Path $chunkDir -Force | Out-Null
$revision = '7999e1d3359715c523056ef9478215996d62a620'
$baseUrl = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/$revision"
$expectedHash = '354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026'
$target = Join-Path $modelDir 'model.safetensors'
if ((Test-Path -LiteralPath $target) -and ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expectedHash) -and
    (@('config.json','tokenizer.json','tokenizer_config.json','special_tokens_map.json') | Where-Object { -not (Test-Path -LiteralPath (Join-Path $modelDir $_)) }).Count -eq 0) {
    Write-Output 'Embedding model already verified.'
    exit 0
}
$total = 95827648L
$partSize = [long][math]::Ceiling($total / 8.0)
$jobs = @()
for ($index=0; $index -lt 8; $index++) {
    $start = $index * $partSize
    $end = [math]::Min($total - 1, $start + $partSize - 1)
    $part = Join-Path $chunkDir "$index.part"
    $jobs += Start-ThreadJob -ArgumentList $part,$start,$end,"$baseUrl/model.safetensors" -ScriptBlock {
        param($Part,$Start,$End,$Url)
        if ((Test-Path -LiteralPath $Part) -and ((Get-Item -LiteralPath $Part).Length -eq ($End-$Start+1))) {
            return [PSCustomObject]@{Part=$Part;Ok=$true}
        }
        for ($attempt=0; $attempt -lt 8; $attempt++) {
            $existing = if (Test-Path -LiteralPath $Part) { (Get-Item -LiteralPath $Part).Length } else { 0L }
            if ($existing -eq ($End-$Start+1)) { break }
            $actualStart = $Start + $existing
            $segment = "$Part.segment"
            $code = & curl.exe --location --fail --silent --show-error --connect-timeout 10 --max-time 120 --range "$actualStart-$End" --output $segment --write-out '%{http_code}' $Url 2> "$Part.log"
            if ("$code".Trim() -eq '206' -and (Test-Path -LiteralPath $segment)) {
                $length = (Get-Item -LiteralPath $segment).Length
                if ($length -gt 0 -and $length -le ($End-$actualStart+1)) {
                    $output = [IO.File]::Open($Part,[IO.FileMode]::Append,[IO.FileAccess]::Write)
                    $input = [IO.File]::OpenRead($segment)
                    try { $input.CopyTo($output) } finally { $input.Dispose(); $output.Dispose() }
                }
            }
        }
        [PSCustomObject]@{Part=$Part;Ok=((Test-Path -LiteralPath $Part) -and (Get-Item -LiteralPath $Part).Length -eq ($End-$Start+1))}
    }
}
$results = $jobs | Receive-Job -Wait -AutoRemoveJob
if ($results | Where-Object { -not $_.Ok }) { throw 'One or more model ranges failed; rerun to reuse completed ranges.' }
$stream = [IO.File]::Create($target)
try {
    for ($index=0; $index -lt 8; $index++) {
        $partStream = [IO.File]::OpenRead((Join-Path $chunkDir "$index.part"))
        try { $partStream.CopyTo($stream) } finally { $partStream.Dispose() }
    }
} finally { $stream.Dispose() }
if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Embedding model SHA-256 does not match the pinned revision.'
}
foreach ($name in @('config.json','tokenizer.json','tokenizer_config.json','special_tokens_map.json')) {
    & curl.exe --location --fail --silent --show-error --connect-timeout 10 --max-time 60 --retry 2 --output (Join-Path $modelDir $name) "$baseUrl/$name"
    if ($LASTEXITCODE -ne 0) { throw "Model metadata download failed: $name" }
}
Write-Output 'Embedding model and metadata downloaded; SHA-256 verified.'
