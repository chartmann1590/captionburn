# CaptionBurn

A native Android app that auto-generates and burns captions into your videos.
Speech recognition (Whisper) and translation (ML Kit) run **entirely on-device**
— no cloud calls.

## Status

**Phase 5 of 7 complete (implementation).** The app includes whisper.cpp JNI
transcription, translation, ASS subtitle generation, FFmpeg burn-in, and
MediaStore publishing for exported captioned videos.

Phase 5 validation evidence and manual QA checklist are documented in
`PHASE5_VALIDATION.md`.

See `C:\Users\Charles\.claude\plans\atomic-honking-wand.md` for the full plan.

## Requirements

- Android Studio Ladybug | 2024.2.1 or newer
- JDK 17 (Android Studio bundles a compatible one)
- Android SDK 35, NDK 26.x, CMake 3.22.1 (Android Studio offers to install all
  of these on first sync)
- A device or emulator on Android 13 (API 33) or newer

## First-time setup

1. Open the `vid-caption` folder in Android Studio.
2. Accept SDK / NDK / CMake installation prompts.
3. Let Gradle sync. Android Studio will create `gradle/wrapper/gradle-wrapper.jar`
   and `gradlew` / `gradlew.bat` automatically if they're missing.
4. Run the `app` configuration on a device or Pixel-class emulator.

## Caption style controls

Open a project in the Editor and tap **Style controls**.

- **Size** changes caption text size in the preview and exported video.
- **Outline** adds a dark edge around caption text for contrast on bright video.
- **Placement** chooses the caption anchor point: top, middle, bottom, left,
  center, or right.
- **Word highlight** controls the spoken-word effect. `Off` renders one steady
  caption per segment. `Color word`, `Grow word`, and `Underline` render
  per-word overlay images so the exported video matches the preview.
- **Text color** sets the normal caption color.
- **Highlight color** sets the color used by active-word highlight modes.

## Native Whisper setup (Phase 2)

1. Initialize whisper.cpp sources once after clone:
   - Windows (PowerShell): `./scripts/init-whisper.ps1`
   - macOS/Linux: `./scripts/init-whisper.sh`
2. Re-sync Gradle in Android Studio (or run `./gradlew :app:assembleDebug`).
3. Confirm native build succeeds for your ABI (default filters: `arm64-v8a`, `armeabi-v7a`, `x86_64`).

If `app/src/main/cpp/third_party/whisper.cpp` is missing, CMake intentionally
fails with instructions to run the init script.

## What works through Phase 2

- Onboarding → Home → Editor → Export → Settings navigation
- Material 3 dynamic-color theming, custom dark/light schemes
- System Photo Picker for video selection (no broad storage permission needed)
- Foreground service skeleton, notification channel, manifest plumbing
- whisper.cpp JNI bridge and coroutine-friendly `WhisperEngine`
- Resumable Whisper model download with SHA-256 verification
- FFmpeg extraction to 16 kHz mono PCM WAV
- End-to-end transcription orchestration (`TranscriptionService`)

## Phase 2 verification checklist

Run on a device/emulator with API 33+:

1. Download a Whisper model from onboarding.
2. Add a spoken English fixture:
   - `app/src/androidTest/assets/whisper_sample_30s_en.wav`
   - mono, 16 kHz, 16-bit PCM, 30 seconds
3. Run instrumentation test:
   - `./gradlew :app:connectedDebugAndroidTest --tests "com.charlesh.captionburn.data.transcription.WhisperEngineInstrumentedTest"`
4. Confirm:
   - detected language is `en`
   - word count is in expected range
   - each word duration is positive and < 2 seconds

## What's next

Phase 6: WorkManager pipeline backed by the foreground service.
Phase 7: animations, settings, ProGuard, polish.

## Module layout

```
app/src/main/java/com/charlesh/captionburn/
├── ui/{theme,nav,onboarding,home,editor,export,settings}
├── domain/model/         # Project, Transcript, Word, Segment, CaptionStyle
├── data/transcription/   # WhisperJni (real impl in Phase 2)
├── service/              # ProcessingService (foreground)
└── di/                   # Hilt modules

app/src/main/cpp/         # JNI bridge to whisper.cpp (added Phase 2)
```
