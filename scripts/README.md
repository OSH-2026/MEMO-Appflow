# Scripts

The scripts directory is now focused on Android eBPF evidence collection and
MAPLE integration.

Primary entry points:

- `android_ebpf/android_ebpf_collect.py`
- `android_ebpf/maple_context_from_ebpf.py`

The old app-sequence replay, macOS tracing, and dataset helper scripts were
removed because they are not part of the current eBPF-first direction.
