# Clones whisper.cpp into app/src/main/cpp/third_party/whisper.cpp
# Run once from the repo root after first clone of this project.
#
# Pinned to a known-good tag so the C++ API used by whisper-jni.cpp matches.

$ErrorActionPreference = "Stop"

$repo = "https://github.com/ggerganov/whisper.cpp.git"
$tag = "v1.7.1"
$dest = "app/src/main/cpp/third_party/whisper.cpp"

if (Test-Path $dest) {
    Write-Host "whisper.cpp already present at $dest"
    exit 0
}

Write-Host "Cloning whisper.cpp $tag into $dest..."
New-Item -ItemType Directory -Force -Path "app/src/main/cpp/third_party" | Out-Null
git clone --depth 1 --branch $tag $repo $dest

Write-Host "Done. You can now sync the project in Android Studio."
