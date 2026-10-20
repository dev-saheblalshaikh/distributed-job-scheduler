param(
  [string]$ApiUrl = "http://localhost:8080",
  [string]$PrometheusUrl = "http://localhost:9090",
  [string]$GrafanaUrl = "http://localhost:3000",
  [string]$GrafanaUser = "admin",
  [string]$GrafanaPassword = "admin",
  [switch]$IncludeCrashTest
)

$ErrorActionPreference = "Stop"
$results = @()

foreach ($endpoint in @(
  @{ Name = "API"; Uri = "$ApiUrl/actuator/health" },
  @{ Name = "Prometheus"; Uri = "$PrometheusUrl/-/ready" },
  @{ Name = "Grafana"; Uri = "$GrafanaUrl/api/health" }
)) {
  try {
    Invoke-RestMethod -Uri $endpoint.Uri -Method Get | Out-Null
  } catch {
    Write-Error "Stack is not running. Start it with: docker compose up --build --scale worker=3 -d"
    exit 2
  }
}

$targetsReady = Wait-Until { (Get-PromValue 'count(up == 1)') -eq 4 } 60
if (-not $targetsReady) {
  Write-Error "Stack is not running. Start it with: docker compose up --build --scale worker=3 -d"
  exit 2
}

function Add-Result([string]$Name, [bool]$Passed, [string]$Detail) {
  $script:results += [pscustomobject]@{ Check = $Name; Result = if ($Passed) { "PASS" } else { "FAIL" }; Detail = $Detail }
  Write-Host ("{0}: {1} - {2}" -f $Name, $script:results[-1].Result, $Detail)
}

function Get-PromValue([string]$Query) {
  $encoded = [Uri]::EscapeDataString($Query)
  $response = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$encoded" -Method Get
  if ($response.status -ne "success" -or $response.data.result.Count -eq 0) { return $null }
  return [double]$response.data.result[0].value[1]
}

function Get-PromSum([string]$Query) {
  $value = Get-PromValue $Query
  if ($null -eq $value) { return 0.0 }
  return $value
}

function Wait-Until([scriptblock]$Condition, [int]$TimeoutSeconds) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    try { if (&$Condition) { return $true } } catch { }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  return $false
}

function Submit-Job([string]$Type, [hashtable]$Payload, [int]$MaxAttempts = 3) {
  $body = @{ jobType = $Type; payload = $Payload; maxAttempts = $MaxAttempts } | ConvertTo-Json -Depth 5
  $null = Invoke-RestMethod -Uri "$ApiUrl/api/jobs" -Method Post -ContentType "application/json" -Body $body
}

function Submit-JobAndGetResponse([string]$Type, [hashtable]$Payload, [int]$MaxAttempts = 3) {
  $body = @{ jobType = $Type; payload = $Payload; maxAttempts = $MaxAttempts } | ConvertTo-Json -Depth 5
  return Invoke-RestMethod -Uri "$ApiUrl/api/jobs" -Method Post -ContentType "application/json" -Body $body
}

function Get-DbLockedBy([long]$JobId) {
  $password = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "root" }
  $query = "select coalesce(locked_by,'') from jobs where id=$JobId"
  return (docker compose exec -T mysql mysql "-uroot" "-p$password" jobs -N -s -e $query).Trim()
}

function Get-WorkerContainerForId([string]$LockedBy) {
  foreach ($containerId in @(docker compose ps -q worker)) {
    if (-not $containerId) { continue }
    $hostname = (docker inspect -f "{{.Config.Hostname}}" $containerId).Trim()
    if ("worker-$hostname" -eq $LockedBy) { return $containerId }
  }
  return $null
}

function Get-Job([long]$JobId) {
  return Invoke-RestMethod -Uri "$ApiUrl/api/jobs/$JobId" -Method Get
}

$up = Get-PromValue 'count(up)'
$upAll = Get-PromValue 'count(up == 1)'
Add-Result "up" ($up -eq 4 -and $upAll -eq 4) "expected 4 healthy series, got $up healthy of $upAll"

