<#
.SYNOPSIS
Build, install and verify Vehicle Control on emulator-5554 only.
.DESCRIPTION
Runs all debug unit tests and every app instrumentation class (including the live property
matrix). Instrumentation temporarily changes vehicle properties and driving state; tests own
their finally-block restoration. Write-only seat memory actions cannot be read back or undone.
Does not modify the system image, VHAL configuration, Android Settings, or other adb devices.
Stores an attributable evidence bundle under build/vehicle-evidence/<timestamp>.
.EXAMPLE
.\scripts\verify_automotive_avd.ps1 -Serial emulator-5554
.EXAMPLE
.\scripts\verify_automotive_avd.ps1 -Serial emulator-5554 -SkipBuild -SkipInstall
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('emulator-5554')]
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'com.android.mysystemapp'
$runner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"
$timestamp = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(7)).ToString('yyyyMMdd-HHmmss-zzz').Replace(':', '')
$evidenceRoot = Join-Path $projectRoot "build\vehicle-evidence\$timestamp"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$summary = [ordered]@{
    startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    serial = $Serial
    status = 'RUNNING'
    buildSkipped = [bool]$SkipBuild
    installSkipped = [bool]$SkipInstall
    instrumentation = 'NOT_RUN'
    note = 'Emulator verification; startup/gfx snapshots are diagnostic, not production performance claims.'
}

function Save-Text {
    param([string]$Name, [object[]]$Lines)
    [IO.File]::WriteAllText((Join-Path $evidenceRoot $Name), ($Lines -join "`n"), $utf8)
}

function Invoke-Native {
    param([string]$Executable, [string[]]$Arguments, [string]$LogName)
    # Native tools sometimes use stderr for ordinary progress. Exit code is authoritative.
    $ErrorActionPreference = 'Continue'
    $output = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
    if ($LogName) { Save-Text $LogName $output }
    if ($code -ne 0) { throw "$Executable failed ($code). $($output | Select-Object -Last 12 | Out-String)" }
    return $output
}

function Invoke-Adb {
    param([string[]]$Arguments, [string]$LogName)
    # Every command, including get-state/install/pull, is explicitly scoped to this serial.
    Invoke-Native -Executable $adb -Arguments (@('-s', $Serial) + $Arguments) -LogName $LogName
}

function Get-CertDigest {
    param([string]$Apk, [string]$LogName)
    $output = Invoke-Native -Executable $apksigner -Arguments @('verify', '--print-certs', $Apk) -LogName $LogName
    $match = [regex]::Match(($output -join "`n"), '(?m)^(?:Signer #1|V\d+(?:\.\d+)* Signer:) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$')
    if (-not $match.Success) { throw "No APK signer SHA-256 in $LogName" }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Pull-Verification {
    param([string]$LogName)
    $errors = @()
    foreach ($path in @($remoteVerification, $remoteVerificationStorage, $remoteVerificationMedia) | Select-Object -Unique) {
        try {
            Invoke-Adb -Arguments @('pull', $path, $evidenceRoot) -LogName $LogName | Out-Null
            return
        } catch { $errors += $_.ToString() }
    }
    throw "Cannot pull verification evidence through either storage view: $($errors -join '; ')"
}

function Assert-InstalledApk {
    param([string]$ExpectedHash)
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $packageName) -LogName 'package.txt') -join "`n"
    if ($dump -notmatch 'versionCode=52\b' -or $dump -notmatch 'versionName=3\.0\.0\b') {
        throw 'Installed application must be Vehicle Control versionCode 52 / versionName 3.0.0.'
    }
    if ($dump -notmatch '(?:pkgFlags|flags)=\[[^\]]*\bSYSTEM\b') { throw 'Installed application is not a SYSTEM application.' }
    if ($dump -notmatch 'privateFlags=\[[^\]]*\b(?:PRIVATE_FLAG_)?PRIVILEGED\b') { throw 'Installed application lacks the PRIVILEGED flag.' }
    $paths = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', '--user', $userId, $packageName))
    $candidates = @()
    foreach ($line in $paths) {
        if ($line -notmatch '^package:(.+\.apk)\s*$') { continue }
        $path = $Matches[1].Trim()
        $hashOutput = (Invoke-Adb -Arguments @('shell', 'sha256sum', $path)) -join ' '
        $hash = [regex]::Match($hashOutput, '\b([a-fA-F0-9]{64})\b').Groups[1].Value.ToLowerInvariant()
        $candidates += [ordered]@{ path = $path; sha256 = $hash }
    }
    if (-not ($candidates | Where-Object { $_.sha256 -eq $ExpectedHash })) {
        throw 'Installed APK SHA-256 does not match the current local debug APK.'
    }
    return $candidates
}

