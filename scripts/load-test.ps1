param(
  [int]$Workers = 3,
  [int]$BatchSize = 5,
  [int]$Jobs = 5000,
  [int]$SleepMillis = 50,
  [string]$OutFile = "results/benchmarks.csv",
  [switch]$Quick
)

$ErrorActionPreference = "Stop"
$database = "jobs"
$rootPassword = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "root" }
$timeoutSeconds = if ($Quick) { 120 } else { 900 }
if ($Quick) { $Jobs = 200 }

function Invoke-Native([string]$Description, [scriptblock]$Command) {
  # Docker writes normal progress to stderr, so inspect the exit code instead of treating stderr as failure.
  $stdoutPath = [IO.Path]::GetTempFileName()
  $stderrPath = [IO.Path]::GetTempFileName()
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    & $Command 1> $stdoutPath 2> $stderrPath
    $exitCode = $LASTEXITCODE
    $stdout = if (Test-Path $stdoutPath) { (Get-Content $stdoutPath | ForEach-Object { "$_" }) -join [Environment]::NewLine } else { "" }
    $stderr = if (Test-Path $stderrPath) { (Get-Content $stderrPath | ForEach-Object { "$_" }) -join [Environment]::NewLine } else { "" }
    $output = ($stdout + $stderr).Trim()
    if ($exitCode -ne 0) {
      throw "$Description failed with exit code $exitCode.`n$output"
    }
    return [pscustomobject]@{ StdOut = $stdout; StdErr = $stderr }
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
  }
}

function Invoke-MySql([string]$Sql) {
  $description = "docker compose exec -T -e MYSQL_PWD mysql mysql -h 127.0.0.1 -uroot $database -N -s -e <sql>"
  $result = Invoke-Native $description { docker compose exec -T -e "MYSQL_PWD=$rootPassword" mysql mysql -h 127.0.0.1 -uroot $database -N -s -e $Sql }
  return $result.StdOut
}

function Invoke-Compose([string]$Description, [scriptblock]$Action) {
  Invoke-Native $Description $Action | Out-Null
}

function Wait-ForMySql {
  $deadline = (Get-Date).AddSeconds(120)
  do {
    try {
      # A fresh data directory first exposes a temporary socket-only server, so health status alone is insufficient.
      Invoke-MySql "select 1" | Out-Null
      return
    } catch {
      Start-Sleep -Seconds 2
    }
  } while ((Get-Date) -lt $deadline)
  throw "MySQL did not accept TCP connections within 120 seconds."
}

