# Bring Tally up on a local kind cluster from Windows PowerShell. Same steps as k8s-up.sh, see ADR-0022.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$cluster = if ($env:CLUSTER) { $env:CLUSTER } else { 'tally' }
$overlay = if ($env:OVERLAY) { $env:OVERLAY } else { 'local' }
$tag = if ($env:TAG) { $env:TAG } else { $overlay }
$kindConfig = if ($env:KIND_CONFIG) { $env:KIND_CONFIG } else { 'deploy/k8s/kind/cluster.yaml' }

# Native tools do not throw on a non-zero exit in Windows PowerShell 5.1, so every call is checked.
function Invoke-Checked {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "k8s-up: $Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

foreach ($tool in 'docker', 'kind', 'kubectl') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "k8s-up: $tool is required" }
}
if (-not (Test-Path .env)) { throw 'k8s-up: .env is missing, copy .env.example to .env and fill it in' }

$envLines = Get-Content .env
$token = ($envLines | Where-Object { $_ -match '^TALLY_API_TOKEN=' } | Select-Object -First 1) -replace '^TALLY_API_TOKEN=', ''
$password = ($envLines | Where-Object { $_ -match '^POSTGRES_PASSWORD=' } | Select-Object -First 1) -replace '^POSTGRES_PASSWORD=', ''
if (-not $token -or -not $password) { throw 'k8s-up: set both TALLY_API_TOKEN and POSTGRES_PASSWORD in .env' }

$clusters = & kind get clusters
if ($clusters -notcontains $cluster) {
    Invoke-Checked kind @('create', 'cluster', '--name', $cluster, '--config', $kindConfig)
}
Invoke-Checked kubectl @('config', 'use-context', "kind-$cluster")

Invoke-Checked docker @('build', '--build-arg', "VITE_API_TOKEN=$token", '-t', "tally:$tag", '.')
Invoke-Checked kind @('load', 'docker-image', "tally:$tag", '--name', $cluster)

& kubectl create namespace tally --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw 'k8s-up: could not create the namespace' }
& kubectl -n tally create secret generic tally-secrets --from-env-file=.env --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw 'k8s-up: could not create the secret' }

if ($env:WITH_METRICS_SERVER -eq '1') {
    Invoke-Checked kubectl @('apply', '-f', 'https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml')
    & kubectl -n kube-system patch deployment metrics-server --type=json `
        -p '[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--kubelet-insecure-tls\"}]'
}

Invoke-Checked kubectl @('-n', 'tally', 'delete', 'job', 'tally-migrate', '--ignore-not-found')
$existed = & kubectl -n tally get deployment tally-api --ignore-not-found -o name
Invoke-Checked kubectl @('apply', '-k', "deploy/k8s/overlays/$overlay")
if ($existed) {
    Invoke-Checked kubectl @('-n', 'tally', 'rollout', 'restart', 'deployment/tally-api')
}

Invoke-Checked kubectl @('-n', 'tally', 'rollout', 'status', 'statefulset/tally-db', '--timeout=300s')
Invoke-Checked kubectl @('-n', 'tally', 'wait', '--for=condition=complete', 'job/tally-migrate', '--timeout=300s')
Invoke-Checked kubectl @('-n', 'tally', 'rollout', 'status', 'deployment/tally-api', '--timeout=300s')
foreach ($monitor in 'prometheus', 'grafana') {
    $found = & kubectl -n tally get deployment $monitor --ignore-not-found -o name
    if ($found) { Invoke-Checked kubectl @('-n', 'tally', 'rollout', 'status', "deployment/$monitor", '--timeout=300s') }
}

Write-Output ''
Write-Output 'Tally:      http://localhost:8080'
Write-Output 'Grafana:    http://localhost:3000   (anonymous viewer, dashboard "Tally")'
Write-Output 'Prometheus: http://localhost:9090'
