param(
    [string]$Avd,
    [string]$Serial,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$PackageName = "pl.whisperlive"
$AndroidProject = Join-Path $Root "whisper.cpp\examples\whisper.android.java"
$Apk = Join-Path $AndroidProject "app\build\outputs\apk\debug\app-debug.apk"

function Find-SdkTool([string]$SdkRoot, [string]$ToolName) {
    $Candidates = @(
        (Join-Path $SdkRoot "platform-tools\$ToolName.exe"),
        (Join-Path $SdkRoot "emulator\$ToolName.exe")
    )
    foreach ($Candidate in $Candidates) {
        if (Test-Path $Candidate) { return $Candidate }
    }
    return $null
}

$Sdk = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($Sdk)) { $Sdk = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($Sdk)) {
    $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path $Sdk)) { throw "Android SDK not found: $Sdk" }

$Adb = Find-SdkTool $Sdk "adb"
$Emulator = Find-SdkTool $Sdk "emulator"
if (-not $Adb) { throw "adb.exe not found in Android SDK." }

function Get-ConnectedSerial {
    $Lines = & $Adb devices
    foreach ($Line in $Lines) {
        if ($Line -match "^(emulator-\d+)\s+device$") { return $Matches[1] }
    }
    return $null
}

$Device = if ($Serial) { $Serial } else { Get-ConnectedSerial }
if (-not $Device) {
    if (-not $Emulator) { throw "No running emulator and emulator.exe was not found in the SDK." }
    if (-not $Avd) {
        $Avd = (& $Emulator -list-avds | Where-Object { $_.Trim() } | Select-Object -First 1).Trim()
    }
    if ([string]::IsNullOrWhiteSpace($Avd)) {
        throw "No AVD found. Create one in Android Studio or pass -Avd AvdName."
    }
    Write-Host "Starting emulator AVD: $Avd" -ForegroundColor Cyan
    Start-Process -FilePath $Emulator -ArgumentList "-avd", $Avd
    & $Adb wait-for-device
    $Device = Get-ConnectedSerial
    if (-not $Device) { throw "The emulator did not appear in adb." }
}

Write-Host "Using device: $Device" -ForegroundColor DarkGray
if (-not $SkipBuild -and (-not (Test-Path $Apk))) {
    Write-Host "Debug APK not found. Running build_windows.ps1..." -ForegroundColor Cyan
    & (Join-Path $Root "build_windows.ps1")
    if ($LASTEXITCODE -ne 0) { throw "APK build failed with exit code $LASTEXITCODE." }
}
if (-not (Test-Path $Apk)) {
    throw "APK not found: $Apk. Run the script without -SkipBuild."
}

Write-Host "Installing APK..." -ForegroundColor Cyan
& $Adb -s $Device install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed with exit code $LASTEXITCODE." }

Write-Host "Launching Whisper Live..." -ForegroundColor Green
& $Adb -s $Device shell am force-stop $PackageName
& $Adb -s $Device shell monkey -p $PackageName 1
if ($LASTEXITCODE -ne 0) { throw "Application launch failed with exit code $LASTEXITCODE." }

Write-Host "Application is running on the emulator." -ForegroundColor Green