New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
Push-Location $projectRoot
try {
    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdk) -and (Test-Path -LiteralPath 'local.properties')) {
        $sdkLine = Get-Content -LiteralPath 'local.properties' | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $adb = $adbCommand.Source }
    elseif ($sdk) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }
    else { throw 'adb is unavailable. Configure ANDROID_HOME or local.properties.' }
    if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = Split-Path -Parent (Split-Path -Parent $adb) }
    $apksigner = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $apksigner) { throw 'Android build-tools/apksigner.bat is required for platform-signature verification.' }

    if (((Invoke-Adb -Arguments @('get-state')) -join '').Trim() -ne 'device') { throw "$Serial is not online." }
    if (((Invoke-Adb -Arguments @('shell', 'getprop', 'sys.boot_completed')) -join '').Trim() -ne '1') { throw 'AVD has not completed boot.' }
    $features = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'features') -LogName 'features.txt'
    if (($features -join "`n") -notmatch 'feature:android.hardware.type.automotive') { throw 'Target is not an Automotive device.' }
    if (((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim() -ne '1') { throw 'Target is not an emulator.' }
    $userId = ((Invoke-Adb -Arguments @('shell', 'am', 'get-current-user')) -join '').Trim()
    if ($userId -notmatch '^\d+$') { throw "Invalid current Android user: $userId" }
    $summary.userId = [int]$userId
    $summary.fingerprint = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.fingerprint')) -join '').Trim()
    $summary.api = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
    $summary.gitHead = (Invoke-Native -Executable 'git' -Arguments @('rev-parse', 'HEAD')) -join ''
    $summary.gitBranch = (Invoke-Native -Executable 'git' -Arguments @('branch', '--show-current')) -join ''
    Invoke-Native -Executable 'git' -Arguments @('status', '--short') -LogName 'git-status.txt' | Out-Null
    Invoke-Adb -Arguments @('shell', 'wm', 'size') -LogName 'display-size.txt' | Out-Null
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'car_service') -LogName 'car-service-before.txt' | Out-Null

    if (-not $SkipBuild) {
        Write-Host 'Building APKs and running all debug unit tests...'
        Invoke-Native -Executable '.\gradlew.bat' -Arguments @(
            ':app:assembleDebug', ':app:assembleDebugAndroidTest', 'testDebugUnitTest',
            '--no-daemon', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process'
        ) -LogName 'build-and-unit-tests.txt' | Out-Null
        # Restrict collection to included modules: retired Settings modules can retain old XML.
        $includedModules = [regex]::Matches((Get-Content -LiteralPath 'settings.gradle.kts' -Raw), 'include\("(:[^\"]+)"\)')
        $unitReports = @()
        foreach ($module in $includedModules) {
            $moduleName = $module.Groups[1].Value.Trim(':')
            $resultFolder = Join-Path ($moduleName.Replace(':', '\')) 'build\test-results\testDebugUnitTest'
            if (-not (Test-Path -LiteralPath $resultFolder)) { continue }
            $destination = Join-Path $evidenceRoot ("unit-tests\" + $moduleName.Replace(':', '_'))
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            foreach ($report in (Get-ChildItem -LiteralPath $resultFolder -Filter 'TEST-*.xml')) {
                [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
                $unitReports += [ordered]@{
                    module = $moduleName
                    suite = $xml.testsuite.name
                    tests = [int]$xml.testsuite.tests
                    failures = [int]$xml.testsuite.failures
                    errors = [int]$xml.testsuite.errors
                    skipped = [int]$xml.testsuite.skipped
                }
                Copy-Item -LiteralPath $report.FullName -Destination $destination
            }
        }
        Save-Text 'unit-tests.json' @($unitReports | ConvertTo-Json -Depth 5)
        $unitTestCount = 0
        foreach ($report in $unitReports) { $unitTestCount += [int]$report['tests'] }
        $summary.unitTests = $unitTestCount
        if ($unitReports.Count -eq 0 -or @($unitReports | Where-Object { $_.failures -gt 0 -or $_.errors -gt 0 -or $_.skipped -gt 0 }).Count -gt 0) {
            throw 'Included-module unit tests are absent, failed, or skipped.'
        }
    }
    $appApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    foreach ($apk in @($appApk, $testApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Missing APK: $apk" }
    }
    $summary.apkSha256 = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $summary.testApkSha256 = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $artifacts = Join-Path $evidenceRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
    Copy-Item -LiteralPath $appApk, $testApk -Destination $artifacts
    $platformPath = (Invoke-Adb -Arguments @('shell', 'pm', 'path', 'android') |
        Where-Object { $_ -match '^package:' } | Select-Object -First 1) -replace '^package:', ''
    if ([string]::IsNullOrWhiteSpace($platformPath)) { throw 'Cannot resolve installed android framework APK.' }
    $platformApk = Join-Path $evidenceRoot 'platform-framework.apk'
    Invoke-Adb -Arguments @('pull', $platformPath.Trim(), $platformApk) -LogName 'platform-pull.txt' | Out-Null
    $summary.platformCertificateSha256 = Get-CertDigest $platformApk 'platform-certificate.txt'
    $summary.appCertificateSha256 = Get-CertDigest $appApk 'app-certificate.txt'
    if ($summary.appCertificateSha256 -ne $summary.platformCertificateSha256) { throw 'App certificate differs from the connected image platform certificate.' }

    if (-not $SkipInstall) {
        Write-Host 'Installing platform-signed app and instrumentation APKs...'
        Invoke-Adb -Arguments @('install', '-r', $appApk) -LogName 'install-app.txt' | Out-Null
        Invoke-Adb -Arguments @('install', '-r', '-t', $testApk) -LogName 'install-tests.txt' | Out-Null
    }
    $summary.installedApks = @(Assert-InstalledApk $summary.apkSha256)
    $testPaths = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', '--user', $userId, "$packageName.test"))
    $testHashMatched = $false
    foreach ($line in $testPaths) {
        if ($line -notmatch '^package:(.+\.apk)\s*$') { continue }
        $testPath = $Matches[1].Trim()
        $hashLine = (Invoke-Adb -Arguments @('shell', 'sha256sum', $testPath)) -join ' '
        if ($hashLine -match $summary.testApkSha256) { $testHashMatched = $true }
    }
    if (-not $testHashMatched) { throw 'Installed instrumentation APK SHA-256 differs from the current local artifact.' }
    $permissionDump = (Get-Content -LiteralPath (Join-Path $evidenceRoot 'package.txt') -Raw)
    foreach ($permission in @('READ_VALET_MODE', 'CONTROL_VALET_MODE')) {
        # The connected API 37 image has no pm/cmd-package check-permission command.
        # Native checkSelfPermission + actual writes are also asserted by instrumentation.
        $pattern = 'android\.car\.permission\.' + [regex]::Escape($permission) + ':\s*granted=true\b'
        $granted = $permissionDump -match $pattern
        $summary[$permission] = $granted
        if (-not $granted) { throw "Package metadata lacks a native Valet grant: $permission." }
    }

    # App external storage follows the active user on the headless-system-user AVD.
    $remoteVerificationStorage = "/storage/emulated/$userId/Android/data/$packageName/files/verification"
    $remoteVerificationMedia = "/data/media/$userId/Android/data/$packageName/files/verification"
    $deviceUid = ((Invoke-Adb -Arguments @('shell', 'id', '-u')) -join '').Trim()
    # The root shell on this image cannot traverse the secondary user's FUSE storage view.
    # /data/media references the same app evidence; never request root or change device state.
    $remoteVerification = if ($deviceUid -eq '0') { $remoteVerificationMedia } else { $remoteVerificationStorage }
    $summary.evidenceRemotePath = $remoteVerification
    Invoke-Adb -Arguments @('shell', 'rm', '-f', "$remoteVerification/property-matrix.json", "$remoteVerification/property-matrix-before.json", "$remoteVerification/external-door-update.png") | Out-Null
    $logSince = ((Invoke-Adb -Arguments @('shell', 'date', '+%m-%d\ %H:%M:%S.000')) -join '').Trim()
    Write-Host 'Running every app instrumentation class (live properties are temporarily modified)...'
    $summary.instrumentation = 'RUNNING'
    $instrumentOutput = Invoke-Adb -Arguments @('shell', 'am', 'instrument', '--user', $userId, '-w', '-r', $runner) -LogName 'instrumentation.txt'
    $instrumentText = $instrumentOutput -join "`n"
    if ($instrumentText -notmatch 'OK \((\d+) tests?\)' -or
        $instrumentText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[34]') {
        $summary.instrumentation = 'FAIL'
        throw 'Instrumentation failed, skipped tests, or did not produce a complete success result. Inspect instrumentation.txt.'
    }
    $summary.instrumentationTests = [int]([regex]::Match($instrumentText, 'OK \((\d+) tests?\)').Groups[1].Value)
    foreach ($testClass in @('VehicleCapabilityMatrixTest', 'VehicleWorkspaceInstrumentedTest', 'VehicleRuntimeInstrumentedTest',
        'VehicleMovementGestureTest', 'VehicleMovementLifecycleTest', 'SystemHvacInstrumentedTest', 'VehicleDomainRepositoryInstrumentedTest',
        'VehicleAuditFixesInstrumentedTest', 'VehicleGroupNavigationInstrumentedTest')) {
        if ($instrumentText -notmatch [regex]::Escape("INSTRUMENTATION_STATUS: class=com.android.mysystemapp.$testClass")) {
            throw "Expected instrumentation class did not execute: $testClass"
        }
    }
    $summary.instrumentation = 'PASS'
    Pull-Verification -LogName 'pull-verification.txt'
    $matrix = Get-Content -LiteralPath (Join-Path $evidenceRoot 'verification\property-matrix.json') -Raw | ConvertFrom-Json
    if (@($matrix.failures).Count -gt 0 -or @($matrix.rows | Where-Object { $_.status -eq 'FAIL' -or $_.restore -eq 'FAIL' }).Count -gt 0) {
        throw 'Property matrix contains a failure or restoration failure.'
    }
    $expectedRestoreCount = @($matrix.initial_snapshot).Count
    if ($expectedRestoreCount -eq 0 -or @($matrix.final_restore).Count -ne $expectedRestoreCount -or
        @($matrix.final_restore | Where-Object { $_.status -ne 'PASS' }).Count -gt 0) {
        throw 'Final independent restoration readback is missing, incomplete, or failed.'
    }
    $summary.finalRestoredAreas = $expectedRestoreCount
    $summary.matrix = @($matrix.rows | Group-Object status | ForEach-Object { [ordered]@{ status = $_.Name; count = $_.Count } })
    $summary.unsupported = @($matrix.rows | Where-Object status -eq 'UNSUPPORTED' | ForEach-Object { $_.property })

    # Diagnostic startup and frame snapshots; dedicated macrobenchmarks produce regression metrics.
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', '--user', $userId, $packageName) | Out-Null
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $packageName, 'reset') | Out-Null
    Invoke-Adb -Arguments @('shell', 'am', 'start', '--user', $userId, '-W', '-n', "$packageName/.MainActivity") -LogName 'startup.txt' | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb -Arguments @('shell', 'screencap', '-p', '/data/local/tmp/vehicle-control-verification.png') | Out-Null
    Invoke-Adb -Arguments @('pull', '/data/local/tmp/vehicle-control-verification.png', (Join-Path $evidenceRoot 'workspace.png')) | Out-Null
    Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', '/data/local/tmp/vehicle-control-verification.xml') -LogName 'ui-dump.txt' | Out-Null
    Invoke-Adb -Arguments @('pull', '/data/local/tmp/vehicle-control-verification.xml', (Join-Path $evidenceRoot 'workspace.xml')) | Out-Null
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $packageName, 'framestats') -LogName 'gfxinfo.txt' | Out-Null
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -LogName 'activity-after.txt' | Out-Null
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'car_service') -LogName 'car-service-after.txt' | Out-Null
    $log = Invoke-Adb -Arguments @('logcat', '-d', '-v', 'threadtime', '-T', $logSince) -LogName 'logcat.txt'
    if (($log -join "`n") -match 'ANR in com\.android\.mysystemapp|Process: com\.android\.mysystemapp, PID:') {
        throw 'Application crash/ANR appears in the verification log interval.'
    }
    $summary.installedApksAfter = @(Assert-InstalledApk $summary.apkSha256)
    $summary.status = 'PASS'
    Write-Host "Vehicle verification passed. Evidence: $evidenceRoot"
} catch {
    $summary.status = 'FAIL'
    if ($summary.instrumentation -eq 'RUNNING') { $summary.instrumentation = 'FAIL' }
    $summary.error = $_.ToString()
    # Preserve diagnostics on failure; do not silently restart/reinstall or manufacture a pass.
    if ($adb -and (Test-Path -LiteralPath $adb)) {
        try { Invoke-Adb -Arguments @('logcat', '-d', '-t', '3000') -LogName 'failure-logcat.txt' | Out-Null } catch { }
        if ($remoteVerification) {
            try { Pull-Verification -LogName 'failure-pull.txt' } catch { }
        }
    }
    throw
} finally {
    $summary.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Save-Text 'summary.json' @($summary | ConvertTo-Json -Depth 10)
    Pop-Location
}
