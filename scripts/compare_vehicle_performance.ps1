<#
.SYNOPSIS
Compares lightweight vehicle performance snapshots produced by measure_vehicle_performance.ps1.

.DESCRIPTION
This is a reproducibility/reporting helper, not a benchmark gate. It requires the baseline and
candidate samples to use the same serial and route, reports medians, and deliberately labels the
result as SIGNAL_ONLY_NOT_PASS because gfxinfo/TotalTime on an emulator are noisy. Macrobenchmark
or Perfetto remains the authoritative performance gate when the image permits it.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$BaselineEvidence,
    [Parameter(Mandatory = $true)]
    [string[]]$CurrentEvidence,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"

function Read-Samples {
    param([Parameter(Mandatory = $true)][string[]]$Directories)
    $samples = @()
    foreach ($directory in $Directories) {
        $resolved = (Resolve-Path -LiteralPath $directory).Path
        $summaryPath = Join-Path $resolved "summary.json"
        if (-not (Test-Path -LiteralPath $summaryPath)) {
            throw "Performance summary not found: $summaryPath"
        }
        $samples += (Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json)
    }
    if ($samples.Count -eq 0) {
        throw "No performance summaries were supplied."
    }
    return @($samples)
}

function Get-Number {
    param($Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        return $null
    }
    $text = ([string]$Value).Trim().TrimEnd('%')
    $number = 0.0
    if ([double]::TryParse($text, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        return $number
    }
    return $null
}

function Get-Median {
    param([object[]]$Values)
    $numbers = @($Values | ForEach-Object { Get-Number $_ } | Where-Object { $null -ne $_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return $null }
    $middle = [math]::Floor($numbers.Count / 2)
    if (($numbers.Count % 2) -eq 1) { return [double]$numbers[$middle] }
    return ([double]$numbers[$middle - 1] + [double]$numbers[$middle]) / 2.0
}

function Get-Metrics {
    param([object[]]$Samples)
    return [ordered]@{
        sampleCount = $Samples.Count
        coldLaunchMsMedian = Get-Median ($Samples | ForEach-Object { $_.coldLaunchMs })
        jankyPercentMedian = Get-Median ($Samples | ForEach-Object { $_.jankyPercent })
        percentile90MsMedian = Get-Median ($Samples | ForEach-Object { $_.percentile90Ms })
        percentile95MsMedian = Get-Median ($Samples | ForEach-Object { $_.percentile95Ms })
        missedVsyncMedian = Get-Median ($Samples | ForEach-Object { $_.missedVsync })
    }
}

$baseline = @(Read-Samples $BaselineEvidence)
$current = @(Read-Samples $CurrentEvidence)
# PowerShell unwraps a one-element function result to a scalar. Normalize both sides before
# concatenating so a valid single-sample comparison cannot fail with an op_Addition error.
$all = @($baseline) + @($current)
$serials = @($all | ForEach-Object { $_.serial } | Select-Object -Unique)
$routes = @($all | ForEach-Object { $_.route } | Select-Object -Unique)
if ($serials.Count -ne 1) { throw "Baseline and current samples must use one AVD serial; found: $($serials -join ', ')" }
if ($routes.Count -ne 1) { throw "Baseline and current samples must use one route; found: $($routes -join ', ')" }

$baselineHashes = @($baseline | ForEach-Object { $_.apkSha256 } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Unique)
$currentHashes = @($current | ForEach-Object { $_.apkSha256 } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Unique)
$result = [ordered]@{
    serial = $serials[0]
    route = $routes[0]
    baselineApkSha256 = if ($baselineHashes.Count -ge 1) { $baselineHashes[0] } else { $null }
    currentApkSha256 = if ($currentHashes.Count -ge 1) { $currentHashes[$currentHashes.Count - 1] } else { $null }
    baseline = Get-Metrics $baseline
    current = Get-Metrics $current
    performanceClaim = "SIGNAL_ONLY_NOT_PASS"
    note = "Emulator gfxinfo/TotalTime is noisy; this comparison does not replace Macrobenchmark or Perfetto evidence."
}

$json = $result | ConvertTo-Json -Depth 6
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $json | Set-Content -LiteralPath $OutputPath -Encoding utf8
}
Write-Output $json
