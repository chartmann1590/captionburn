#!/usr/bin/env bash
# Clones whisper.cpp into app/src/main/cpp/third_party/whisper.cpp
# Run once from the repo root after first clone of this project.
set -euo pipefail

REPO="https://github.com/ggerganov/whisper.cpp.git"
TAG="v1.7.1"
DEST="app/src/main/cpp/third_party/whisper.cpp"

if [ -d "$DEST" ]; then
  echo "whisper.cpp already present at $DEST"
  exit 0
fi

echo "Cloning whisper.cpp $TAG into $DEST..."
mkdir -p "app/src/main/cpp/third_party"
git clone --depth 1 --branch "$TAG" "$REPO" "$DEST"
echo "Done. You can now sync the project in Android Studio."
