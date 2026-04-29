# eBPF-First Reasoning Collector

This milestone is about deep Android system evidence, not application-sequence
prediction. Markov and frequency models are useful baselines, but the research
direction here is reasoning-based prediction and memory scheduling from eBPF
signals.

## Core Claim

MEMO-Appflow should answer scheduling questions from observable system facts:

- Which services is the foreground workload touching?
- What file/resource categories does a cold launch actually depend on?
- Is memory pressure normal, elevated, or critical?
- Should the system prewarm, keep alive, reclaim, defer kill, or hold?

The app package/action name is only an experiment window label. The model-facing
input is a privacy-preserving eBPF evidence summary.

## Evidence Links

The short-term collector targets three primary Android links:

| Link | Tracepoints | Structured Output | Reasoning Use |
| --- | --- | --- | --- |
| Binder service behavior | `binder:binder_transaction` | `binder_service_segments.jsonl` | Infer hardware/service demand such as camera, audio, location, input, window, package |
| Cold-launch resources | `syscalls:sys_enter_openat`, `sys_enter_openat2` | `cold_launch_file_profile.jsonl` | Infer prewarm benefit and file category profile: dex, so, asset, database, cache, model |
| Memory pressure | `vmscan:mm_vmscan_direct_reclaim_begin`, `mm_vmscan_direct_reclaim_end`, `mm_vmscan_kswapd_wake` | `memory_pressure_segments.jsonl` | Gate prewarm, background scans, reclaim mode, and deferred kill |

Process fork/exit tracepoints can be collected as secondary context, but they
are not the main contribution.

## Main Model Interface

The main output is:

```text
scripts/android_ebpf output -> reasoning_scheduler_requests.jsonl
```

Each request follows `scripts/android_ebpf/configs/reasoning_scheduler_request.schema.json`
and asks the reasoning model for:

- `next_resource_need`
- `memory_action`
- `prewarm_allowed`
- `keep_alive_app_ids`
- `reclaim_mode`
- `defer_kill_app_ids`
- `confidence`
- evidence citations
- rationale

The prompt explicitly says not to use a frequency or Markov app sequence. The
input is eBPF evidence: Binder service segments, cold-launch file profiles,
memory pressure segments, and anonymized app actor hashes.

## Compatibility Output

`llm_context_windows.jsonl` remains for midterm-deck ATP/NTP compatibility:

- ATP: app type prediction
- NTP: specific app ID prediction

That file is not the core eBPF milestone. It exists so the teammate building the
LLM app-prediction interface can consume the same run output if needed.

## Privacy Boundary

- Binder payloads are never parsed.
- Raw file paths are dropped after path-category extraction.
- Model-facing app identities use `app_id_hash`.
- Package names remain only in host-side collection/debug data and are omitted
  from `reasoning_scheduler_requests.jsonl`.

## Run Path

```powershell
python scripts/android_ebpf/android_ebpf_collect.py probe `
  --adb "$adb" `
  --out dataset_cache/ebpf_runs/probe.json

python scripts/android_ebpf/android_ebpf_collect.py collect `
  --adb "$adb" `
  --out dataset_cache/ebpf_runs/run_001 `
  --scenario mixed_daily `
  --cold-launch-repeats 5
```

For local validation without a rooted Android target:

```powershell
python scripts/android_ebpf/android_ebpf_collect.py parse `
  --raw-trace scripts/android_ebpf/fixtures/sample_trace.tsv `
  --actions-json scripts/android_ebpf/fixtures/sample_actions.json `
  --uid-map-json scripts/android_ebpf/fixtures/sample_uid_map.json `
  --out dataset_cache/ebpf_runs/fixture_parse

python -m unittest scripts.android_ebpf.tests.test_android_ebpf_collect
```
