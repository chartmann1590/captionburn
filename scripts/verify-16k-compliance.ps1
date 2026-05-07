# Requires: Android SDK with build-tools 35+, NDK r28+, JDK, optional bundletool-all.jar.
# Usage:
#   .\scripts\verify-16k-compliance.ps1 -Arm64Apk "app\build\outputs\apk\release\app-arm64-v8a-release-unsigned.apk"
#   .\scripts\verify-16k-compliance.ps1 -Arm64Apk "...\apk" -Bundle "...\app-release.aab" -BundletoolJar "$env:TEMP\bundletool-all.jar"
param(
    [Parameter(Mandatory = $true)]
    [string] $Arm64Apk,
    [string] $SdkRoot = $env:ANDROID_HOME,
    [string] $Bundle,
    [string] $BundletoolJar
)

$ErrorActionPreference = "Stop"

if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    $SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not (Test-Path $SdkRoot)) {
    Write-Error "Set ANDROID_HOME or pass -SdkRoot."
}

$ndkDirs = Get-ChildItem "$SdkRoot\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
if (-not $ndkDirs) { Write-Error "No NDK under $SdkRoot\ndk" }
$ndkVer = $ndkDirs[0].Name
$llvmObjdump = "$SdkRoot\ndk\$ndkVer\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe"
if (-not (Test-Path $llvmObjdump)) { Write-Error "Missing $llvmObjdump" }

$zipalignItem = Get-ChildItem "$SdkRoot\build-tools\*\zipalign.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $zipalignItem) { Write-Error "Missing zipalign.exe under $SdkRoot\build-tools" }
$zipalign = $zipalignItem.FullName
$zipalignVer = $zipalignItem.Directory.Name

if (-not (Test-Path $Arm64Apk)) { Write-Error "APK not found: $Arm64Apk" }

Write-Host "Using NDK $ndkVer, zipalign from build-tools $zipalignVer"

$tmp = Join-Path $env:TEMP ("apk16k_" + ([guid]::NewGuid().ToString("n")))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Copy-Item $Arm64Apk (Join-Path $tmp "app.zip") -Force
    Expand-Archive -Path (Join-Path $tmp "app.zip") -DestinationPath (Join-Path $tmp "out") -Force
    $libDir = Join-Path $tmp "out\lib\arm64-v8a"
    if (-not (Test-Path $libDir)) { Write-Error "No lib/arm64-v8a in APK (wrong split APK?)" }

    $fail = $false
    Get-ChildItem "$libDir\*.so" | ForEach-Object {
        $soPath = $_.FullName
        $soName = $_.Name
        $lines = @(& $llvmObjdump -p $soPath 2>&1 | ForEach-Object { "$_" })
        foreach ($line in $lines) {
            if ($line -notmatch "LOAD") { continue }
            if ($line -match "align 2\*\*(\d+)") {
                $exp = [int]$Matches[1]
                $bytes = [math]::Pow(2, $exp)
                if ($bytes -lt 16384) {
                    Write-Host "FAIL ${soName}: $line" -ForegroundColor Red
                    $fail = $true
                }
            }
        }
    }
    if (-not $fail) {
        $count = (Get-ChildItem "$libDir\*.so").Count
        Write-Host "OK: $count arm64-v8a libraries - all LOAD align >= 16 KB (min exponent 14)" -ForegroundColor Green
    }

    & $zipalign -c -P 16 -v 4 $Arm64Apk
    if ($LASTEXITCODE -ne 0) { Write-Error "zipalign verification failed" }

    if ($Bundle -and $BundletoolJar -and (Test-Path $Bundle) -and (Test-Path $BundletoolJar)) {
        $dump = @(& java -jar $BundletoolJar dump config --bundle=$Bundle 2>&1 | ForEach-Object { "$_" })
        $dumpText = $dump -join "`n"
        $dump | Select-String -Pattern "alignment"
        if ($dumpText -notmatch "PAGE_ALIGNMENT_16K") {
            Write-Error "Bundle config does not show PAGE_ALIGNMENT_16K"
        }
        Write-Host "OK: bundle alignment PAGE_ALIGNMENT_16K" -ForegroundColor Green
    }
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Optional: on a 16 KB device or emulator (adb shell getconf PAGE_SIZE = 16384), run a full app smoke test."