function Wait-ForApi {
  $deadline = (Get-Date).AddSeconds(120)
  do {
    try {
      $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get
      if ($health.status -eq "UP") { return }
    } catch {
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "API did not report UP within 120 seconds."
}

function Get-Stat([string]$Name) {
  $value = Invoke-MySql "SHOW GLOBAL STATUS LIKE '$Name';"
  return [long](($value -split "\s+")[-1])
}

function Get-ContainerStats {
  $rows = @()
  $mysqlIds = @((Invoke-Native "docker compose ps -q mysql" { docker compose ps -q mysql }).StdOut -split "\r?\n")
  $workerIds = @((Invoke-Native "docker compose ps -q worker" { docker compose ps -q worker }).StdOut -split "\r?\n")
  foreach ($containerId in $mysqlIds + $workerIds) {
    if (-not $containerId) { continue }
    $json = (Invoke-Native "docker stats --no-stream $containerId" { docker stats --no-stream --format "{{json .}}" $containerId }).StdOut | ConvertFrom-Json
    $cpu = [double](($json.CPUPerc -replace "%", "").Replace(",", "."))
    $memoryText = (($json.MemUsage -split "/")[0]).Trim()
    $memory = [double](($memoryText -replace "[^0-9\.\,]", "").Replace(",", "."))
    if ($memoryText -match "GiB") { $memory = $memory * 1024 }
    $rows += [pscustomobject]@{ Name = $json.Name; Cpu = $cpu; MemoryMiB = $memory }
  }
  return $rows
}

if ($Workers -lt 1 -or $BatchSize -lt 1 -or $Jobs -lt 1 -or $SleepMillis -lt 0) {
  throw "Workers, BatchSize, Jobs and SleepMillis must be positive, except SleepMillis may be zero."
}

try {
  Invoke-Compose "docker compose stop api prometheus grafana" { docker compose stop api prometheus grafana }
  Invoke-Compose "docker compose up -d mysql" { docker compose up -d mysql }
  Wait-ForMySql
  # Flyway runs when the application starts, so initialize the schema before direct database loading.
  Invoke-Compose "docker compose up -d api" { docker compose up -d api }
  Wait-ForApi
  Invoke-Compose "docker compose stop api" { docker compose stop api }
  Invoke-Compose "docker compose up -d --scale worker=0 worker" { docker compose up -d --scale worker=0 worker }
  Invoke-MySql "DELETE FROM jobs;" | Out-Null

  $sql = "SET SESSION cte_max_recursion_depth = $($Jobs + 1); INSERT INTO jobs (job_type, payload, status, attempts, max_attempts, run_at, created_at, updated_at) WITH RECURSIVE numbers AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM numbers WHERE n < $Jobs) SELECT 'CHAOS', JSON_OBJECT('sleepMillis', $SleepMillis), 'PENDING', 0, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), UTC_TIMESTAMP() FROM numbers;"
  Invoke-MySql $sql | Out-Null
  $waitsBefore = Get-Stat "Innodb_row_lock_waits"
  $lockTimeBefore = Get-Stat "Innodb_row_lock_time"
  $env:WORKER_BATCH_SIZE = $BatchSize
  $started = Get-Date
  Invoke-Compose "docker compose up -d --no-deps --scale worker=$Workers worker" { docker compose up -d --no-deps --scale worker=$Workers worker }
  Start-Sleep -Seconds 10
  $stats = @(Get-ContainerStats)

  $deadline = (Get-Date).AddSeconds($timeoutSeconds)
  $remaining = $Jobs
  do {
    Start-Sleep -Seconds 5
    $processed = [int](Invoke-MySql "SELECT COUNT(*) FROM jobs WHERE status IN ('SUCCESS', 'DEAD');")
    $remaining = $Jobs - $processed
    $elapsedSeconds = [int]((Get-Date) - $started).TotalSeconds
    Write-Host "processed $processed of $Jobs after $elapsedSeconds seconds"
  } while ($remaining -gt 0 -and (Get-Date) -lt $deadline)

  $waitsAfter = Get-Stat "Innodb_row_lock_waits"
  $lockTimeAfter = Get-Stat "Innodb_row_lock_time"
  $success = [int](Invoke-MySql "SELECT COUNT(*) FROM jobs WHERE status='SUCCESS';")
  $dead = [int](Invoke-MySql "SELECT COUNT(*) FROM jobs WHERE status='DEAD';")
  $badAttempts = [int](Invoke-MySql "SELECT COUNT(*) FROM jobs WHERE attempts <> 1;")
  $total = [int](Invoke-MySql "SELECT COUNT(*) FROM jobs;")
  $windowValue = Invoke-MySql "SELECT TIMESTAMPDIFF(MICROSECOND, MIN(locked_at), MAX(updated_at)) / 1000000.0 FROM jobs;"
  if ($total -eq 0 -or $success + $dead -eq 0 -or $null -eq $windowValue -or $windowValue -eq "NULL") {
    throw "no jobs were processed"
  }
  $windowSeconds = [double]$windowValue
  $jobsPerSecond = if ($windowSeconds -gt 0) { $Jobs / $windowSeconds } else { 0 }
  $averageCpu = if ($stats.Count -gt 0) { ($stats | Measure-Object Cpu -Average).Average } else { 0 }
  $averageMemory = if ($stats.Count -gt 0) { ($stats | Measure-Object MemoryMiB -Average).Average } else { 0 }
  $directory = Split-Path -Parent $OutFile
  if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
  $row = [pscustomobject]@{
    timestamp = (Get-Date).ToUniversalTime().ToString("o")
    jobs = $Jobs; workers = $Workers; batch_size = $BatchSize; sleep_millis = $SleepMillis
    window_seconds = [math]::Round($windowSeconds, 3); jobs_per_second = [math]::Round($jobsPerSecond, 3)
    row_lock_waits_delta = $waitsAfter - $waitsBefore; row_lock_time_delta = $lockTimeAfter - $lockTimeBefore
    average_cpu_percent = [math]::Round($averageCpu, 2); average_memory_mib = [math]::Round($averageMemory, 2)
    success = $success; dead = $dead; attempts_not_one = $badAttempts
  }
  $row | Export-Csv -Path $OutFile -NoTypeInformation -Append
  Write-Host "| $($row.jobs) | $($row.workers) | $($row.batch_size) | $($row.window_seconds) | $($row.jobs_per_second) | $($row.row_lock_waits_delta) | $($row.average_cpu_percent) | $($row.average_memory_mib) | $($row.success) | $($row.dead) | $($row.attempts_not_one) |"
  if ($remaining -gt 0 -or $total -ne $Jobs -or $success + $dead -ne $Jobs -or $badAttempts -ne 0) {
    throw "Benchmark correctness failure: jobs were lost, duplicated, or did not finish."
  }
} catch {
  Write-Error "Benchmark failed: $($_.Exception.Message)"
  exit 1
} finally {
  try { Invoke-Compose "docker compose stop worker" { docker compose stop worker } } catch { Write-Error "Cleanup failed: $($_.Exception.Message)" }
}
