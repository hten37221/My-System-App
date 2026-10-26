<#
.SYNOPSIS
Collects real ART startup/baseline rules when AndroidX's in-runner compilation is rejected by AAOS.

.DESCRIPTION
Install the freshly built platform-signed nonMinifiedRelease target and baselineprofile runner
before running this script. It verifies the installed target hash, disables automatic installation
of an older embedded profile for driver user 10, clears ART profiles, runs separate startup and
workspace journeys, and exports ART's own human-readable profile. It never invents method rules.
Raw exports, instrumentation output and APK provenance are retained under app/build/runtime-profile.
The caller must own an exclusive emulator-5554 device window. Root adbd is needed on this image to
pull /data/misc/profman output; the application and test runner still run under their normal UIDs.
#>
[CmdletBinding()]
param(
    [ValidateSet("emulator-5554")][string]$Serial = "emulator-5554",
    [Parameter(Mandatory)][string]$TargetApk,
    [ValidateRange(1, 8)][int]$Iterations = 3
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetPackage = "com.android.mysystemapp"
$profileRunner = "com.android.mysystemapp.baselineprofile/androidx.test.runner.AndroidJUnitRunner"
$receiver = "$targetPackage/androidx.profileinstaller.ProfileInstallReceiver"
$journeyClass = "com.android.mysystemapp.baselineprofile.RuntimeProfileJourneyTest"
$resolvedApk = (Resolve-Path -LiteralPath $TargetApk).Path
$apkHash = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$evidence = Join-Path $repoRoot ("app/build/runtime-profile/" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& adb -s $Serial @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw "adb failed ($code): $($Arguments -join ' ')`n$($output -join "`n")" }
    return ($output -join "`n")
}

function Set-InstallerSkip {
    param([string]$Operation)
    $output = Invoke-Adb @("shell", "am", "broadcast", "--user", "10", "-a", "androidx.profileinstaller.action.SKIP_FILE", "-n", $receiver, "--es", "EXTRA_SKIP_FILE_OPERATION", $Operation)
    $output | Add-Content -Encoding utf8 (Join-Path $evidence "profile-installer.txt")
    $expected = if ($Operation -eq "WRITE_SKIP_FILE") { "result=10" } else { "result=11" }
    if (-not $output.Contains($expected)) { throw "ProfileInstaller skip operation failed: $output" }
}

function Invoke-Journey {
    param([string]$Method, [int]$Iteration)
    Invoke-Adb @("shell", "am", "force-stop", "--user", "10", $targetPackage) | Out-Null
    $output = Invoke-Adb @("shell", "am", "instrument", "--user", "10", "-w", "-r", "-e", "class", "$journeyClass#$Method", $profileRunner)
    $output | Set-Content -Encoding utf8 (Join-Path $evidence "$Method-$Iteration.txt")
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "Profile journey did not pass: $Method #$Iteration`n$output"
    }
    $save = Invoke-Adb @("shell", "am", "broadcast", "--user", "10", "-a", "androidx.profileinstaller.action.SAVE_PROFILE", "-n", $receiver)
    $save | Add-Content -Encoding utf8 (Join-Path $evidence "profile-save.txt")
    if (-not $save.Contains("result=12")) { throw "ART profile save failed: $save" }
    Start-Sleep -Milliseconds 1000
}

function Export-Rules {
    param([string]$Name)
    $output = Invoke-Adb @("shell", "pm", "dump-profiles", "--dump-classes-and-methods", $targetPackage)
    $output | Set-Content -Encoding utf8 (Join-Path $evidence "$Name-export.txt")
    $rawPath = Join-Path $evidence "$Name-raw.txt"
    Invoke-Adb @("pull", "/data/misc/profman/$targetPackage-primary.prof.txt", $rawPath) | Out-Null
    # Match AndroidX's target-P filtering: array qualifiers and inline caches cannot be embedded.
    $rules = @(Get-Content -LiteralPath $rawPath | Where-Object { $_ -match '^[HSP]*L[^;]+;' -and $_ -notmatch '\+' } | Sort-Object -CaseSensitive -Unique)
    if (-not ($rules | Where-Object { $_ -match 'Lcom/android/mysystemapp/' })) {
        throw "ART did not export first-party rules for $Name; generated sources were not changed."
    }
    return $rules
}

if ((Invoke-Adb @("get-state")).Trim() -ne "device") { throw "AVD is not online." }
if ((Invoke-Adb @("shell", "am", "get-current-user")).Trim() -ne "10") { throw "Expected visible Automotive driver user 10." }
$installedPath = (Invoke-Adb @("shell", "pm", "path", "--user", "10", $targetPackage)).Trim() -replace '^package:', ''
$installedHash = ((Invoke-Adb @("shell", "sha256sum", $installedPath)) -split '\s+')[0]
if ($installedHash -cne $apkHash) { throw "Installed target APK does not match $resolvedApk." }
$skipWritten = $false
try {
    Set-InstallerSkip "WRITE_SKIP_FILE"
    $skipWritten = $true
    Invoke-Adb @("shell", "am", "force-stop", "--user", "10", $targetPackage) | Out-Null
    $reset = Invoke-Adb @("shell", "cmd", "package", "compile", "--reset", $targetPackage)
    $reset | Set-Content -Encoding utf8 (Join-Path $evidence "art-reset.txt")
    if (-not $reset.Contains("Success")) { throw "ART reset rejected: $reset" }
    Invoke-Adb @("shell", "cmd", "package", "clear-app-profiles", $targetPackage) | Set-Content -Encoding utf8 (Join-Path $evidence "art-clear.txt")
    $compile = Invoke-Adb @("shell", "cmd", "package", "compile", "-f", "-m", "verify", $targetPackage)
    $compile | Set-Content -Encoding utf8 (Join-Path $evidence "art-verify.txt")
    if (-not $compile.Contains("Success")) { throw "ART verify rejected: $compile" }
    for ($i = 1; $i -le $Iterations; $i++) { Invoke-Journey "startup" $i }
    $startup = @(Export-Rules "startup")
    for ($i = 1; $i -le $Iterations; $i++) { Invoke-Journey "vehicleControls" $i }
    $baseline = @(@(Export-Rules "baseline") + $startup | Sort-Object -CaseSensitive -Unique)
    $destination = Join-Path $repoRoot "app/src/main/generated/baselineProfiles"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $destination "startup-prof.txt"), ($startup -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $destination "baseline-prof.txt"), ($baseline -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    [ordered]@{
        serial = $Serial
        userId = 10
        apkPath = $resolvedApk
        apkSha256 = $apkHash
        iterations = $Iterations
        collection = "ART runtime dump after clean-profile verified compilation; no AndroidX stability claim"
        startupRules = $startup.Count
        baselineRules = $baseline.Count
        startupSha256 = (Get-FileHash (Join-Path $destination "startup-prof.txt") -Algorithm SHA256).Hash.ToLowerInvariant()
        baselineSha256 = (Get-FileHash (Join-Path $destination "baseline-prof.txt") -Algorithm SHA256).Hash.ToLowerInvariant()
    } | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $evidence "summary.json")
    Write-Output "Fresh ART rules exported. Evidence: $evidence"
} finally {
    if ($skipWritten) { Set-InstallerSkip "DELETE_SKIP_FILE" }
    Invoke-Adb @("shell", "am", "force-stop", "--user", "10", $targetPackage) | Out-Null
}
