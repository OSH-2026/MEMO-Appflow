# Real Raw eBPF Ablation Result Interpretation

Date: 2026-05-28

Experiment track owner: Jingyi Guo.

Main result file:

```text
docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json
```

This report was generated on a rooted Pixel 5 from a real raw eBPF scroll/display scenario. It is not a synthetic dataset run and not a host Python prediction benchmark.

## Experiment Background

The product run simulated a normal user using the phone:

```text
Launch real Chrome
-> drive real Android input swipes
-> raw eBPF collector observes kernel/system activity
-> Android app parses the trace
-> Android app builds a MAPLE scenario
-> device-local MAPLE predicts resource/app demand
-> adaptive scanner maps the result to real installed apps
-> ActionExecutor plans widget, network, memory, display, Binder/service and warm-launch actions
```

The raw trace came from:

```text
/sdcard/MEMO/logs/real_user_1779966570132.trace
```

The pulled repository copy is:

```text
docs/real_device_experiments/real_ebpf_ablation/real_user_1779966570132.trace
```

## What The Ablation Tests

The full configuration uses the whole real raw eBPF scenario:

```text
full_real_ebpf
```

Each ablation removes one evidence family and reruns MAPLE:

| Config | Removed or changed evidence |
| --- | --- |
| `no_network` | UDP `sendto` / `recvfrom` and Network IO evidence |
| `no_camera_media` | Camera Service and Media Codec evidence |
| `no_display_ui` | SurfaceFlinger, RenderThread, input/display evidence |
| `no_binder_service` | Binder and Android service evidence |
| `no_memory` | Memory pressure, reclaim, LMKD and thermal-memory hints |
| `counters_only` | Keeps aggregate eBPF counters but removes target/workflow hints |
| `app_sequence_baseline` | Removes eBPF/system evidence and keeps only category/app sequence context |

The goal is to measure whether raw eBPF evidence changes MAPLE's prediction and the downstream scheduling plan.

## Metrics

| Metric | Meaning |
| --- | --- |
| predicted app id | MAPLE Stage 2 app id |
| Stage 1 category | MAPLE's resource/app category reasoning result |
| Top-1 app | First real Android launchable app after adaptive mapping |
| predicted domains | Resource domains inferred from Stage 1 and Top-3 categories |
| Top-3 overlap | Jaccard overlap with `full_real_ebpf` Top-3 |
| action-domain overlap | Overlap between each ablation's action domains and the full-eBPF action domains |
| scheduler alignment | How well predicted resource domains match planned scheduling action domains |
| MAPLE latency | Device-side MAPLE inference time |
| end-to-end latency | MAPLE + app mapping + non-intrusive action planning |

There is no manually labeled "next app" ground truth in this real-user run. A real scroll/display session can be followed by many reasonable next actions, so the report does not invent a supervised accuracy number. Instead, it compares the full-eBPF condition against ablations and the sequence-only baseline.

## Full eBPF vs App-Sequence Baseline

| Metric | App-sequence baseline | Full real raw eBPF | Change |
| --- | ---: | ---: | ---: |
| MAPLE latency | 57.089s | 18.753s | -38.336s, 67.2% faster |
| End-to-end latency | 58.229s | 19.981s | -38.248s, 65.7% faster |
| Stage 1 category | Network IO | App Process Runtime | changed |
| Predicted app id | 245 | 280 | changed |
| Top-1 real app | `com.android.chrome` | `com.android.settings` | changed |
| Top-3 overlap vs full | 0.50 | 1.00 | full eBPF reference |
| Action-domain overlap vs full | 0.89 | 1.00 | full eBPF reference |

Interpretation:

The sequence-only baseline mostly sees a shallow app/category history and predicts a network-oriented Chrome follow-up. Full raw eBPF sees the deeper runtime context of the Chrome scroll window: network IO, Binder/service IPC, app process runtime, display composition, media codec and file access. That changes both MAPLE's Stage 1 result and the real app mapping.

## Per-Variant Results

