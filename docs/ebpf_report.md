# MEMO-Appflow Device-Side eBPF and MAPLE Report

Date: 2026-05-06

## Current Product Path

MEMO-Appflow now treats the Android emulator as the deployment target, not as a thin UI attached to host scripts.

```text
Android app
-> root/device capability probe
-> eBPF or tracefs collection inside Android
-> system-state snapshot inside Android
-> MAPLE scenario built inside Android
-> MAPLE native bridge or device-local MAPLE binary
-> adaptive Top-3 real app recommendation
-> widget update and scheduling actions
```

The host is not part of the product runtime. It can install the APK, push model/tool files, and read logs.

## Android Components

| Component | Role |
| --- | --- |
| `EBPFCollectorService` | Foreground service that runs the device pipeline and real user experiment windows |
| `EBPFCapabilityProbe` | Detects root, tracefs, bpftrace, bpftool, BTF and available tracepoints |
| `BpftraceProgramBuilder` | Generates a bpftrace program from device-supported tracepoints |
| `EBPFTraceParser` | Parses `MEMO_*` trace lines and TSV records inside Android |
| `SystemStateCollector` | Collects memory, battery, network, camera/media, display and process/service state |
| `MapleScenarioBuilder` | Builds MAPLE-compatible scenario JSON from eBPF + system state |
| `MapleNative` | JNI bridge for device-local MAPLE engine |
| `AppIdMapping` | Dynamically scans installed launcher apps and maps evidence categories to real apps |
| `ActionExecutor` | Turns MAPLE output into widget updates, warm launch and pressure-aware system actions |
| `MemoWidgetProvider` | Displays Top-3 real app recommendations |

## Evidence Collected

The Android app collects these signals:

- memory: `MemAvailable`, swap, reclaim counters, PSI and LMKD snapshot;
- battery: level, charging state, voltage and temperature;
- network: UDP `sendto` / `recvfrom` tracepoints where available, plus `/proc/net` UDP counters;
- camera/media: cameraserver, media codec, audio server and media session hints;
- display/UI: SurfaceFlinger, RenderThread, input and scheduler activity;
- process/service: foreground package, critical service PIDs and Binder activity.

These are summarized into MAPLE evidence lines such as:

```text
event_type MEMO_BINDER: count=80
event_type MEMO_RECVFROM: count=55
MAPLE evidence/resource category Network IO: count=58
MAPLE evidence/resource category Camera Service: count=42
memory available=1234567kB total=4096000kB pressure=normal
battery level=85% thermal=normal temp_c=31.5
foreground app package=com.example.chat activity=com.example.chat/.MainActivity
```

## Adaptive App Recommendation

The Top-3 list is always real launchable Android apps. The app scanner uses:

- `PackageManager` launcher queries;
- default browser/dialer/SMS/Home handlers;
- intent resolution for camera, gallery, browser, dial, SMS, map, media and payment-like flows;
- requested permissions such as `CAMERA`, `INTERNET`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `CALL_PHONE`, `SEND_SMS`, `NFC`;
- `ApplicationInfo.category`;
- weak package/label lexical hints only as a last signal.

Kernel processes, Binder workers, SurfaceFlinger and MediaCodec services are never shown to the user as recommendations. They only influence evidence and scheduling.

## Actions

MAPLE output and system state are converted into actions:

| Signal | Action |
| --- | --- |
| normal memory and stable thermal state | warm-launch one or two selected apps, then return HOME |
| elevated memory pressure | reduce prewarm budget, run ActivityManager idle maintenance if root is available |
| critical memory pressure | skip warm launch, trim non-primary candidates, optionally drop page cache |
| UDP/network-heavy window | prioritize network-capable apps and refresh network stats |
| camera/media evidence | select camera/gallery/media candidates, but skip if pressure is critical |
| SurfaceFlinger/RenderThread/input activity | keep prewarm budget low to reduce UI jank |
| Binder/system-service-heavy window | refresh service state and keep service evidence in MAPLE context |

## Real User Experiments

Synthetic demo scenarios have been removed. Real experiments now use Android itself to open installed apps or record the current foreground app while eBPF is running. The evidence still comes from kernel tracepoints and system state, then flows through the same parser, scenario builder, MAPLE bridge, app scanner and action executor:

- record current real app usage for a 28-second eBPF window;
- open a real communication/network app, interact with it, then infer follow-up apps/actions;
- open a real camera/photo-capable app and record camera/media/display evidence;
- open a real media/video-capable app and record media/network/display evidence;
- open a payment/security-capable app only if one is installed; otherwise the run is blocked instead of fabricating payment evidence;
- run real Android scroll/input commands against a real launchable app and record display/UI evidence.

Each run writes the MAPLE scenario to the app external files directory and writes raw trace output under `/sdcard/MEMO/logs/real_user_*.trace`. If eBPF tools are missing or cannot attach, the strict pipeline fails and reports the error instead of producing fake events.

## Real eBPF Ablation

The real ablation experiment now runs inside Android against the latest real eBPF scenario. It removes selected evidence families and reruns MAPLE:

- full real eBPF evidence;
- no network evidence;
- no camera/media evidence;
- no display/UI evidence;
- no Binder/service evidence;
- no memory evidence;
- aggregate counters only;
- app/category sequence baseline.

The report keeps a compact metric set: prediction stability, Top-3 app overlap, MAPLE latency, scheduler domain alignment, and action success/latency. This makes the experiment about both prediction and the downstream scheduling plan, not only about whether MAPLE prints an app id.

## Device Deployment

Expected layout:

```text
/data/local/tmp/memo/models/Qwen3.5-0.8B-Q4_K_M.gguf
/data/local/tmp/memo/bpftrace
/data/local/tmp/memo/bpftool
/data/local/tmp/memo/libmaple_engine.so
```

The app probes these files and reports missing capabilities. It does not silently pretend that MAPLE or eBPF succeeded.

## Build Verification

The Android APK builds with:

```powershell
.\gradlew.bat :app:assembleDebug
```

The build includes Kotlin device-side collection code, Widget UI and the `maple-jni` wrapper library for all configured ABIs.
