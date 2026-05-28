# Real User eBPF Experiments

Date: 2026-05-07

These experiments replace the removed synthetic scenarios. They are not hand-written event sequences. The app starts a real device-side eBPF capture window, launches or records real Android app usage, parses the resulting kernel evidence inside Android, calls MAPLE, maps the result to real installed apps, executes scheduling actions, and updates the widget.

The phone UI now has a one-tap `Run Full Local Evaluation` path. It runs the real scroll/display experiment, publishes the MAPLE-driven Top-3/actions, then runs the real eBPF ablation on the same device-generated scenario. The host can inspect outputs, but does not execute product logic.

## Product Path

```text
tap experiment in MEMO app
-> EBPFCollectorService starts the raw eBPF collector on device
-> real Android app launch/manual usage/input gestures happen
-> raw trace is written under /sdcard/MEMO/logs/real_user_*.trace
-> EBPFTraceParser parses real MEMO records
-> MapleScenarioBuilder builds latest_maple_scenario.json on device
-> MAPLE predicts resource/app demand
-> AppIdMapping scans installed apps and returns Top-3 real apps
-> ActionExecutor runs widget, warm launch, memory/network/media/display/service actions
```

## Available Runs

| App control | What is real |
| --- | --- |
| Record Current Real Usage (28s) | User manually switches apps while eBPF records the device |
| Open Real Communication App + Record | PackageManager chooses a real communication/network-capable app and records its launch/use |
| Open Real Camera/Photo App + Record | PackageManager chooses a real camera/photo-capable app and records camera/media/display behavior |
| Open Real Media/Video App + Record | PackageManager chooses a real media/network-capable app and records media/network/display behavior |
| Open Payment/Security App if Installed + Record | Runs only against a real matching app; otherwise records a no-target real window |
| Run Real Scroll/Display Interaction + Record | Launches a real app and uses Android `input swipe` while eBPF records UI/display evidence |
| Run Full Local Evaluation | One tap runs real scroll/display capture, MAPLE, Top-3, ActionExecutor, and 8-config ablation locally |

The Android `input` commands are only user-action simulation. They do not create fake eBPF rows. The collected evidence still comes from the kernel and system services.

## Output Files On Device

```text
/sdcard/MEMO/logs/real_user_*.trace
/sdcard/Android/data/com.memoos/files/latest_real_user_experiment.txt
/sdcard/Android/data/com.memoos/files/latest_maple_scenario.json
/sdcard/Android/data/com.memoos/files/latest_full_local_evaluation.txt
/sdcard/MEMO/ablations/latest_real_ablation.json
/sdcard/MEMO/pressure/latest_pressure_experiment.json
```

The app UI shows user-facing Top-3 apps and system actions. Raw eBPF evidence is kept in advanced diagnostics and the files above.

## Real User App Pressure A/B

The pressure experiment is the product-performance check. It asks whether MEMO reduces phone pressure while the user uses other real apps, not just whether MEMO's own inference pipeline finishes quickly.

App control:

```text
Run App Pressure A/B Test
```

Runtime:

```text
same prior real app action without MEMO
-> measured app workload baseline

same prior real app action with raw eBPF + MAPLE + ActionExecutor
-> measured app workload MEMO-on
```

Latest rooted Pixel 5 result:

```text
report: docs/real_device_experiments/user_app_pressure/latest_pressure_experiment.json
workloads: Chrome, Magisk, Tencent Meeting
conditions: normal_recent_usage, crowded_cached_apps
comparisons: 6
avg pressure score improvement: +35.41%
avg MemAvailable-drop improvement: +62.10%
avg reclaim improvement: +56.08%
avg launch TotalTime improvement: +0.15%
avg CPU busy improvement: -0.53%
avg iowait improvement: -2.79%
```

Interpretation: on this phone, MEMO's clearest benefit is memory/reclaim pressure, especially with crowded cached apps. Launch time is almost unchanged, and CPU/iowait overhead remains. The detailed Chinese interpretation is in `docs/real_device_experiments/user_app_pressure/README.md`.

## Real eBPF Ablation Metrics

The ablation runner uses the latest real Android-side eBPF scenario and removes one evidence family at a time before calling MAPLE again. It measures a small set of product-facing metrics:

| Metric | Meaning |
| --- | --- |
| prediction stability | whether the predicted MAPLE app id changes versus full real eBPF |
| Top-3 app overlap | Jaccard overlap between the real launchable Top-3 apps and the full-evidence Top-3 |
| MAPLE latency | device-side MAPLE inference time for each variant |
| scheduler domain alignment | overlap between predicted resource domains and actions planned by `ActionExecutor` |
| action success/latency | action status counts and action execution time in non-intrusive mode |

Latest checked run:

```text
report: docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json
source scenario: real_user_camera_photo_usage
configs: 8
MAPLE available: 8/8
changed predicted app: no_display_ui, no_binder_service, app_sequence_baseline
changed Top-1 app: no_display_ui, no_binder_service, app_sequence_baseline
changed scheduler domains: no_network, no_camera_media, no_display_ui, no_binder_service
task-domain hit rate best: full_real_ebpf, no_binder_service, no_memory, counters_only, app_sequence_baseline = 100.0%
full-eBPF prediction stability best: full_real_ebpf, no_memory, counters_only keep Stage1/Top1/Top3/action-domain stable
end-to-end range: 30.1s to 74.3s
```
