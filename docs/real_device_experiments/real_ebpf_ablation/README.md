# Real Raw eBPF Ablation Experiment

This folder contains the Android-device-side raw eBPF ablation run from 2026-05-28.

Experiment track owner: Jingyi Guo.

Device: rooted Pixel 5 / redfin, Android 14, Linux 4.19.278, arm64.

## Files

| File | Meaning |
| --- | --- |
| `real_ebpf_ablation_result_interpretation.md` | Human-readable result interpretation, including numbers and percentages |
| `latest_real_ablation.json` | Full Android-generated ablation report |
| `latest_maple_scenario.json` | MAPLE scenario built from real raw eBPF evidence |
| `latest_pipeline_latency.json` | Pipeline latency from the real scroll/display run |
| `latest_full_local_evaluation.txt` | One-tap local evaluation completion summary |
| `latest_real_user_experiment.txt` | Experiment metadata |
| `memo_pipeline.xml` | App-persisted recommendations, MAPLE result, evidence and actions |
| `real_user_1779966570132.trace` | Raw real eBPF trace from Android |

## Main Numbers

Compared with the app-sequence baseline:

| Metric | App-sequence baseline | Full real raw eBPF | Change |
| --- | ---: | ---: | ---: |
| MAPLE latency | 57.089s | 18.753s | -38.336s, 67.2% faster |
| End-to-end latency | 58.229s | 19.981s | -38.248s, 65.7% faster |
| Stage 1 category | Network IO | App Process Runtime | changed |
| Predicted app id | 245 | 280 | changed |
| Top-1 real app | `com.android.chrome` | `com.android.settings` | changed |

This is a one-tap local real user-operation experiment, not a synthetic trace. The app launched Chrome and drove real Android input swipes while the raw eBPF collector was attached, then ran MAPLE and 8 ablations on the phone. The collected evidence contains Binder, openat, UDP sendto/recvfrom, scheduler, process fork/exit/exec, display/UI and system state signals.

There is no manual next-app ground truth in this run, so the report does not invent a supervised accuracy number. It compares prediction stability, Top-3 overlap, scheduler/action alignment and latency across evidence ablations. See `real_ebpf_ablation_result_interpretation.md`.
