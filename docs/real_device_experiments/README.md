# Real Device / Emulator Experiments

This folder contains experiments that run against Android device-side evidence. In the current repository, the verified target is the rooted Android emulator; the design is the same deployment shape expected for a rooted physical phone.

Current real device/emulator experiment track owner: Jingyi Guo.

Folders:

```text
real_ebpf_ablation/
```

The real eBPF ablation starts from a natural Android camera/photo interaction, captures eBPF evidence on-device, builds a MAPLE scenario on-device, runs MAPLE on-device, and evaluates prediction plus scheduling metrics.
