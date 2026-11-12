<#
.SYNOPSIS
    Builds the Meshline test APKs side by side and optionally installs one.

.DESCRIPTION
    The three builds (Phase C, D1, D2) come from three different git refs.
    Instead of checking them out one after another (which would leave the
    working tree in a random state), the script uses `git worktree`: every
    build gets its own throw-away checkout in the temp folder, is built there,
    and the APK is copied into .\test-apks\ before the worktree is removed.

    The APKs are always the *debug* builds (signed with the debug key, so
    they install on any phone without extra setup).

.PARAMETER Build
    Which APK(s) to build: phase-c | d1 | d2 | all.

.PARAMETER Serial
    The adb serial of the device to install on (e.g. R58M1234ABC).
    Without it the script only builds.

.PARAMETER Install
    Which APK to install: phase-c | d1 | d2.

.PARAMETER RunTests
    Also run `connectedDebugAndroidTest` on the chosen device (this wipes the
    app data — see TESTING.md).

.PARAMETER Task
    The Gradle task to run in each worktree (default: assembleDebug).

.PARAMETER OutDir
    Where the APKs are written (default: <repo>\test-apks).

.EXAMPLE
    .\tools\test-day.ps1 -Build all
    Builds all three APKs into .\test-apks\.

.EXAMPLE
    .\tools\test-day.ps1 -Build d2 -Install d2 -Serial R58M1234ABC
    Builds the D2 debug APK and installs it on that phone.

.EXAMPLE
    .\tools\test-day.ps1 -Build d2 -Install d2 -Serial emulator-5554 -RunTests
    Builds, installs and runs the instrumented tests (wipes the app data!).
#>
[CmdletBinding()]
param(
    [ValidateSet('phase-c', 'd1', 'd2', 'all')]
    [string] $Build = 'all',
    [string] $Serial = '',
    [ValidateSet('phase-c', 'd1', 'd2')]
    [string] $Install = '',
    [switch] $RunTests,
    [string] $Task = 'assembleDebug',
    [string] $OutDir = '',
    [string] $JavaHome = '',
    [string] $AndroidHome = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'test-apks' }

# --- the three builds we care about -------------------------------------------
$targets = @(
    @{ Name = 'phase-c'; Ref = 'phase-c-unverified'; Apk = 'meshline-phase-c.apk' },
    @{ Name = 'd1'; Ref = 'd1-identity'; Apk = 'meshline-d1.apk' },
    @{ Name = 'd2'; Ref = 'd2-noise'; Apk = 'meshline-d2.apk' }
)

# --- environment -------------------------------------------------------------
if (-not $JavaHome) {
    $JavaHome = $env:JAVA_HOME
    if (-not $JavaHome) {
        $candidate = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '17' } | Select-Object -First 1
        if ($candidate) { $JavaHome = $candidate.FullName }
    }
}
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:ANDROID_HOME -and $AndroidHome) { $env:ANDROID_HOME = $AndroidHome }
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git is required' }
if (-not (Test-Path (Join-Path $repoRoot 'gradlew.bat'))) { throw "not a Meshline checkout: $repoRoot" }

function Invoke-Checked {
    param([string] $Exe, [string[]] $Arguments, [string] $What)
    Write-Host "==> $What" -ForegroundColor Cyan
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

function Get-Adb {
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { $adb = 'adb' }
    return $adb
}

# --- build one ref in a throw-away worktree ---------------------------------
function Build-Ref {
    param([hashtable] $Target)

    $worktree = Join-Path ([System.IO.Path]::GetTempPath()) ("meshline-wt-" + $Target.Name + "-" + [Guid]::NewGuid().ToString('N').Substring(0, 6))
    Write-Host ""
    Write-Host "--- $($Target.Name)  (ref: $($Target.Ref)) ---" -ForegroundColor Yellow

    Invoke-Checked 'git' @('-C', $repoRoot, 'worktree', 'add', '--detach', $worktree, $Target.Ref) "creating a worktree for $($Target.Ref)"
    try {
        # The Gradle wrapper resolves the project from the *current* directory,
        # so the build has to run inside the worktree.
        Push-Location $worktree
        try {
            Invoke-Checked (Join-Path $worktree 'gradlew.bat') @(
                ":app:$Task", '--no-daemon', '--console=plain'
            ) "building $($Target.Name) ($($Target.Ref))"
        }
        finally { Pop-Location }

        $apkDir = Join-Path $worktree 'app\build\outputs\apk\debug'
        $apk = Get-ChildItem $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
            Sort-Object Length -Descending | Select-Object -First 1
        if (-not $apk) { throw "no APK was produced in $apkDir" }

        New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
        $apkPath = Join-Path $OutDir $Target.Apk
        Copy-Item $apk.FullName $apkPath -Force
        Write-Host "    -> $apkPath ($([Math]::Round($apk.Length / 1MB, 2)) MiB)" -ForegroundColor Green
    }
    finally {
        # The worktree is disposable: never leave the repo in a strange state.
        # (git writes to stderr, so the errors are swallowed on purpose.)
        & git -C $repoRoot worktree remove --force $worktree *>$null
        & git -C $repoRoot worktree prune *>$null
    }
}

# --- build -------------------------------------------------------------------
$wanted = if ($Build -eq 'all') { $targets } else { @($targets | Where-Object { $_.Name -eq $Build }) }
foreach ($ref in $wanted) { Build-Ref $ref }

# --- install -----------------------------------------------------------------
if ($Install) {
    $apk = Join-Path $OutDir (($targets | Where-Object { $_.Name -eq $Install }).Apk)
    if (-not (Test-Path $apk)) { throw "APK not found: $apk (build it first)" }
    $adb = Get-Adb
    $serialArgs = @()
    if ($Serial) { $serialArgs = @('-s', $Serial) }

    Write-Host ""
    Write-Host "--- installing $Install on $Serial ---" -ForegroundColor Yellow
    Invoke-Checked $adb ($serialArgs + @('install', '-r', $apk)) "installing $apk"

    if ($RunTests) {
        Write-Host ""
        Write-Host "--- instrumented tests on $Serial (this WIPES the app data) ---" -ForegroundColor Yellow
        Push-Location $repoRoot
        try {
            $gradleArgs = @(':app:connectedDebugAndroidTest', '--no-daemon', '--console=plain')
            if ($Serial) { $gradleArgs = @(':app:connectedDebugAndroidTest') + $serialArgs + @('--no-daemon', '--console=plain') }
            Invoke-Checked (Join-Path $repoRoot 'gradlew.bat') $gradleArgs 'connectedDebugAndroidTest'
        }
        finally { Pop-Location }
    }
}

Write-Host ""
Write-Host "Done. APKs are in $OutDir" -ForegroundColor Green
Get-ChildItem $OutDir -Filter '*.apk' -ErrorAction SilentlyContinue |
    Select-Object Name, @{ Name = 'MiB'; Expression = { [Math]::Round($_.Length / 1MB, 2) } } |
    Format-Table -AutoSize