| Config | Predicted id | Stage 1 | Top-1 app | Predicted domains | MAPLE latency | End-to-end | Stage1 overlap | Top-3 overlap | Action-domain overlap |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `full_real_ebpf` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 18.753s | 19.981s | 1.00 | 1.00 | 1.00 |
| `no_network` | 230 | Android Service IPC | `com.google.android.apps.messaging` | binder_service, display_ui | 17.085s | 17.959s | 0.00 | 0.50 | 0.88 |
| `no_camera_media` | 245 | Network IO | `com.android.chrome` | network, binder_service | 19.854s | 20.920s | 0.00 | 1.00 | 1.00 |
| `no_display_ui` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 18.210s | 19.341s | 1.00 | 1.00 | 0.88 |
| `no_binder_service` | 280 | App Process Runtime | `com.android.settings` | network, display_ui | 18.621s | 19.319s | 1.00 | 0.50 | 0.88 |
| `no_memory` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 18.534s | 19.674s | 1.00 | 1.00 | 1.00 |
| `counters_only` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 23.110s | 24.255s | 1.00 | 1.00 | 1.00 |
| `app_sequence_baseline` | 245 | Network IO | `com.android.chrome` | network, binder_service | 57.089s | 58.229s | 0.00 | 1.00 | 1.00 |

## Important Deltas Against Full eBPF

| Removed evidence | Prediction impact | Latency impact |
| --- | --- | --- |
| Network | predicted id changes 280 -> 230; Top-1 changes Settings -> Messages | MAPLE -3.105s (-16.4%); end-to-end -3.534s (-17.5%) |
| Camera/media | predicted id changes 280 -> 245; Top-1 changes Settings -> Chrome | MAPLE -1.977s (-10.4%); end-to-end -2.112s (-10.5%) |
| Display/UI | predicted id unchanged; Top-1 unchanged; action-domain overlap drops to 0.88 | MAPLE -2.114s (-11.2%); end-to-end -2.321s (-11.5%) |
| Binder/service | predicted id unchanged; Top-1 unchanged; Top-3 overlap drops to 0.50 | MAPLE -1.055s (-5.6%); end-to-end -1.488s (-7.4%) |
| Memory | prediction unchanged; action-domain overlap remains 1.00 | MAPLE -0.151s (-0.8%); end-to-end -0.267s (-1.3%) |
| Counters only | prediction unchanged in this run | MAPLE -0.355s (-1.9%); end-to-end -0.391s (-1.9%) |
| App-sequence baseline | predicted id changes 280 -> 260; Stage 1 changes App Process Runtime -> Media Codec | MAPLE +27.819s (+146.8% vs full); end-to-end +27.792s (+137.9% vs full) |

## What The Results Mean

Network and camera/media evidence are most decisive for the final app prediction in this scroll/display run. Removing either changes MAPLE's predicted app id and changes the user-facing Top-1 app.

Binder/service and display/UI evidence are still useful even when they do not change Top-1. They change the action-domain profile and Top-3 composition, which matters because MEMO-Appflow is a scheduling product, not just a next-app classifier.

Memory evidence is mostly a safety constraint here. It decides whether warm launch should be aggressive, reduced or skipped. In this run memory pressure was normal, so removing memory did not change the prediction.

The sequence-only baseline is not enough for this product goal. It loses the deeper runtime evidence that MAPLE uses to reason about resource demand, and it was much slower in this run.

## Bottom Line

Adding real raw eBPF evidence improves the product signal in this run:

- prediction changes from `Network IO / com.android.chrome` to `App Process Runtime / com.android.settings`;
- end-to-end latency improves from 58.229s to 19.981s, a 65.7% reduction;
- MAPLE latency improves from 57.089s to 18.753s, a 67.2% reduction;
- removing Network or Camera/media evidence changes the final predicted app, showing that those evidence families carry important signal;
- removing Binder/service or Display/UI changes action-domain or Top-3 stability, showing that they matter for downstream scheduling.

The result should not be overstated as "eBPF always makes every metric better." The correct conclusion is: in this real Android scroll/display scenario, raw eBPF gives better resource-aware prediction and scheduling context than the app-sequence baseline, while also reducing measured MAPLE/end-to-end latency versus that baseline.
