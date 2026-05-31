# Real Device / Emulator Experiments

This folder contains experiments that run against Android device-side evidence. The current verified target is a rooted Pixel 5 physical phone; the same deployment shape is still used for rooted emulator runs.

Current real device/emulator experiment track owner: Jingyi Guo.

Folders:

```text
real_usage_100/
user_app_pressure/
button_audit/
real_ebpf_ablation/
```

Current verified runs:

- `real_usage_100/`: UI-triggered 100 real app openings with raw eBPF, MAPLE, Top-3, ActionExecutor, and ablation.
- `user_app_pressure/`: MEMO-off vs MEMO-on A/B experiment measuring real app workload pressure.
- `button_audit/`: UIAutomator button audit proving the visible product controls call real functions.
- `real_ebpf_ablation/`: older real eBPF ablation track retained for comparison.

The latest narrative summary is `docs/2026-06-01_real_phone_product_verification.md`.
