# MEMO-Appflow

MEMO-Appflow is an Android operating-systems-oriented project for prediction-guided resource management. It predicts the next likely apps a user will open, converts those predictions into resource decisions, and pushes those decisions through a system-facing execution layer. The widget and dashboard are downstream observability and control surfaces, not the core contribution.

The central chain in this repository is:

`collection/replay -> normalized AppEvent history -> predictor -> policy -> MemoSystemServiceFacade -> SystemBridge/controllers -> observable system status`

## What Already Exists

- Kotlin Android app with Room, WorkManager, widget, dashboard, settings, and emulator-ready entry points
- Online-first home screen with live capture guidance, algorithm switching, auto refresh, and system-impact summaries
- Replay Lab with step-by-step benchmark flow, algorithm switching, built-in dataset preview, and export controls
- Public dataset registry, downloader, parser, normalizer, split manager, and replay evaluation pipeline
- Online usage collection through `UsageStatsCollector`
- Predictor layer with a working Markov baseline and trainer
- Policy layer with a threshold policy that converts predictions into `ResourceDecision`
- System-facing execution through `MemoSystemServiceFacade`, `AppLevelSystemBridge`, `NativeSystemBridge`, `PrewarmController`, and `RetentionController`
- JNI/C++ support used at runtime for score normalization and threshold merging
- Experiment recording, analysis, CSV export, and JSON export
- Android Emulator demo target with a created AVD: `MEMO_OS_API34`

## Why This Is OS-Oriented

This repository is not framed as an app recommendation demo. Prediction quality matters here because it drives system-facing decisions:

- app prewarming intent
- retention / keep-alive intent
- launch-related resource hints
- future integration with Binder-backed services, native daemons, and AOSP hooks

Today the actual execution is app-level because normal app sandboxing does not allow privileged framework hooks. Even so, the code is already organized around the future system boundary:

- `system/service/MemoSystemServiceFacade.kt`
- `system/bridge/SystemBridge.kt`
- `system/bridge/AppLevelSystemBridge.kt`
- `system/bridge/NativeSystemBridge.kt`
- `system/prewarm/PrewarmController.kt`
- `system/memory/RetentionController.kt`

## Official Modes

Only two formal run modes are treated as first-class:

1. `PUBLIC_DATASET_REPLAY` / `LOCAL_REPLAY`
   Formal benchmark mode using dataset selection, cache/download, normalization, split, replay, predict, policy, evaluate, and export.
2. `ONLINE_DEVICE`
   Formal live mode on this Android device using usage collection, prediction, policy, system-facing execution, widget/dashboard state updates, and experiment recording.

## Repository Structure

- `app/`: Android app, JNI bridge, UI surfaces, workers, orchestration, Room, and core runtime logic
- `docs/`: architecture, datasets, metrics, experiment protocol, and system integration plan
- `configs/`: dataset/config examples
- `scripts/`: helper scripts
- `dataset_cache/`: desktop-side local dataset samples used during development
- `benchmark/`: benchmark notes and placeholders

Within `app/src/main/java/com/memoos/`:

- `core/`
- `data/`
- `predictor/`
- `policy/`
- `system/`
- `evaluation/`
- `orchestration/`
- `worker/`
- `ui/`

## Build

The workspace now includes a Gradle wrapper.

```powershell
.\gradlew.bat build
```

Verified in this repository:

- `.\gradlew.bat build` succeeds
- debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`

## Android Emulator Runbook

Android Emulator is the primary live demo target.

### 1. Start the emulator

An AVD named `MEMO_OS_API34` has already been created on this machine.

```powershell
$env:ANDROID_SDK_ROOT="$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_SDK_ROOT\emulator\emulator.exe" `
  -avd MEMO_OS_API34 `
  -no-snapshot-load `
  -no-snapshot-save `
  -no-boot-anim `
  -noaudio `
  -gpu swiftshader_indirect
```

If you want to confirm acceleration support:

```powershell
& "$env:ANDROID_SDK_ROOT\emulator\emulator-check.exe" accel
```

### 2. Install and launch the app

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell cmd appops set com.memoos GET_USAGE_STATS allow
& $adb shell am start -n com.memoos/.ui.main.MainActivity
```

Verified in this workspace:

- APK installed successfully on the emulator
- `MainActivity` launched successfully

### 3. Demo the two official modes

#### Public Dataset Replay Mode

Use this when you want the formal benchmark path.

From the app:

1. Open `Dashboard`
2. Cycle mode to `PUBLIC_DATASET_REPLAY` or `LOCAL_REPLAY`
3. Cycle dataset to `sample_local`, `sample_public_json`, or another registered manifest
4. Tap `Prepare Dataset`
5. Tap `Run Replay Evaluation`

