param([switch]$Matrix)

$ErrorActionPreference = "Stop"
if (-not $Matrix) {
  throw "Use -Matrix to run the benchmark matrix."
}

function Invoke-Native([string]$Description, [scriptblock]$Command) {
  # Docker and child PowerShell commands can write progress to stderr while succeeding, so exit code is authoritative.
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

$rows = @()
foreach ($configuration in @(
  @{ Workers = 1; BatchSize = 5 },
  @{ Workers = 2; BatchSize = 5 },
  @{ Workers = 3; BatchSize = 5 },
  @{ Workers = 3; BatchSize = 1 },
  @{ Workers = 3; BatchSize = 20 }
)) {
  Invoke-Native "load-test workers=$($configuration.Workers), batch size=$($configuration.BatchSize)" { powershell -ExecutionPolicy Bypass -File "$PSScriptRoot/load-test.ps1" -Workers $configuration.Workers -BatchSize $configuration.BatchSize } | Out-Null
  if (-not (Test-Path "results/benchmarks.csv")) {
    throw "Benchmark completed without creating results/benchmarks.csv; stopping the matrix."
  }
  $rows += Import-Csv "results/benchmarks.csv" | Select-Object -Last 1
}

Write-Host "`n| Jobs | Workers | Batch size | Window seconds | Jobs/sec | Lock waits | Avg CPU % | Avg memory MiB | Success | Dead | Attempts != 1 |"
Write-Host "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
$rows | ForEach-Object {
  Write-Host "| $($_.jobs) | $($_.workers) | $($_.batch_size) | $($_.window_seconds) | $($_.jobs_per_second) | $($_.row_lock_waits_delta) | $($_.average_cpu_percent) | $($_.average_memory_mib) | $($_.success) | $($_.dead) | $($_.attempts_not_one) |"
}
