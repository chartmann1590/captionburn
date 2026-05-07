# Runs a minimal install + launch smoke test only when adb reports PAGE_SIZE = 16384.
# Requires: arm64 debug APK at default path from assembleDebug.
param(
    [string] $DebugArm64Apk = (Join-Path (Split-Path $PSScriptRoot -Parent) "app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"),
    [string] $Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

if (-not (Test-Path $Adb)) {
    $Adb = "adb.exe"
}

function Get-PageDevices {
    $out = & $Adb devices
    $serials = @()
    foreach ($line in $out) {
        if ($line -match "^(\S+)\s+device\s*$") {
            $serials += $Matches[1]
        }
    }
    $sixteen = @()
    foreach ($s in $serials) {
        $ps = (& $Adb -s $s shell getconf PAGE_SIZE 2>$null).Trim()
        if ($ps -eq "16384") {
            $sixteen += $s
        }
    }
    return $sixteen
}

if (-not (Test-Path $DebugArm64Apk)) {
    Write-Error "Build debug APK first (assembleDebug): missing $DebugArm64Apk"
}

$targets = Get-PageDevices
if ($targets.Count -eq 0) {
    Write-Host "No adb device with PAGE_SIZE=16384. Connect a 16 KB emulator or device, or enable 16 KB mode in developer options."
    Write-Host "Skipping smoke test."
    exit 0
}

$s = $targets[0]
Write-Host "Using device $s (16 KB pages)"
& $Adb -s $s install -r $DebugArm64Apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$pkg = "com.charlesh.captionburn.debug"
$cls = "com.charlesh.captionburn.MainActivity"
& $Adb -s $s shell am start -n "${pkg}/${cls}"
exit $LASTEXITCODE