What happens internally:

`dataset selection -> cache/download -> parse -> normalize -> split -> replay -> predict -> policy -> evaluate -> export`

Important emulator detail:

- bundled sample datasets are seeded into the app's internal storage at startup under `files/dataset_cache/`
- this keeps replay mode usable on emulator and device, not only on the desktop workspace

#### Online Emulator / Real-Device Mode

Use this when you want the live systems demo.

From the app:

1. Open MEMO-Appflow
2. Enable Usage Access if needed
3. Use a few apps on this Android device
4. Return to MEMO-Appflow
5. Wait for the automatic refresh or tap `Refresh Live Prediction`

What happens internally:

`online collection -> normalized AppEvent history -> predictor -> policy -> MemoSystemServiceFacade -> SystemBridge/controllers -> system status update -> widget/dashboard update -> experiment recording`

What the user now sees on the home screen:

- a live status sentence
- the current predictor
- top predicted apps
- a recent activity timeline with timestamps
- the latest system plan
- memory / decision telemetry from the latest cycle

## Widget Demo

The widget is downstream from repository/service state. It does not own prediction logic or system control logic.

To pin it on the emulator:

1. Go to the home screen
2. Long press the home screen
3. Open `Widgets`
4. Find `MemoOS Top 3`
5. Add the widget

The widget shows:

- top-3 predicted apps
- resolved app labels when available
- latest system execution summary from `SystemStatusRepository`

Tapping a widget prediction launches that package through `AppLaunchController`.

## Data And Replay

Public dataset support is first-class. The current pipeline includes:

- `DatasetRegistry`
- `PublicDatasetDownloader`
- parser/normalizer registries
- `PublicDatasetManager`
- `DatasetSplitManager`
- `ReplayEventStream`
- `ReplayPipeline`

Built-in dataset names:

- `sample_local`
- `sample_public_json`
- `lsapp_placeholder`

Replay and online mode share the same normalized schema and the same predictor/policy/evaluation interfaces.

## Native / JNI Usage

Native code is meaningful and exercised on runtime paths:

- `MarkovPredictor` uses `NativeScoreBridge.normalize(...)`
- `ThresholdPolicyEngine` uses `NativeScoreBridge.mergeThresholds(...)`
- `NativeSystemBridge` provides a future-facing native strategy seam for system-close execution logic

This keeps C++ focused on system/native flavor and future extensibility rather than moving all application logic out of Kotlin.

## Where To Verify Output

### In the UI

- `MainActivity`: live optimization console, algorithm switch, widget pinning, live activity timeline, and system-impact summaries
- `DashboardActivity`: Replay Lab with mode switching, algorithm switching, dataset preparation, dataset preview, replay trigger, metrics, and exports
- widget: top predictions plus system execution summary

### In app internal storage

Bundled sample datasets:

- `files/dataset_cache/sample_usage.csv`
- `files/dataset_cache/sample_usage.json`

Replay exports:

- `files/dataset_cache/exports/*.csv`
- `files/dataset_cache/exports/*.json`

Dashboard exports:

- `files/exports/experiments.csv`
- `files/exports/experiments.json`

Example inspection:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell run-as com.memoos ls -R files
```

### In logs

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -s MemoOS
```

## Current App-Level vs Future System Integration

Current runnable prototype:

- user-granted Usage Access
- app-level launch intents
- structured prewarm/retention control points
- native/JNI utilities
- emulator/device demo path

Future replacement path:

- Binder-backed system service
- native daemon / service process
- launch hints deeper in framework paths
- privileged memory and process management hooks
- AOSP integration with the predictor/policy interfaces kept stable

## Key Files To Start From

- `app/src/main/java/com/memoos/ui/main/MemoGraph.kt`
- `app/src/main/java/com/memoos/orchestration/ReplayPipeline.kt`
- `app/src/main/java/com/memoos/orchestration/PolicyPipeline.kt`
- `app/src/main/java/com/memoos/system/service/MemoSystemServiceFacade.kt`
- `app/src/main/java/com/memoos/ui/widget/RecommendationWidgetUpdater.kt`
- `app/src/main/cpp/bridge/memo_native_bridge.cpp`

## Status Of This Pass

Verified during this pass:

- JDK / SDK / NDK / CMake / Gradle wrapper installed and working
- `.\gradlew.bat build` passes
- Android Emulator package and API 34 Google APIs system image installed
- AVD `MEMO_OS_API34` created
- app installed to emulator
- app launched on emulator
- bundled replay datasets seeded into app internal storage
