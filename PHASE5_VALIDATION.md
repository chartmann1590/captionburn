# Phase 5 Validation

This document records completion and validation evidence for **Phase 5 — Render & burn** from `plan.txt`.

## Implementation status

Phase 5 components are implemented:

- `app/src/main/java/com/charlesh/captionburn/data/render/AssSubtitleBuilder.kt`
  - Builds `.ass` subtitles from transcript/style data.
  - Emits per-word karaoke tags (`\k<centiseconds>`).
  - Supports Original / Translated / Both display modes.
- `app/src/main/java/com/charlesh/captionburn/data/render/FFmpegBurner.kt`
  - Runs FFmpeg burn-in.
  - Parses `time=HH:MM:SS.CS` from FFmpeg log callback for progress.
  - Writes output to app cache and publishes to MediaStore on success.
- `app/src/main/java/com/charlesh/captionburn/data/media/MediaStorePublisher.kt`
  - Inserts under `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.
  - Uses `RELATIVE_PATH = Movies/CaptionBurn`.
  - Uses `IS_PENDING` finalize flow.
- `app/src/main/java/com/charlesh/captionburn/ui/export/ExportViewModel.kt`
  - Drives export flow and persists success/failure on the project.

## Automated validation evidence

Command run:

- `.\gradlew.bat testDebugUnitTest`

Result:

- `BUILD SUCCESSFUL`

Relevant report artifacts:

- `app/build/test-results/testDebugUnitTest/TEST-com.charlesh.captionburn.data.render.AssSubtitleBuilderTest.xml`
- `app/build/test-results/testDebugUnitTest/TEST-com.charlesh.captionburn.data.render.FFmpegBurnerTest.xml`
- `app/build/reports/tests/testDebugUnitTest/classes/com.charlesh.captionburn.data.render.AssSubtitleBuilderTest.html`
- `app/build/reports/tests/testDebugUnitTest/classes/com.charlesh.captionburn.data.render.FFmpegBurnerTest.html`

## Manual validation checklist (VLC + Android playback)

The project plan requires device-driven manual QA for burn-in output playback.

### 1) Verify `.ass` karaoke rendering in VLC

1. Produce a 3-segment transcript export in app flow.
2. Capture generated `.ass` file (from export staging).
3. Open source video + `.ass` in VLC.
4. Confirm:
   - karaoke highlighting advances word-by-word
   - dual-line placement is correct when display mode is Both
   - subtitle timing aligns with speech

### 2) Verify final hard-burned output on Android player

1. Run export from `ExportScreen`.
2. Open output from `Movies/CaptionBurn`.
3. Play in default Android video player.
4. Confirm captions are hard-burned and visible with playback.

### Manual QA evidence log

Use this section to log final device verification:

- Device / API:
- Input video:
- Output URI/path:
- VLC karaoke check: PASS / FAIL
- Android player burn-in check: PASS / FAIL
- Notes:

Current run status in this environment:

- Device-connected manual playback: NOT RUN (requires interactive VLC + Android player check on a physical/emulated device).
