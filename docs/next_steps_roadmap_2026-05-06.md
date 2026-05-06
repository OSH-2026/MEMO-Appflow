# MEMO-Appflow Device-Side Roadmap and Current Implementation

Date: 2026-05-06

## Decision

The product path is now Android-device-side only.

```text
Android emulator / rooted phone
-> device-local collection
-> device-local parsing and scenario construction
-> device-local MAPLE call
-> adaptive real-app Top-3 recommendation
-> widget update and scheduling actions
```

Host scripts are not part of the product architecture. The host may install the APK, push model/tool binaries, and read logs.

## Implemented in the Android App

| Area | Android implementation |
| --- | --- |
| eBPF/service collection | `EBPFCollectorService`, `EBPFCapabilityProbe`, `BpftraceProgramBuilder`, `DeviceCollectorDeployer` |
| Trace parsing | `EBPFTraceParser` parses `MEMO_*` trace lines and bpftrace TSV records inside Android |
| System state | `SystemStateCollector` collects memory, PSI, LMKD snapshot, battery, UDP/network, foreground app, SurfaceFlinger, camera/media and service hints |
| MAPLE scenario | `MapleScenarioBuilder` builds MAPLE-compatible JSON inside Android |
| MAPLE bridge | `MapleNative` JNI wrapper and device-local shell backend path |
| App recommendation | `AppIdMapping` dynamically scans installed launcher apps and classifies them from roles, intents, permissions and Android category metadata |
| Actions | `ActionExecutor` updates widget, warm-launches apps, reduces prewarm under pressure, trims non-primary candidates, refreshes service/network state |
| UI | `MainActivity` control panel and `MemoWidgetProvider` Top-3 home-screen widget |
| Real user experiments | device-side capture windows open real installed apps or record current usage; no synthetic eBPF events are injected |

## Adaptive App Mapping

The app does not hard-code "App 110 means WeChat" as the product behavior.

The mapping process is:

```text
PackageManager launcher scan
-> default role / default handler detection
-> intent capability probing
-> requested permission inspection
-> ApplicationInfo.category
-> weak package/label lexical hints
-> Top-3 real installed launchable apps
```

Stable semantic categories are still necessary as the protocol between eBPF, MAPLE and actions, for example `Communication`, `Camera Service`, `Media Codec`, `Network IO`, `Memory Management`, and `Display Composition`. The actual packages shown to the user are always selected from installed apps.

## System Evidence and Actions

The app collects:

- memory: available memory, swap, reclaim counters, PSI, LMKD;
- battery: level, plugged state, voltage, temperature;
- network: UDP `sendto` / `recvfrom` where tracepoints are available, plus `/proc/net` state;
- camera/media: cameraserver, media codec, audio, media sessions;
- display/UI: SurfaceFlinger, RenderThread, input and scheduler activity;
- process/service: foreground package, service PIDs, Binder activity.

Actions after MAPLE inference:

- widget and app cache update;
- warm launch recommended apps and return HOME;
- skip or reduce warm launch when memory/thermal pressure is elevated;
- root-only ActivityManager idle maintenance;
- root-only trim-memory requests for non-primary candidates;
- root-only page-cache drop only under critical memory pressure;
- service/network refresh for Binder or UDP-heavy windows.

## Deployment Shape

Expected device layout:

```text
/sdcard/MEMO/models/Qwen3.5-0.8B-Q4_K_M.gguf
/data/local/tmp/memo/bpftrace
/data/local/tmp/memo/bpftool
/data/local/tmp/memo/libmaple_engine.so
```

The APK runs without host Python. If a capability is absent, the app reports it in the control panel instead of fabricating a successful trace or inference.

## Remaining Engineering Work

The Android code path is in place and builds. The next practical work is device packaging:

- cross-compile or provide Android-compatible `bpftrace` / `bpftool` for the emulator architecture;
- cross-compile `libmaple_engine.so` and its llama.cpp dependencies for Android;
- test the pipeline on the custom Android 14 emulator kernel;
- then repeat the same deployment layout on the rooted phone.

The architecture intentionally keeps emulator and real phone deployment the same so the migration cost is tool/kernel compatibility, not rewriting the product.
