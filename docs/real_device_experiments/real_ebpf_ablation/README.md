# Real eBPF Ablation Experiment

This folder contains the Android-side real eBPF ablation run from 2026-05-07.

Experiment track owner: Jingyi Guo.

## Files

| File | Meaning |
| --- | --- |
| `real_ebpf_ablation_result_interpretation.md` | Human-readable result interpretation, including numbers and percentages |
| `latest_real_ablation.json` | Full Android-generated ablation report |
| `latest_maple_scenario.json` | MAPLE scenario built from real eBPF evidence |
| `latest_pipeline_latency.json` | Pipeline latency from the real camera/photo run |
| `latest_real_user_experiment.txt` | Experiment metadata |
| `memo_pipeline.xml` | App-persisted state after the run |
| `real_user_1778095657670.trace` | Raw real eBPF trace from Android |

## Main Numbers

Compared with the app-sequence baseline:

| Metric | App-sequence baseline | Full real eBPF | Change |
| --- | ---: | ---: | ---: |
| MAPLE latency | 69.104s | 36.468s | -32.636s, 47.2% faster |
| End-to-end latency | 74.343s | 41.991s | -32.352s, 43.5% faster |
| Task-domain hit rate | 100.0% | 100.0% | tied |

For prediction quality, there is no manual "next app" label in this real-user run, so the result uses task-domain hit rate and full-eBPF stability. See `real_ebpf_ablation_result_interpretation.md`.
