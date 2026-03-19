# Experiment Protocol

MEMO-OS supports two formal experiment tracks.

## Track A: Public Dataset Replay

This is the formal offline benchmark mode.

End-to-end path:

`dataset selection -> cache/download -> parse -> normalize -> split -> replay -> predict -> policy -> evaluate -> export`

Procedure:

1. choose `PUBLIC_DATASET_REPLAY` or `LOCAL_REPLAY`
2. select a dataset from `DatasetRegistry`
3. prepare the dataset so cache/local/download resolution happens
4. replay normalized events in temporal order
5. generate predictions from recent history windows
6. convert predictions into `ResourceDecision`
7. execute the current system-facing path
8. record evaluation outcomes and export results

Outputs:

- `Hit@1`
- `Hit@3`
- `MRR`
- keep-alive / prewarm / hint counts
- CSV export
- JSON export

## Track B: Online Emulator / Real-Device Mode

This is the formal live demo and live-evaluation mode.

End-to-end path:

`online collection -> normalize -> predict -> policy -> MemoSystemServiceFacade -> SystemBridge/controllers -> widget/dashboard status -> experiment recording`

Procedure:

1. use Android Emulator or a real device
2. grant Usage Access
3. open a few apps so usage events exist
4. run the online cycle from `MainActivity` or allow workers to run
5. inspect dashboard, widget, logs, and exports

The official emulator presentation path should use this mode, not a separate fake demo subsystem.

## Shared Task Definition

Both tracks use the same internal schema:

- `AppEvent`
- `Prediction`
- `PredictionBatch`
- `ResourceDecision`
- `ExperimentRecord`

This keeps benchmark results and live-device results aligned.

## Reproducibility

Replay reproducibility comes from:

- manifest-based dataset registration
- explicit parser/normalizer selection
- deterministic split logic
- temporal replay windows
- shared predictor and policy interfaces
- stable export columns

## Runtime Verification Checklist

### Replay mode

- dataset is present in cache or downloaded
- normalized events were produced
- replay windows were evaluated
- exports appear under `files/dataset_cache/exports`

### Online mode

- Usage Access is granted
- events are written to Room
- a prediction batch is produced
- a resource decision is applied through `MemoSystemServiceFacade`
- system status is visible in dashboard/widget state

## Emulator Notes

Android Emulator is the primary demo target. On Windows, the most stable launch command in this repository has been:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -avd MEMO_OS_API34 `
  -no-snapshot-load `
  -no-snapshot-save `
  -no-boot-anim `
  -noaudio `
  -gpu swiftshader_indirect
```

After boot:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell cmd appops set com.memoos GET_USAGE_STATS allow
& $adb shell am start -n com.memoos/.ui.main.MainActivity
```
