# 2026-05-07 Device-Side Pipeline Worklog

## What Changed Today

Today's work moved the project from host-driven scripts toward an Android-device-side product pipeline. The host is now only used to install APKs, push model/tool files, and pull logs/results. Product logic runs inside the emulator/rooted Android device.

Worklog owner for this device-side pipeline track: Jingyi Guo.

Implemented or verified today:

- removed fast prediction as a product path; MAPLE is the only prediction source;
- compacted MAPLE prompts so device-side inference can run with the small local GGUF model;
- fixed Android `su` command format to `su 0 sh -c ...`;
- made the app use the device-local MAPLE binary and model under `/data/local/tmp/memo`;
- verified a real Android eBPF camera/photo experiment end to end;
- implemented real Android-side eBPF ablation;
- added prediction and scheduling metrics to the ablation report;
- pulled the latest real ablation result into `docs/real_device_experiments/real_ebpf_ablation/`.

## Runtime Flow

```text
Android app button or service intent
-> EBPFCollectorService
-> EBPFCapabilityProbe
-> DeviceCollectorDeployer + BpftraceProgramBuilder
-> real Android app usage while bpftrace is attached
-> EBPFTraceParser
-> SystemStateCollector
-> MapleScenarioBuilder
-> MapleInferenceOrchestrator / MapleShellBackend / MAPLE C++ engine
-> AppIdMapping
-> ActionExecutor
-> MemoStore + MemoWidgetProvider
```

## Code Map

| Runtime step | Main code | Purpose |
| --- | --- | --- |
| Start/stop pipeline | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` | Foreground service, real usage experiments, MAPLE async execution, ablation entry |
| Generate eBPF program | `app/src/main/java/com/memoos/ebpf/BpftraceProgramBuilder.kt` | Builds supported tracepoint program on device |
| Deploy eBPF tools/scripts | `app/src/main/java/com/memoos/ebpf/DeviceCollectorDeployer.kt` | Places generated bpftrace script under device paths |
| Parse raw trace | `app/src/main/java/com/memoos/ebpf/EBPFTraceParser.kt` | Converts `MEMO_*` raw trace rows into structured events |
| Collect system state | `app/src/main/java/com/memoos/state/SystemStateCollector.kt` | Reads memory, battery, UDP, foreground app, service, media/display state |
| Build MAPLE input | `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` | Builds `memo.maple_scenarios.v1` JSON from eBPF and system state |
| Call MAPLE | `app/src/main/java/com/memoos/maple/MapleInferenceOrchestrator.kt` and `MapleShellBackend.kt` | Runs device-local MAPLE, parses Stage 1/Stage 2 output |
| MAPLE prompt | `llm/maple/maple_engine/src/prompt_builder.cpp` | Compact evidence prompt for device inference |
| MAPLE parser | `llm/maple/maple_engine/src/result_parser.cpp` | Parses small-model category and app-id outputs |
| MAPLE backend | `llm/maple/maple_engine/src/llama_backend.cpp` | Reuses llama context memory and uses compact generation settings |
| Real app mapping | `app/src/main/java/com/memoos/action/AppIdMapping.kt` | Scans installed launcher apps and maps predictions to real apps |
| System actions | `app/src/main/java/com/memoos/action/ActionExecutor.kt` | Turns MAPLE output into widget, memory, network, camera/media, display and service actions |
| Widget | `app/src/main/java/com/memoos/widget/MemoWidgetProvider.kt` | Shows Top-3 real apps to users |
| Persist UI state | `app/src/main/java/com/memoos/store/MemoStore.kt` | Saves last evidence, MAPLE result, actions, latency |
| Real ablation | `app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt` | Reruns MAPLE after removing evidence families from a real device-side scenario |

## Verified Device Layout

```text
/data/local/tmp/memo/maple_demo
/data/local/tmp/memo/libmaple_engine.so
/data/local/tmp/memo/models/Qwen3.5-0.8B-Q4_K_M.gguf
/sdcard/MEMO/logs/real_user_*.trace
/sdcard/MEMO/ablations/latest_real_ablation.json
```

## Commands Used

Build APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

Install APK:

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Run real camera/photo eBPF experiment:

```powershell
& $adb shell am start-foreground-service -n com.memoos/.ebpf.EBPFCollectorService -a com.memoos.action.REAL_EXPERIMENT_CAMERA
```

Run real eBPF ablation on the latest scenario:

```powershell
& $adb shell am start-foreground-service -n com.memoos/.ebpf.EBPFCollectorService -a com.memoos.action.REAL_EBPF_ABLATION_LATEST
```

## Result Files

| File | Meaning |
| --- | --- |
| `docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json` | Main real eBPF ablation report pulled from Android |
| `docs/real_device_experiments/real_ebpf_ablation/latest_maple_scenario.json` | Real camera/photo scenario generated on Android |
| `docs/real_device_experiments/real_ebpf_ablation/latest_pipeline_latency.json` | End-to-end pipeline latency for the real camera/photo run |
| `docs/real_device_experiments/real_ebpf_ablation/latest_real_user_experiment.txt` | Human-readable experiment metadata |
| `docs/real_device_experiments/real_ebpf_ablation/memo_pipeline.xml` | App-persisted recommendations, MAPLE result, evidence and actions |
| `docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace` | Raw real eBPF trace from the Android window |

## Verification

`.\gradlew.bat :app:assembleDebug` succeeded after the Android-side pipeline and metric changes.

The latest ablation report was produced by the emulator and pulled from:

```text
/sdcard/MEMO/ablations/latest_real_ablation.json
```