$grafanaHeaders = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$GrafanaUser`:$GrafanaPassword")) }
try {
  $search = Invoke-RestMethod -Uri "$GrafanaUrl/api/search" -Headers $grafanaHeaders
  $dashboardFound = @($search | Where-Object { $_.title -eq "Distributed Job Scheduler" }).Count -gt 0
  $datasourceInfo = Invoke-RestMethod -Uri "$GrafanaUrl/api/datasources/name/Prometheus" -Headers $grafanaHeaders
  $datasource = Invoke-RestMethod -Uri "$GrafanaUrl/api/datasources/uid/$($datasourceInfo.uid)/health" -Headers $grafanaHeaders
  Add-Result "Grafana" ($dashboardFound -and $datasource.message -match "OK|success") "dashboard found and datasource health checked"
} catch {
  Add-Result "Grafana" $false $_.Exception.Message
}

$successBefore = Get-PromSum 'sum(jobs_completed_total{result="success"})'
1..100 | ForEach-Object { Submit-Job "EMAIL" @{ to = "smoke-$_.example@example.com" } }
$s2Pending = 0
$s2Running = 0
$s2Done = Wait-Until {
  $s2Pending = Get-PromSum 'jobs_queue_depth{status="PENDING"}'
  $s2Running = Get-PromSum 'jobs_queue_depth{status="RUNNING"}'
  (Get-PromSum 'sum(jobs_completed_total{result="success"})') - $successBefore -eq 100 -and $s2Pending -eq 0 -and $s2Running -eq 0
} 60
$s2Pending = Get-PromSum 'jobs_queue_depth{status="PENDING"}'
$s2Running = Get-PromSum 'jobs_queue_depth{status="RUNNING"}'
Add-Result "S2" ($s2Done -and $s2Pending -eq 0 -and $s2Running -eq 0) "success delta=$((Get-PromSum 'sum(jobs_completed_total{result="success"})') - $successBefore), pending=$s2Pending, running=$s2Running"

$successBefore = Get-PromSum 'sum(jobs_completed_total{result="success"})'
# Fifteen-second jobs keep RUNNING visible across the five-second gauge refresh and scrape lag.
1..30 | ForEach-Object { Submit-Job "CHAOS" @{ sleepSeconds = 15 } }
$maxRunning = 0
for ($sample = 0; $sample -lt 20 -and $maxRunning -lt 10; $sample++) {
  $maxRunning = [Math]::Max($maxRunning, (Get-PromSum 'jobs_queue_depth{status="RUNNING"}'))
  if ($maxRunning -lt 10) {
    Start-Sleep -Seconds 2
  }
}
$s3Done = Wait-Until { (Get-PromSum 'sum(jobs_completed_total{result="success"})') - $successBefore -eq 30 -and (Get-PromSum 'jobs_queue_depth{status="RUNNING"}') -eq 0 } 120
Add-Result "S3" ($maxRunning -ge 10 -and $s3Done) "max running=$maxRunning"

$p95 = Get-PromValue 'histogram_quantile(0.95, sum by (le) (rate(job_execution_seconds_bucket[2m])))'
Add-Result "P95" ($null -ne $p95 -and -not [double]::IsNaN($p95) -and $p95 -ge 1.5) "p95=$p95"

$retryBefore = Get-PromSum 'sum(jobs_completed_total{result="retry"})'
$deadBefore = Get-PromSum 'sum(jobs_completed_total{result="dead"})'
1..5 | ForEach-Object { Submit-Job "CHAOS" @{ fail = $true } 3 }
$s4Done = Wait-Until { (Get-PromSum 'sum(jobs_completed_total{result="retry"})') - $retryBefore -eq 10 -and (Get-PromSum 'sum(jobs_completed_total{result="dead"})') - $deadBefore -eq 5 } 90
Add-Result "S4" $s4Done "retry delta=$((Get-PromSum 'sum(jobs_completed_total{result="retry"})') - $retryBefore), dead delta=$((Get-PromSum 'sum(jobs_completed_total{result="dead"})') - $deadBefore)"

$retryBefore = Get-PromSum 'sum(jobs_completed_total{result="retry"})'
$deadBefore = Get-PromSum 'sum(jobs_completed_total{result="dead"})'
1..3 | ForEach-Object { Submit-Job "NOPE" @{} }
$s5Done = Wait-Until { (Get-PromSum 'sum(jobs_completed_total{result="dead"})') - $deadBefore -eq 3 } 30
Add-Result "S5" ($s5Done -and (Get-PromSum 'sum(jobs_completed_total{result="retry"})') - $retryBefore -eq 0) "unknown jobs are dead without retry"

$oldest = Get-PromValue 'jobs_oldest_pending_age_seconds'
$recoveries = Get-PromSum 'sum(jobs_lease_recovered_total)'
Add-Result "queue gauges" ($null -ne $oldest -and $null -ne $recoveries) "oldest=$oldest recoveries=$recoveries"

if ($IncludeCrashTest) {
  $recoveryBefore = Get-PromSum 'sum(jobs_lease_recovered_total)'
  $crashJob = Submit-JobAndGetResponse "CHAOS" @{ sleepSeconds = 20 } 3
  $crashJobId = [long]$crashJob.id
  $containerId = $null
  $lockDeadline = (Get-Date).AddSeconds(30)
  do {
    Start-Sleep -Seconds 2
    $lockedBy = Get-DbLockedBy $crashJobId
    $containerId = Get-WorkerContainerForId $lockedBy
  } while (-not $containerId -and (Get-Date) -lt $lockDeadline)
  if ($containerId) {
    docker kill $containerId | Out-Null
    $s6Done = Wait-Until {
      $currentJob = Get-Job $crashJobId
      $recoveryDelta = (Get-PromSum 'sum(jobs_lease_recovered_total)') - $recoveryBefore
      $recoveryDelta -ge 1 -and $currentJob.status -eq "SUCCESS" -and $currentJob.attempts -eq 2
    } 120
    $finalJob = Get-Job $crashJobId
    Add-Result "S6" $s6Done "recovery delta=$((Get-PromSum 'sum(jobs_lease_recovered_total)') - $recoveryBefore), status=$($finalJob.status), attempts=$($finalJob.attempts)"
  } else {
    Add-Result "S6" $false "could not find a worker container holding job $crashJobId"
  }
}

Write-Host "`nSummary"
$results | Format-Table -AutoSize
if (@($results | Where-Object Result -eq "FAIL").Count -gt 0) { exit 1 }
exit 0
