<#
.SYNOPSIS
Collects a reproducible, lightweight AAOS performance snapshot without requiring Macrobenchmark
shell privileges. Evidence is written below app/build/performance (ignored by Git).

.DESCRIPTION
Macrobenchmark is the preferred before/after tool, but some stock Automotive images reject its
background-dexopt cancellation. This script records the same device-side launch and gfxinfo
signals as a safe fallback. It does not declare a performance pass; compare two timestamped runs
on the same image and build before drawing a conclusion.
#>

[CmdletBinding()]
param(
    [ValidateSet("emulator-5554")][string]$Serial = "emulator-5554",
    [string]$PackageName = "com.android.mysystemapp",
    [string]$Activity = "com.android.mysystemapp/.MainActivity",
    [ValidateSet("Doors", "Seats", "Climate", "Lighting", "Assistance", "Modes")]
    [string]$Group = "Assistance",
    [string]$ApkPath = "",
    [ValidateRange(1, 30)][int]$WarmupSeconds = 3,
    [ValidateRange(1, 10)][int]$InteractionRepeats = 3,
    [ValidateRange(1, 30)][int]$CaptureSeconds = 2
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot ".." )).Path
$resolvedApkPath = if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    Join-Path $root "app/build/outputs/apk/debug/app-debug.apk"
} else {
    (Resolve-Path $ApkPath).Path
}
$apkHash = $null
$apkSizeBytes = $null
if (Test-Path -LiteralPath $resolvedApkPath) {
    $apkFile = Get-Item -LiteralPath $resolvedApkPath
    $apkHash = (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $apkSizeBytes = $apkFile.Length
}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidence = Join-Path $root ("app/build/performance/{0}" -f $stamp)
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $maxAttempts = 6
    for ($attempt = 0; $attempt -lt $maxAttempts; $attempt++) {
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $output = @(& adb -s $Serial @Arguments 2>&1)
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($exitCode -eq 0) {
            return @($output)
        }
        $outputText = ($output -join " ")
        $transportFailure =
            $outputText -match "device offline|device unauthorized|no devices|cannot connect|closed|more than one"
        if ($attempt -lt ($maxAttempts - 1) -and $transportFailure) {
            & adb -s $Serial reconnect | Out-Null
            Start-Sleep -Seconds ([Math]::Min(5, 2 + $attempt))
            continue
        }
        throw "adb failed ($exitCode): adb -s $Serial $($Arguments -join ' ')`n$($output -join "`n")"
    }
}

function Get-UiHierarchy {
    # AAOS's headless system user cannot access the visible driver's FUSE /sdcard mount.
    Invoke-Adb @("shell", "uiautomator", "dump", "/data/local/tmp/mysystemapp-performance.xml") | Out-Null
    return ((Invoke-Adb @("shell", "cat", "/data/local/tmp/mysystemapp-performance.xml")) -join "`n")
}

function Dismiss-PlatformNotice {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        try {
            $xml = Get-UiHierarchy
            if ($xml -notmatch 'com\.google\.android\.car\.kitchensink|initial user notice') {
                return
            }
            $match = [regex]::Match(
                $xml,
                'text="Dismiss for now"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            )
            if (-not $match.Success) {
                $match = [regex]::Match(
                    $xml,
                    'text="Do not show again"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                )
            }
            if ($match.Success) {
                $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
                $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
                Invoke-Adb @("shell", "input", "tap", $x.ToString(), $y.ToString()) | Out-Null
                Start-Sleep -Milliseconds 500
                continue
            }
        } catch {
            # The notice can disappear while the hierarchy is being dumped. Retry the bounded
            # probe instead of treating a transient UiAutomator failure as an app metric.
        }
        Start-Sleep -Milliseconds 250
    }
}

function Wait-AppResumed {
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $activity = (Invoke-Adb @("shell", "dumpsys", "activity", "activities")) -join "`n"
        if (
            $activity -match "topResumedActivity=.*$([regex]::Escape($PackageName))/\.MainActivity" -and
            $activity -match "Task\{[^}]+A=.*$([regex]::Escape($PackageName)).*visible=true"
        ) {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Application '$PackageName' did not remain resumed for the performance snapshot."
}

function Assert-InstalledApkMatches {
    if ([string]::IsNullOrWhiteSpace($apkHash)) {
        return
    }
    $paths = @(
        Invoke-Adb @("shell", "pm", "path", "--user", "10", $PackageName) |
            Where-Object { $_ -match '^package:(.+\.apk)$' } |
            ForEach-Object { ([regex]::Match($_, '^package:(.+)$')).Groups[1].Value.Trim() }
    )
    $matches = foreach ($path in $paths) {
        $hashLine = (Invoke-Adb @("shell", "sha256sum", $path)) -join " "
        $installedHash = ([regex]::Match($hashLine, '^([0-9a-fA-F]{64})')).Groups[1].Value.ToLowerInvariant()
        if ($installedHash -eq $apkHash) { $path }
    }
    if (-not $matches) {
        throw "Installed APK hash does not match measured APK '$resolvedApkPath'. Install the exact artifact before measuring."
    }
}

function Get-NodeBounds {
    param([Parameter(Mandatory)]$Node)
    $match = [regex]::Match($Node.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) { throw "Invalid UI bounds: $($Node.bounds)" }
    return @($match.Groups[1..4] | ForEach-Object { [int]$_.Value })
}

function Select-WorkspaceGroup {
    $labels = @{
        Doors = @("Doors & glass", "Cửa & kính")
        Seats = @("Seats", "Ghế ngồi")
        Climate = @("Climate", "Điều hòa")
        Lighting = @("Lighting", "Đèn xe")
        Assistance = @("Assistance", "Hỗ trợ lái")
        Modes = @("Modes", "Chế độ")
    }
    $xml = [xml](Get-UiHierarchy)
    $node = $xml.SelectNodes("//node") | Where-Object { $_.'content-desc' -cin $labels[$Group] } | Select-Object -First 1
    if (-not $node) { throw "Workspace group '$Group' was not found in the actual UI." }
    $bounds = Get-NodeBounds $node
    Invoke-Adb @("shell", "input", "tap", ([int](($bounds[0] + $bounds[2]) / 2)).ToString(), ([int](($bounds[1] + $bounds[3]) / 2)).ToString()) | Out-Null
    Start-Sleep -Milliseconds 750
}

$deviceLine = (Invoke-Adb @("get-state") | Select-Object -First 1).ToString().Trim()
if ($deviceLine -ne "device") {
    throw "Device '$Serial' is not online (state='$deviceLine')."
}

if (((Invoke-Adb @("shell", "am", "get-current-user")) -join "").Trim() -ne "10") {
    throw "Expected the connected Automotive driver's visible user to be 10."
}
Assert-InstalledApkMatches
$launchOutput = Invoke-Adb @("shell", "am", "force-stop", "--user", "10", $PackageName)
Invoke-Adb @("shell", "pm", "enable", "--user", "10", $PackageName) | Out-Null
Invoke-Adb @("shell", "dumpsys", "gfxinfo", $PackageName, "reset") | Out-Null
$launchOutput = Invoke-Adb @(
    "shell", "am", "start", "--user", "10", "-W", "-f", "0x00020000", "-n", $Activity
)
$launchOutput | Set-Content -Encoding utf8 (Join-Path $evidence "am-start.txt")
$totalLine = $launchOutput | Where-Object { $_ -match "^TotalTime:" } | Select-Object -First 1
$totalMs = if ($totalLine -and $totalLine -match "TotalTime:\s*(\d+)") { [int]$Matches[1] } else { $null }

Dismiss-PlatformNotice
Wait-AppResumed
Assert-InstalledApkMatches
Select-WorkspaceGroup
# Cold-start timing is recorded above. Reset gfxinfo only after the route has settled so the
# frame sample describes steady-state interaction instead of a handful of startup stalls. This
# also makes the fallback comparable across two APKs when Macrobenchmark is unavailable.
Start-Sleep -Seconds $WarmupSeconds
$hierarchy = Get-UiHierarchy
$hierarchy | Set-Content -Encoding utf8 (Join-Path $evidence "interaction-ui.xml")
$xml = [xml]$hierarchy
$model = $xml.SelectSingleNode("//node[@content-desc='Vehicle model']")
if (-not $model) { throw "Vehicle model was not found; refusing to measure swipes against guessed coordinates." }
$bounds = Get-NodeBounds $model
$leftX = [int]($bounds[0] + ($bounds[2] - $bounds[0]) * 0.3)
$rightX = [int]($bounds[0] + ($bounds[2] - $bounds[0]) * 0.7)
$middleY = [int](($bounds[1] + $bounds[3]) / 2)
Invoke-Adb @("shell", "dumpsys", "gfxinfo", $PackageName, "reset") | Out-Null
for ($repeat = 0; $repeat -lt $InteractionRepeats; $repeat++) {
    Invoke-Adb @("shell", "input", "swipe", $leftX.ToString(), $middleY.ToString(), $rightX.ToString(), $middleY.ToString(), "700") | Out-Null
    Invoke-Adb @("shell", "input", "swipe", $rightX.ToString(), $middleY.ToString(), $leftX.ToString(), $middleY.ToString(), "700") | Out-Null
}
Start-Sleep -Seconds $CaptureSeconds
$gfx = Invoke-Adb @("shell", "dumpsys", "gfxinfo", $PackageName)
$gfx | Set-Content -Encoding utf8 (Join-Path $evidence "gfxinfo.txt")

$gfxText = $gfx -join "`n"
function First-Match([string]$Pattern) {
    $match = [regex]::Match($gfxText, $Pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

$frameCount = First-Match "Total frames rendered:\s*(\d+)"
$sampleQuality =
    if ($frameCount -and [int]$frameCount -ge 30) {
        "usable steady-state signal"
    } else {
        "insufficient frames; do not compare"
    }

$summary = [ordered]@{
    serial = $Serial
    package = $PackageName
    route = "workspace/$Group/model-orbit"
    group = $Group
    userId = 10
    timestamp = (Get-Date).ToUniversalTime().ToString("o")
    apkPath = $resolvedApkPath
    apkSha256 = $apkHash
    apkSizeBytes = $apkSizeBytes
    coldLaunchMs = $totalMs
    warmupSeconds = $WarmupSeconds
    interactionRepeats = $InteractionRepeats
    captureSeconds = $CaptureSeconds
    totalFrames = $frameCount
    jankyFrames = First-Match "(?m)^Janky frames:\s*(\d+)"
    jankyPercent = First-Match "(?m)^Janky frames:\s*\d+\s+\(([^)]+)\)"
    percentile50Ms = First-Match "(?m)^50th percentile:\s*(\d+)ms"
    percentile90Ms = First-Match "(?m)^90th percentile:\s*(\d+)ms"
    percentile95Ms = First-Match "(?m)^95th percentile:\s*(\d+)ms"
    percentile99Ms = First-Match "(?m)^99th percentile:\s*(\d+)ms"
    missedVsync = First-Match "(?m)^Number Missed Vsync:\s*(\d+)"
    sampleQuality = $sampleQuality
    note = "Device-side steady-state snapshot only; compare with a same-image baseline. Macrobenchmark remains preferred."
}
$summary | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $evidence "summary.json")
$summary | ConvertTo-Json
Write-Output "Evidence: $evidence"
