#!/usr/bin/env python3
"""
Android eBPF collection orchestrator for MEMO-Appflow.

The default collection path is host driven:
  1. probe tracepoint availability on a rooted/userdebug Android target,
  2. run a bpftrace program through adb,
  3. drive a repeatable app-launch workload,
  4. normalize raw kernel events into MEMO JSONL datasets,
  5. emit LLM context windows matching the ATP/NTP prompt contract.

The script also supports parse-only mode so the structuring pipeline can be
tested from saved raw traces without a device.
"""

from __future__ import annotations

import argparse
import tarfile
import urllib.request
import hashlib
import json
import os
import shlex
import subprocess
import sys
import threading
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parent
DEFAULT_CONFIG = ROOT / "configs" / "service_categories.json"
DEFAULT_WORKLOAD = ROOT / "configs" / "workload_scenarios.json"
DEFAULT_BPFTRACE = ROOT / "bpftrace" / "memo_appflow_trace.bt"
TRACE_REMOTE = "/data/local/tmp/memo_appflow_trace.bt"
DEFAULT_REMOTE_TOOL_DIR = "/data/local/tmp/memo-tools"
DEFAULT_BPFTRACE_URL = "https://github.com/bpftrace/bpftrace/releases/download/v0.25.1/bpftrace"
DEFAULT_BPFTOOL_URL = "https://github.com/libbpf/bpftool/releases/download/v7.7.0/bpftool-v7.7.0-amd64.tar.gz"


EVENT_TYPE_CODES = {
    "binder": 1,
    "file": 2,
    "memory": 3,
    "process_fork": 4,
    "process_exit": 5,
    "network": 6,
    "sched": 7,
    "input": 8,
    "graphics": 9,
}

PATH_CATEGORY_IDS = {
    "dex": 1,
    "so": 2,
    "asset": 3,
    "database": 4,
    "cache": 5,
    "model": 6,
    "other": 99,
}


@dataclass
class RawEvent:
    ts_ns: int
    event_type: str
    uid: int
    pid: int
    tid: int
    comm: str
    arg0: int = 0
    arg1: int = 0
    arg2: int = 0
    arg3: int = 0
    detail: str = ""


@dataclass
class Window:
    window_id: int
    scenario_id: str
    package_name: str | None
    app_alias: str | None
    action_type: str
    resource_phase: str
    start_ts_ns: int
    end_ts_ns: int
    host_start_ms: int | None = None
    host_end_ms: int | None = None


@dataclass
class CollectResult:
    raw_trace: Path
    action_timeline: Path | None
    tracepoints: list[str] = field(default_factory=list)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> int:
    count = 0
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
            count += 1
    return count


def run(args: list[str], timeout: int = 30, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=check)


def adb_shell(adb: str, command: str, *, su: bool = False, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    if su:
        return run([adb, "shell", "su", "-c", command], timeout=timeout, check=False)
    return run([adb, "shell", command], timeout=timeout, check=False)


def device_uptime_ns(adb: str) -> int:
    result = adb_shell(adb, "cat /proc/uptime", timeout=10)
    if result.returncode != 0:
        raise RuntimeError(f"failed to read /proc/uptime: {result.stderr.strip()}")
    seconds = float(result.stdout.strip().split()[0])
    return int(seconds * 1_000_000_000)


def package_uid_map(adb: str) -> dict[int, list[str]]:
    commands = [
        "cmd package list packages -U",
        "pm list packages -U",
    ]
    output = ""
    for command in commands:
        result = adb_shell(adb, command, timeout=30)
        if result.returncode == 0 and result.stdout.strip():
            output = result.stdout
            break

    uid_to_packages: dict[int, list[str]] = defaultdict(list)
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        pkg = line.split()[0].removeprefix("package:")
        uid = None
        for part in line.split():
            if part.startswith("uid:"):
                try:
                    uid = int(part.removeprefix("uid:"))
                except ValueError:
                    uid = None
        if uid is not None:
            uid_to_packages[uid].append(pkg)
    return dict(uid_to_packages)


def available_tracepoints(adb: str, *, su: bool = True) -> list[str]:
    command = (
        "cat /sys/kernel/tracing/available_events 2>/dev/null || "
        "cat /sys/kernel/debug/tracing/available_events 2>/dev/null"
    )
    result = adb_shell(adb, command, su=su, timeout=20)
    if result.returncode != 0:
        return []
    return sorted({line.strip() for line in result.stdout.splitlines() if ":" in line})


def first_device_executable(adb: str, candidates: list[str], *, su: bool) -> str:
    quoted = " ".join(shlex.quote(candidate) for candidate in candidates)
    result = adb_shell(
        adb,
        f"for p in {quoted}; do "
        'if command -v "$p" >/dev/null 2>&1; then command -v "$p"; exit 0; fi; '
        'if [ -x "$p" ]; then echo "$p"; exit 0; fi; '
        "done",
        su=su,
        timeout=10,
    )
    return result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""


def probe(adb: str, *, su: bool, out: Path | None, bpftool: str | None = None) -> dict[str, Any]:
    tracepoints = available_tracepoints(adb, su=su)
    bpftool_path = bpftool or first_device_executable(
        adb,
        [
            "/data/local/tmp/memo/bpftool",
            "/data/local/tmp/bpftool",
            "/system/bin/bpftool",
            "/vendor/bin/bpftool",
            "bpftool",
        ],
        su=su,
    )
    feature = adb_shell(
        adb,
        f"{shlex.quote(bpftool_path)} feature probe kernel 2>/dev/null | head -80 || true"
        if bpftool_path
        else "true",
        su=su,
        timeout=25,
    )
    bpftrace_path = first_device_executable(
        adb,
        [
            "/data/local/tmp/memo/bpftrace",
            "/data/local/tmp/bpftrace",
            "/system/bin/bpftrace",
            "/vendor/bin/bpftrace",
            "bpftrace",
        ],
        su=su,
    )
    result = {
        "generated_at": now_iso(),
        "adb": adb,
        "su": su,
        "tracepoint_count": len(tracepoints),
        "required_tracepoints": required_tracepoint_status(tracepoints),
        "bpftrace_path": bpftrace_path,
        "bpftool_path": bpftool_path,
        "bpftool_feature_head": feature.stdout.strip().splitlines(),
    }
    if out:
        write_json(out, result)
    return result


def bpf_inventory(adb: str, *, su: bool, bpftool: str | None = None) -> dict[str, Any]:
    pinned = adb_shell(
        adb,
        "find /sys/fs/bpf -maxdepth 3 \\( -type f -o -type l \\) 2>/dev/null | sort | head -240",
        su=su,
        timeout=30,
    )
    version_lines: list[str] = []
    feature_lines: list[str] = []
    if bpftool:
        version = adb_shell(adb, f"{shlex.quote(bpftool)} version 2>/dev/null || true", su=su, timeout=15)
        feature = adb_shell(adb, f"{shlex.quote(bpftool)} feature probe kernel 2>/dev/null | head -120 || true", su=su, timeout=45)
        version_lines = [line for line in version.stdout.splitlines() if line.strip()]
        feature_lines = [line for line in feature.stdout.splitlines() if line.strip()]
    return {
        "pinned_path_count_sampled": len([line for line in pinned.stdout.splitlines() if line.strip()]),
        "pinned_paths_sample": [line.strip() for line in pinned.stdout.splitlines() if line.strip()],
        "bpftool": bpftool,
        "bpftool_version": version_lines,
        "bpftool_feature_head": feature_lines,
    }


def install_tools(args: argparse.Namespace) -> dict[str, Any]:
    cache_dir = args.cache_dir
    remote_dir = args.remote_dir
    cache_dir.mkdir(parents=True, exist_ok=True)

    bpftrace_path = cache_dir / "bpftrace-v0.25.1"
    bpftool_archive = cache_dir / "bpftool-v7.7.0-amd64.tar.gz"
    bpftool_extract = cache_dir / "bpftool-v7.7.0-amd64"
    bpftool_path = bpftool_extract / "bpftool"

    if not bpftrace_path.exists():
        urllib.request.urlretrieve(args.bpftrace_url, bpftrace_path)
    if not bpftool_archive.exists():
        urllib.request.urlretrieve(args.bpftool_url, bpftool_archive)
    if not bpftool_path.exists():
        bpftool_extract.mkdir(parents=True, exist_ok=True)
        with tarfile.open(bpftool_archive, "r:gz") as archive:
            archive.extractall(bpftool_extract)

    adb_shell(args.adb, f"mkdir -p {shlex.quote(remote_dir)}", su=args.su, timeout=15)
    run([args.adb, "push", str(bpftrace_path), f"{remote_dir}/bpftrace"], timeout=180, check=False)
    run([args.adb, "push", str(bpftool_path), f"{remote_dir}/bpftool"], timeout=90, check=False)
    adb_shell(
        args.adb,
        f"chmod 755 {shlex.quote(remote_dir)}/bpftrace {shlex.quote(remote_dir)}/bpftool",
        su=args.su,
        timeout=15,
    )

    bpftrace_version = adb_shell(args.adb, f"{shlex.quote(remote_dir)}/bpftrace --version", su=args.su, timeout=20)
    bpftool_version = adb_shell(args.adb, f"{shlex.quote(remote_dir)}/bpftool version", su=args.su, timeout=20)
    result = {
        "schema_version": "memo.ebpf_tool_install.v1",
        "generated_at": now_iso(),
        "remote_dir": remote_dir,
        "bpftrace": f"{remote_dir}/bpftrace",
        "bpftool": f"{remote_dir}/bpftool",
        "bpftrace_version": bpftrace_version.stdout.strip().splitlines(),
        "bpftool_version": bpftool_version.stdout.strip().splitlines(),
        "host_cache_dir": str(cache_dir),
    }
    if args.out:
        write_json(args.out, result)
    return result


def required_tracepoint_status(tracepoints: Iterable[str]) -> dict[str, bool]:
    present = set(tracepoints)
    required = [
        "binder:binder_transaction",
        "syscalls:sys_enter_openat",
        "syscalls:sys_enter_openat2",
        "vmscan:mm_vmscan_direct_reclaim_begin",
        "vmscan:mm_vmscan_direct_reclaim_end",
        "vmscan:mm_vmscan_kswapd_wake",
        "sched:sched_process_fork",
        "sched:sched_process_exit",
    ]
    return {name: name in present for name in required}


def device_arch(adb: str) -> str:
    result = adb_shell(adb, "uname -m", timeout=10)
    return result.stdout.strip() or "unknown"


def openat_syscall_ids(syscall_abi: str) -> list[int]:
    if syscall_abi in {"x86_64", "amd64"}:
        return [257, 437]
    if syscall_abi in {"aarch64", "arm64"}:
        return [56, 437]
    return []


def build_bpftrace_program(tracepoints: Iterable[str], *, syscall_abi: str = "x86_64") -> tuple[str, list[str]]:
    present = set(tracepoints)
    probes: list[str] = []
    enabled: list[str] = []

    if "binder:binder_transaction" in present:
        enabled.append("binder:binder_transaction")
        probes.append(
            r'''
tracepoint:binder:binder_transaction
{
  printf("MEMO\t%llu\tbinder\t%d\t%d\t%d\t%s\t%d\t%d\t%d\t%d\t-\n",
    nsecs, uid, pid, tid, comm, args->code, args->flags, args->to_proc, args->to_thread);
}
'''.strip()
        )

    for event_name in ("syscalls:sys_enter_openat", "syscalls:sys_enter_openat2"):
        if event_name in present:
            enabled.append(event_name)
            probes.append(
                f'''
tracepoint:{event_name.replace(":", ":")}
{{
  printf("MEMO\\t%llu\\tfile\\t%d\\t%d\\t%d\\t%s\\t0\\t0\\t0\\t0\\t%s\\n",
    nsecs, uid, pid, tid, comm, str(args->filename));
}}
'''.strip()
            )

    syscall_openat_enabled = any(
        event_name in present
        for event_name in ("syscalls:sys_enter_openat", "syscalls:sys_enter_openat2")
    )
    raw_open_ids = openat_syscall_ids(syscall_abi)
    if not syscall_openat_enabled and "raw_syscalls:sys_enter" in present and raw_open_ids:
        enabled.append(f"raw_syscalls:sys_enter(openat:{syscall_abi})")
        open_filter = " || ".join(f"args->id == {syscall_id}" for syscall_id in raw_open_ids)
        probes.append(
            f'''
tracepoint:raw_syscalls:sys_enter
/{open_filter}/
{{
  printf("MEMO\\t%llu\\tfile\\t%d\\t%d\\t%d\\t%s\\t20\\t0\\t0\\t0\\t%s\\n",
    nsecs, uid, pid, tid, comm, str(args->args[1]));
}}
'''.strip()
        )

    for event_name, detail, code in (
        ("filemap:mm_filemap_add_to_page_cache", "filemap_add_to_page_cache", 10),
        ("filemap:mm_filemap_delete_from_page_cache", "filemap_delete_from_page_cache", 11),
        ("ext4:ext4_readpage", "ext4_readpage", 12),
        ("f2fs:f2fs_readpage", "f2fs_readpage", 13),
    ):
        if event_name in present:
            enabled.append(event_name)
            probes.append(
                f'''
tracepoint:{event_name.replace(":", ":")}
{{
  printf("MEMO\\t%llu\\tfile\\t%d\\t%d\\t%d\\t%s\\t{code}\\t0\\t0\\t0\\t{detail}\\n",
    nsecs, uid, pid, tid, comm);
}}
'''.strip()
            )

    memory_events = [
        ("vmscan:mm_vmscan_direct_reclaim_begin", "direct_reclaim_begin", 1),
        ("vmscan:mm_vmscan_direct_reclaim_end", "direct_reclaim_end", 2),
        ("vmscan:mm_vmscan_kswapd_wake", "kswapd_wake", 3),
    ]
    for event_name, detail, code in memory_events:
        if event_name in present:
            enabled.append(event_name)
            probes.append(
                f'''
tracepoint:{event_name.replace(":", ":")}
{{
  printf("MEMO\\t%llu\\tmemory\\t%d\\t%d\\t%d\\t%s\\t{code}\\t0\\t0\\t0\\t{detail}\\n",
    nsecs, uid, pid, tid, comm);
}}
'''.strip()
            )

    if "sched:sched_process_fork" in present:
        enabled.append("sched:sched_process_fork")
        probes.append(
            r'''
tracepoint:sched:sched_process_fork
{
  printf("MEMO\t%llu\tprocess_fork\t%d\t%d\t%d\t%s\t%d\t%d\t0\t0\t%s\n",
    nsecs, uid, pid, tid, comm, args->parent_pid, args->child_pid, str(args->child_comm));
}
'''.strip()
        )

    if "sched:sched_process_exit" in present:
        enabled.append("sched:sched_process_exit")
        probes.append(
            r'''
tracepoint:sched:sched_process_exit
{
  printf("MEMO\t%llu\tprocess_exit\t%d\t%d\t%d\t%s\t0\t0\t0\t0\t%s\n",
    nsecs, uid, pid, tid, comm, str(args->comm));
}
'''.strip()
        )

    for event_name, direction, code in (
        ("syscalls:sys_enter_sendto", "send", 1),
        ("syscalls:sys_enter_recvfrom", "recv", 2),
        ("net:net_dev_queue", "tx_dev_queue", 3),
        ("net:netif_receive_skb", "rx_netif_receive", 4),
    ):
        if event_name in present:
            enabled.append(event_name)
            probes.append(
                f'''
tracepoint:{event_name.replace(":", ":")}
{{
  printf("MEMO\\t%llu\\tnetwork\\t%d\\t%d\\t%d\\t%s\\t{code}\\t%d\\t0\\t0\\t{direction}\\n",
    nsecs, uid, pid, tid, comm, args->len);
}}
'''.strip()
            )

    if "sched:sched_switch" in present:
        enabled.append("sched:sched_switch")
        probes.append(
            r'''
tracepoint:sched:sched_switch
{
  printf("MEMO\t%llu\tsched\t%d\t%d\t%d\t%s\t%d\t%d\t0\t0\tswitch\n",
    nsecs, uid, pid, tid, comm, args->prev_pid, args->next_pid);
}
'''.strip()
        )

    if "sched:sched_wakeup" in present:
        enabled.append("sched:sched_wakeup")
        probes.append(
            r'''
tracepoint:sched:sched_wakeup
{
  printf("MEMO\t%llu\tsched\t%d\t%d\t%d\t%s\t%d\t%d\t0\t0\twakeup\n",
    nsecs, uid, pid, tid, comm, args->pid, args->prio);
}
'''.strip()
        )

    for event_name in (
        "input:input_event",
        "android_fs_dataread:android_fs_dataread_start",
        "dma_fence:dma_fence_emit",
        "gpu_mem:gpu_mem_total",
    ):
        if event_name not in present:
            continue
        enabled.append(event_name)
        if event_name.startswith("input:"):
            probes.append(
                f'''
tracepoint:{event_name.replace(":", ":")}
{{
  printf("MEMO\\t%llu\\tinput\\t%d\\t%d\\t%d\\t%s\\t0\\t0\\t0\\t0\\tinput_event\\n",
    nsecs, uid, pid, tid, comm);
}}
'''.strip()
            )
        else:
            probes.append(
                f'''
tracepoint:{event_name.replace(":", ":")}
{{
  printf("MEMO\\t%llu\\tgraphics\\t%d\\t%d\\t%d\\t%s\\t0\\t0\\t0\\t0\\t{event_name}\\n",
    nsecs, uid, pid, tid, comm);
}}
'''.strip()
            )

    header = 'BEGIN { printf("MEMO\\t0\\tstatus\\t0\\t0\\t0\\tmemo\\t0\\t0\\t0\\t0\\tcollector_started\\n"); }\n'
    footer = 'END { printf("MEMO\\t0\\tstatus\\t0\\t0\\t0\\tmemo\\t0\\t0\\t0\\t0\\tcollector_stopped\\n"); }\n'
    return header + "\n\n".join(probes) + "\n" + footer, enabled


def parse_trace_line(line: str) -> RawEvent | None:
    line = line.rstrip("\n")
    if not line.startswith("MEMO\t"):
        return None
    fields = line.split("\t")
    if len(fields) < 12:
        return None
    if fields[2] == "status":
        return None

    def parse_int(value: str) -> int:
        try:
            return int(value)
        except ValueError:
            return 0

    return RawEvent(
        ts_ns=parse_int(fields[1]),
        event_type=fields[2],
        uid=parse_int(fields[3]),
        pid=parse_int(fields[4]),
        tid=parse_int(fields[5]),
        comm=fields[6],
        arg0=parse_int(fields[7]),
        arg1=parse_int(fields[8]),
        arg2=parse_int(fields[9]),
        arg3=parse_int(fields[10]),
        detail=fields[11],
    )


def read_raw_trace(path: Path) -> list[RawEvent]:
    events: list[RawEvent] = []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            event = parse_trace_line(line)
            if event is not None:
                events.append(event)
    return sorted(events, key=lambda event: event.ts_ns)


def load_windows(path: Path | None, events: list[RawEvent]) -> list[Window]:
    if path and path.exists():
        rows = load_json(path)
        windows: list[Window] = []
        for row in rows.get("windows", rows if isinstance(rows, list) else []):
            windows.append(
                Window(
                    window_id=int(row["window_id"]),
                    scenario_id=row.get("scenario_id", "unknown"),
                    package_name=row.get("package_name"),
                    app_alias=row.get("app_alias"),
                    action_type=row.get("action_type", "unknown"),
                    resource_phase=row.get("resource_phase", "foreground"),
                    start_ts_ns=int(row["start_ts_ns"]),
                    end_ts_ns=int(row["end_ts_ns"]),
                    host_start_ms=row.get("host_start_ms"),
                    host_end_ms=row.get("host_end_ms"),
                )
            )
        return windows

    if not events:
        return []
    start = min(event.ts_ns for event in events if event.ts_ns > 0)
    end = max(event.ts_ns for event in events)
    width = 5_000_000_000
    windows = []
    window_id = 1
    cursor = start
    while cursor <= end:
        windows.append(
            Window(
                window_id=window_id,
                scenario_id="parse_only",
                package_name=None,
                app_alias=None,
                action_type="trace_window",
                resource_phase="foreground",
                start_ts_ns=cursor,
                end_ts_ns=cursor + width - 1,
            )
        )
        cursor += width
        window_id += 1
    return windows


def find_window(event: RawEvent, windows: list[Window]) -> Window | None:
    for window in windows:
        if window.start_ts_ns <= event.ts_ns <= window.end_ts_ns:
            return window
    return None


def event_package(event: RawEvent, uid_to_packages: dict[int, list[str]]) -> str | None:
    packages = uid_to_packages.get(event.uid, [])
    if len(packages) == 1:
        return packages[0]
    return None


def app_hash(package_name: str | None, uid: int, salt: str = "memo-appflow") -> str:
    identity = package_name or f"uid:{uid}"
    digest = hashlib.sha256(f"{salt}:{identity}".encode("utf-8")).hexdigest()
    return digest[:16]


def categorize_path(path: str) -> str:
    lower = path.lower()
    if lower.endswith((".dex", ".vdex", ".oat", ".art")) or "/dalvik-cache/" in lower:
        return "dex"
    if lower.endswith(".so") or "/lib/" in lower:
        return "so"
    if lower.endswith((".apk", ".arsc")) or "/assets/" in lower or "/res/" in lower:
        return "asset"
    if lower.endswith((".db", ".sqlite", ".sqlite3")) or "/databases/" in lower:
        return "database"
    if "/cache/" in lower or "/tmp/" in lower or lower.endswith(".tmp"):
        return "cache"
    if any(token in lower for token in ("model", "weights", ".tflite", ".onnx", ".gguf", ".bin")):
        return "model"
    return "other"


def keyword_category(value: str, config: dict[str, Any]) -> str | None:
    lower = value.lower()
    for category, keywords in config.get("service_keywords", {}).items():
        if any(keyword.lower() in lower for keyword in keywords):
            return category
    return None


def service_category(event: RawEvent, package_name: str | None, config: dict[str, Any]) -> str:
    if event.event_type == "file":
        return "file"
    if event.event_type == "memory":
        return "memory"
    if event.event_type.startswith("process"):
        return "process"
    if event.event_type == "network":
        return "network"
    if event.event_type == "sched":
        return "scheduler"
    if event.event_type == "input":
        return "input"
    if event.event_type == "graphics":
        return "graphics"
    return keyword_category(" ".join([event.comm, package_name or "", event.detail]), config) or "binder"


def privacy_level(event: RawEvent) -> str:
    if event.event_type == "binder":
        return "P2"
    if event.event_type == "file":
        return "P1"
    return "P0"


def normalize_events(
    raw_events: list[RawEvent],
    windows: list[Window],
    uid_to_packages: dict[int, list[str]],
    config: dict[str, Any],
) -> list[dict[str, Any]]:
    normalized = []
    for event in raw_events:
        if event.ts_ns <= 0:
            continue
        package_name = event_package(event, uid_to_packages)
        window = find_window(event, windows)
        category = service_category(event, package_name, config)
        resource_phase = window.resource_phase if window else "idle"
        window_id = window.window_id if window else -1

        arg0 = event.arg0
        arg1 = event.arg1
        arg2 = event.arg2
        arg3 = event.arg3
        path_category = None
        if event.event_type == "binder":
            arg1 = event.arg2
            arg2 = event.arg1
        if event.event_type == "file":
            path_category = categorize_path(event.detail)
            arg0 = PATH_CATEGORY_IDS[path_category]
            arg1 = 0
            arg2 = 0
            arg3 = 0
            category = path_category

        normalized.append(
            {
                "schema_version": "memo.ebpf_event.v1",
                "ts_ns": event.ts_ns,
                "uid": event.uid,
                "pid": event.pid,
                "tid": event.tid,
                "event_type": event.event_type,
                "event_type_id": EVENT_TYPE_CODES.get(event.event_type, 0),
                "app_id_hash": app_hash(package_name, event.uid),
                "package_name": package_name,
                "comm": event.comm,
                "service_category": category,
                "window_id": window_id,
                "scenario_id": window.scenario_id if window else "unassigned",
                "resource_phase": resource_phase,
                "privacy_level": privacy_level(event),
                "arg0": arg0,
                "arg1": arg1,
                "arg2": arg2,
                "arg3": arg3,
                "path_category": path_category,
                "detail": event.detail if event.event_type != "file" else "",
            }
        )
    return normalized


def aggregate_binder_segments(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[int, str, str], list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        if event["event_type"] == "binder":
            key = (event["window_id"], event["app_id_hash"], event["service_category"])
            groups[key].append(event)

    rows = []
    for (window_id, app_id, category), members in sorted(groups.items()):
        codes = Counter(str(member["arg0"]) for member in members)
        rows.append(
            {
                "schema_version": "memo.binder_service_segment.v1",
                "segment_type": "binder_service",
                "window_id": window_id,
                "scenario_id": members[0]["scenario_id"],
                "app_id_hash": app_id,
                "service_category": category,
                "event_count": len(members),
                "first_ts_ns": min(member["ts_ns"] for member in members),
                "last_ts_ns": max(member["ts_ns"] for member in members),
                "transaction_codes": [code for code, _ in codes.most_common(8)],
                "privacy_level": "P2",
            }
        )
    return rows


def aggregate_file_profiles(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[int, str], list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        if event["event_type"] == "file" and event["resource_phase"] == "cold_launch":
            groups[(event["window_id"], event["app_id_hash"])].append(event)

    rows = []
    for (window_id, app_id), members in sorted(groups.items()):
        members = sorted(members, key=lambda member: member["ts_ns"])
        counts = Counter(member["path_category"] or "other" for member in members)
        first_sequence = []
        seen = set()
        for member in members:
            category = member["path_category"] or "other"
            if category not in seen:
                first_sequence.append({"category": category, "first_ts_ns": member["ts_ns"]})
                seen.add(category)
            if len(first_sequence) >= 20:
                break
        rows.append(
            {
                "schema_version": "memo.cold_launch_file_profile.v1",
                "segment_type": "cold_launch_file_profile",
                "window_id": window_id,
                "scenario_id": members[0]["scenario_id"],
                "app_id_hash": app_id,
                "event_count": len(members),
                "path_category_counts": dict(sorted(counts.items())),
                "first_access_sequence": first_sequence,
                "top_categories": [category for category, _ in counts.most_common(5)],
                "privacy_level": "P1",
            }
        )
    return rows


def aggregate_memory_segments(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        if event["event_type"] == "memory":
            groups[event["window_id"]].append(event)

    rows = []
    for window_id, members in sorted(groups.items()):
        direct_begin = sum(1 for member in members if member["detail"] == "direct_reclaim_begin")
        direct_end = sum(1 for member in members if member["detail"] == "direct_reclaim_end")
        kswapd = sum(1 for member in members if member["detail"] == "kswapd_wake")
        if direct_begin >= 3 or kswapd >= 2:
            level = "critical"
        elif direct_begin >= 1 or kswapd >= 1:
            level = "elevated"
        else:
            level = "normal"
        rows.append(
            {
                "schema_version": "memo.memory_pressure_segment.v1",
                "segment_type": "memory_pressure",
                "window_id": window_id,
                "scenario_id": members[0]["scenario_id"],
                "first_ts_ns": min(member["ts_ns"] for member in members),
                "last_ts_ns": max(member["ts_ns"] for member in members),
                "direct_reclaim_begin_count": direct_begin,
                "direct_reclaim_end_count": direct_end,
                "kswapd_wake_count": kswapd,
                "pressure_level": level,
                "allow_prewarm": level != "critical",
                "privacy_level": "P0",
            }
        )
    return rows


def aggregate_semantic_segments(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[int, str, str], list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        if event["window_id"] < 0:
            continue
        key = (event["window_id"], event["service_category"], event["resource_phase"])
        grouped[key].append(event)

    rows = []
    for (window_id, category, phase), members in sorted(grouped.items()):
        event_counts = Counter(member["event_type"] for member in members)
        app_counts = Counter(member["app_id_hash"] for member in members)
        network_bytes = sum(member["arg1"] for member in members if member["event_type"] == "network")
        scheduler_switches = sum(1 for member in members if member["event_type"] == "sched" and member["detail"] == "switch")
        input_events = sum(1 for member in members if member["event_type"] == "input")
        graphics_events = sum(1 for member in members if member["event_type"] == "graphics")
        rows.append(
            {
                "schema_version": "memo.semantic_evidence_segment.v1",
                "segment_type": "semantic_evidence",
                "window_id": window_id,
                "scenario_id": members[0]["scenario_id"],
                "service_category": category,
                "resource_phase": phase,
                "first_ts_ns": min(member["ts_ns"] for member in members),
                "last_ts_ns": max(member["ts_ns"] for member in members),
                "event_counts": dict(sorted(event_counts.items())),
                "dominant_app_actors": [
                    {"app_id_hash": app_id, "event_count": count}
                    for app_id, count in app_counts.most_common(5)
                ],
                "network_bytes_bucket": bucket_bytes(network_bytes),
                "scheduler_switches": scheduler_switches,
                "input_event_count": input_events,
                "graphics_event_count": graphics_events,
                "privacy_level": segment_privacy_level(members),
            }
        )
    return rows


def bucket_bytes(value: int) -> str:
    if value <= 0:
        return "none"
    if value < 4 * 1024:
        return "tiny"
    if value < 64 * 1024:
        return "small"
    if value < 1024 * 1024:
        return "medium"
    return "large"


def segment_privacy_level(events: list[dict[str, Any]]) -> str:
    levels = {event["privacy_level"] for event in events}
    if "P2" in levels:
        return "P2"
    if "P1" in levels:
        return "P1"
    return "P0"


def build_llm_contexts(
    events: list[dict[str, Any]],
    binder_segments: list[dict[str, Any]],
    file_profiles: list[dict[str, Any]],
    memory_segments: list[dict[str, Any]],
    semantic_segments: list[dict[str, Any]],
    windows: list[Window],
) -> list[dict[str, Any]]:
    by_window_events: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        by_window_events[event["window_id"]].append(event)
    binder_by_window = defaultdict(list)
    for row in binder_segments:
        binder_by_window[row["window_id"]].append(row)
    files_by_window = defaultdict(list)
    for row in file_profiles:
        files_by_window[row["window_id"]].append(row)
    memory_by_window = {row["window_id"]: row for row in memory_segments}
    semantic_by_window = defaultdict(list)
    for row in semantic_segments:
        semantic_by_window[row["window_id"]].append(row)
    windows_by_id = {window.window_id: window for window in windows}

    contexts = []
    for window_id, members in sorted(by_window_events.items()):
        if window_id < 0:
            continue
        window = windows_by_id.get(window_id)
        memory = memory_by_window.get(
            window_id,
            {
                "pressure_level": "normal",
                "allow_prewarm": True,
                "direct_reclaim_begin_count": 0,
                "kswapd_wake_count": 0,
            },
        )
        service_counts = Counter(member["service_category"] for member in members)
        app_ids = []
        seen = set()
        for member in sorted(members, key=lambda item: item["ts_ns"]):
            app_id = member["app_id_hash"]
            if app_id not in seen:
                seen.add(app_id)
                app_ids.append(app_id)

        context_lines = [
            "This evidence is collected from eBPF system events, not from a Markov app sequence.",
            f"Current anonymized app actors are {', '.join(app_ids[:5]) or 'unknown'}.",
            "Observed service categories: "
            + ", ".join(f"{category}={count}" for category, count in service_counts.most_common(8))
            + ".",
            f"Memory pressure is {memory['pressure_level']}.",
            "Prewarm is allowed." if memory["allow_prewarm"] else "Prewarm is blocked by critical memory pressure.",
        ]
        for profile in files_by_window[window_id]:
            counts = profile["path_category_counts"]
            context_lines.append(
                "Cold-launch file categories: "
                + ", ".join(f"{category}={count}" for category, count in counts.items())
                + "."
            )

        contexts.append(
            {
                "schema_version": "memo.llm_context.v1",
                "window_id": window_id,
                "scenario_id": window.scenario_id if window else members[0]["scenario_id"],
                "resource_phase": window.resource_phase if window else members[0]["resource_phase"],
                "current_app_id_hash": app_hash(window.package_name, 0) if window and window.package_name else app_ids[0],
                "current_app_package": None,
                "prediction_contract": {
                    "stage_1": {
                        "name": "ATP",
                        "question": "What type of application will the user open next?",
                        "output_format": "Communication app (75%), Navigation app (15%), Social app (10%)",
                    },
                    "stage_2": {
                        "name": "NTP",
                        "question": "Which specific app ID will the user open next?",
                        "output_format": "This user will use App <id>.",
                    },
                },
                "context": {
                    "recent_app_ids": app_ids[:8],
                    "service_segments": binder_by_window[window_id],
                    "cold_launch_file_profiles": files_by_window[window_id],
                    "memory_pressure": memory,
                    "semantic_evidence_segments": semantic_by_window[window_id],
                    "resource_decision_constraints": {
                        "allow_prewarm": memory["allow_prewarm"],
                        "blocked_actions": ["prewarm", "background_file_scan"] if not memory["allow_prewarm"] else [],
                    },
                },
                "prompt_lines": context_lines,
            }
        )
    return contexts


def parse_and_structure(
    raw_trace: Path,
    out_dir: Path,
    *,
    actions_json: Path | None,
    uid_map_json: Path | None,
    config_path: Path,
    tracepoints: list[str] | None = None,
) -> dict[str, Any]:
    config = load_json(config_path)
    raw_events = read_raw_trace(raw_trace)
    windows = load_windows(actions_json, raw_events)
    uid_to_packages = {}
    if uid_map_json and uid_map_json.exists():
        loaded = load_json(uid_map_json)
        uid_to_packages = {int(uid): packages for uid, packages in loaded.items()}

    normalized = normalize_events(raw_events, windows, uid_to_packages, config)
    binder_segments = aggregate_binder_segments(normalized)
    file_profiles = aggregate_file_profiles(normalized)
    memory_segments = aggregate_memory_segments(normalized)
    semantic_segments = aggregate_semantic_segments(normalized)
    llm_contexts = build_llm_contexts(
        normalized,
        binder_segments,
        file_profiles,
        memory_segments,
        semantic_segments,
        windows,
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    output_files = {
        "normalized_events": out_dir / "normalized_events.jsonl",
        "binder_service_segments": out_dir / "binder_service_segments.jsonl",
        "cold_launch_file_profile": out_dir / "cold_launch_file_profile.jsonl",
        "memory_pressure_segments": out_dir / "memory_pressure_segments.jsonl",
        "semantic_evidence_segments": out_dir / "semantic_evidence_segments.jsonl",
        "llm_context_windows": out_dir / "llm_context_windows.jsonl",
    }
    counts = {
        "normalized_events": write_jsonl(output_files["normalized_events"], normalized),
        "binder_service_segments": write_jsonl(output_files["binder_service_segments"], binder_segments),
        "cold_launch_file_profile": write_jsonl(output_files["cold_launch_file_profile"], file_profiles),
        "memory_pressure_segments": write_jsonl(output_files["memory_pressure_segments"], memory_segments),
        "semantic_evidence_segments": write_jsonl(output_files["semantic_evidence_segments"], semantic_segments),
        "llm_context_windows": write_jsonl(output_files["llm_context_windows"], llm_contexts),
    }
    manifest = {
        "schema_version": "memo.ebpf_run_manifest.v1",
        "generated_at": now_iso(),
        "raw_trace": str(raw_trace),
        "actions_json": str(actions_json) if actions_json else None,
        "config": str(config_path),
        "tracepoints": tracepoints or [],
        "counts": counts,
        "outputs": {key: str(value) for key, value in output_files.items()},
    }
    write_json(out_dir / "run_manifest.json", manifest)
    return manifest


def resolve_package_aliases(adb: str, workload: dict[str, Any]) -> dict[str, str]:
    installed = adb_shell(adb, "pm list packages", timeout=30).stdout
    installed_set = {line.removeprefix("package:").strip() for line in installed.splitlines() if line.startswith("package:")}
    resolved = {}
    for alias, packages in workload.get("package_aliases", {}).items():
        for package_name in packages:
            if package_name in installed_set:
                resolved[alias] = package_name
                break
    return resolved


def adb_start_package(adb: str, package_name: str) -> None:
    adb_shell(adb, f"monkey -p {shlex.quote(package_name)} -c android.intent.category.LAUNCHER 1", timeout=20)


def drive_workload(
    adb: str,
    workload_path: Path,
    scenario_id: str,
    *,
    cold_launch_repeats: int,
    step_seconds: float,
    drop_caches: bool,
    su: bool,
) -> list[Window]:
    workload = load_json(workload_path)
    scenarios = {scenario["id"]: scenario for scenario in workload.get("scenarios", [])}
    scenario = scenarios.get(scenario_id)
    if not scenario:
        raise ValueError(f"unknown scenario {scenario_id}; available: {', '.join(sorted(scenarios))}")
    resolved = resolve_package_aliases(adb, workload)
    windows: list[Window] = []
    window_id = 1

    for repeat in range(cold_launch_repeats):
        for step in scenario.get("steps", []):
            alias = step["alias"]
            package_name = resolved.get(alias)
            if not package_name:
                continue
            adb_shell(adb, f"am force-stop {shlex.quote(package_name)}", timeout=15)
            if drop_caches:
                adb_shell(adb, "sync; echo 3 > /proc/sys/vm/drop_caches", su=su, timeout=15)
            host_start_ms = int(time.time() * 1000)
            start_ns = device_uptime_ns(adb)
            adb_start_package(adb, package_name)
            time.sleep(step_seconds)
            for gesture in step.get("gestures", []):
                adb_shell(adb, gesture, timeout=10)
                time.sleep(0.5)
            end_ns = device_uptime_ns(adb)
            host_end_ms = int(time.time() * 1000)
            windows.append(
                Window(
                    window_id=window_id,
                    scenario_id=scenario_id,
                    package_name=package_name,
                    app_alias=alias,
                    action_type=f"{step.get('action', 'launch')}:repeat_{repeat + 1}",
                    resource_phase=step.get("resource_phase", "cold_launch"),
                    start_ts_ns=start_ns,
                    end_ts_ns=end_ns,
                    host_start_ms=host_start_ms,
                    host_end_ms=host_end_ms,
                )
            )
            window_id += 1
            adb_shell(adb, "input keyevent KEYCODE_HOME", timeout=10)
            time.sleep(1.0)
    return windows


def collect(args: argparse.Namespace) -> CollectResult:
    out_dir = args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    tracepoints = available_tracepoints(args.adb, su=args.su)
    syscall_abi = args.syscall_abi or device_arch(args.adb)
    program, enabled = build_bpftrace_program(tracepoints, syscall_abi=syscall_abi)
    if not enabled:
        raise RuntimeError("no supported tracepoints found; run the probe command first")

    local_program = out_dir / "memo_appflow_trace.bt"
    local_program.write_text(program, encoding="utf-8")
    run([args.adb, "push", str(local_program), TRACE_REMOTE], timeout=30, check=False)

    command = f"{args.bpftrace} -q {TRACE_REMOTE}"
    popen_args = [args.adb, "shell", "su", "-c", command] if args.su else [args.adb, "shell", command]
    raw_trace = out_dir / "raw_trace.tsv"
    uid_map_path = out_dir / "uid_map.json"
    action_path = out_dir / "action_timeline.json"
    write_json(uid_map_path, {str(uid): packages for uid, packages in package_uid_map(args.adb).items()})

    process = subprocess.Popen(
        popen_args,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    stop_reader = threading.Event()
    collector_ready = threading.Event()

    def reader() -> None:
        with raw_trace.open("w", encoding="utf-8") as handle:
            assert process.stdout is not None
            while not stop_reader.is_set():
                line = process.stdout.readline()
                if not line:
                    if process.poll() is not None:
                        break
                    time.sleep(0.05)
                    continue
                handle.write(line)
                handle.flush()
                if line.startswith("MEMO\t0\tstatus") and "collector_started" in line:
                    collector_ready.set()

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    if not collector_ready.wait(timeout=args.collector_ready_timeout):
        process.terminate()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
        stop_reader.set()
        thread.join(timeout=5)
        raise RuntimeError(
            "bpftrace did not emit collector_started before timeout; "
            f"inspect {raw_trace} for compiler or attach errors"
        )
    time.sleep(args.warmup_seconds)
    windows: list[Window] = []
    try:
        if not args.skip_workload:
            windows = drive_workload(
                args.adb,
                args.workload,
                args.scenario,
                cold_launch_repeats=args.cold_launch_repeats,
                step_seconds=args.step_seconds,
                drop_caches=args.drop_caches,
                su=args.su,
            )
        if args.extra_seconds > 0:
            time.sleep(args.extra_seconds)
    finally:
        process.terminate()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
        stop_reader.set()
        thread.join(timeout=5)

    write_json(
        action_path,
        {
            "schema_version": "memo.action_timeline.v1",
            "generated_at": now_iso(),
            "windows": [window.__dict__ for window in windows],
        },
    )
    manifest = parse_and_structure(
        raw_trace,
        out_dir,
        actions_json=action_path,
        uid_map_json=uid_map_path,
        config_path=args.config,
        tracepoints=enabled,
    )
    manifest["collection"] = {
        "mode": "bpftrace",
        "bpftrace": args.bpftrace,
        "bpftool": args.bpftool,
        "collector_ready_timeout": args.collector_ready_timeout,
        "workload": str(args.workload),
        "scenario": args.scenario,
        "cold_launch_repeats": args.cold_launch_repeats,
        "step_seconds": args.step_seconds,
        "drop_caches": args.drop_caches,
        "skip_workload": args.skip_workload,
        "syscall_abi": syscall_abi,
    }
    manifest["android_bpf_inventory"] = bpf_inventory(args.adb, su=args.su, bpftool=args.bpftool)
    write_json(out_dir / "run_manifest.json", manifest)
    return CollectResult(raw_trace=raw_trace, action_timeline=action_path, tracepoints=enabled)


def cmd_probe(args: argparse.Namespace) -> int:
    result = probe(args.adb, su=args.su, out=args.out, bpftool=args.bpftool)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def cmd_emit_bpftrace(args: argparse.Namespace) -> int:
    if args.tracepoints:
        tracepoints = [line.strip() for line in args.tracepoints.read_text(encoding="utf-8").splitlines() if line.strip()]
    else:
        tracepoints = [
            "binder:binder_transaction",
            "syscalls:sys_enter_openat",
            "syscalls:sys_enter_openat2",
            "vmscan:mm_vmscan_direct_reclaim_begin",
            "vmscan:mm_vmscan_direct_reclaim_end",
            "vmscan:mm_vmscan_kswapd_wake",
            "sched:sched_process_fork",
            "sched:sched_process_exit",
        ]
    program, enabled = build_bpftrace_program(tracepoints, syscall_abi=args.syscall_abi)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(program, encoding="utf-8")
    else:
        print(program)
    print("enabled:", ", ".join(enabled), file=sys.stderr)
    return 0


def cmd_parse(args: argparse.Namespace) -> int:
    manifest = parse_and_structure(
        args.raw_trace,
        args.out,
        actions_json=args.actions_json,
        uid_map_json=args.uid_map_json,
        config_path=args.config,
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


def cmd_collect(args: argparse.Namespace) -> int:
    result = collect(args)
    print(f"raw trace: {result.raw_trace}")
    if result.action_timeline:
        print(f"action timeline: {result.action_timeline}")
    print("tracepoints:", ", ".join(result.tracepoints))
    return 0


def cmd_install_tools(args: argparse.Namespace) -> int:
    result = install_tools(args)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Collect and structure Android eBPF traces for MEMO-Appflow.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    probe_parser = subparsers.add_parser("probe", help="Probe target tracepoints and BPF support.")
    probe_parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    probe_parser.add_argument("--su", action=argparse.BooleanOptionalAction, default=True)
    probe_parser.add_argument("--bpftool", default=None)
    probe_parser.add_argument("--out", type=Path)
    probe_parser.set_defaults(func=cmd_probe)

    install_parser = subparsers.add_parser("install-tools", help="Install official bpftrace/bpftool release binaries on the Android target.")
    install_parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    install_parser.add_argument("--su", action=argparse.BooleanOptionalAction, default=False)
    install_parser.add_argument("--remote-dir", default=DEFAULT_REMOTE_TOOL_DIR)
    install_parser.add_argument("--cache-dir", type=Path, default=Path("dataset_cache") / "tools")
    install_parser.add_argument("--bpftrace-url", default=DEFAULT_BPFTRACE_URL)
    install_parser.add_argument("--bpftool-url", default=DEFAULT_BPFTOOL_URL)
    install_parser.add_argument("--out", type=Path)
    install_parser.set_defaults(func=cmd_install_tools)

    emit_parser = subparsers.add_parser("emit-bpftrace", help="Emit a bpftrace program for available tracepoints.")
    emit_parser.add_argument("--tracepoints", type=Path)
    emit_parser.add_argument("--out", type=Path)
    emit_parser.add_argument("--syscall-abi", default="x86_64", choices=["x86_64", "aarch64"])
    emit_parser.set_defaults(func=cmd_emit_bpftrace)

    parse_parser = subparsers.add_parser("parse", help="Parse a saved raw trace into structured JSONL outputs.")
    parse_parser.add_argument("--raw-trace", type=Path, required=True)
    parse_parser.add_argument("--out", type=Path, required=True)
    parse_parser.add_argument("--actions-json", type=Path)
    parse_parser.add_argument("--uid-map-json", type=Path)
    parse_parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parse_parser.set_defaults(func=cmd_parse)

    collect_parser = subparsers.add_parser("collect", help="Run bpftrace through adb, drive workload, and structure outputs.")
    collect_parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    collect_parser.add_argument("--su", action=argparse.BooleanOptionalAction, default=True)
    collect_parser.add_argument("--bpftrace", default="bpftrace")
    collect_parser.add_argument("--bpftool", default=None)
    collect_parser.add_argument("--syscall-abi", choices=["x86_64", "aarch64"])
    collect_parser.add_argument("--out", type=Path, required=True)
    collect_parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    collect_parser.add_argument("--workload", type=Path, default=DEFAULT_WORKLOAD)
    collect_parser.add_argument("--scenario", default="mixed_daily")
    collect_parser.add_argument("--cold-launch-repeats", type=int, default=5)
    collect_parser.add_argument("--step-seconds", type=float, default=3.0)
    collect_parser.add_argument("--warmup-seconds", type=float, default=1.0)
    collect_parser.add_argument("--collector-ready-timeout", type=float, default=45.0)
    collect_parser.add_argument("--extra-seconds", type=float, default=2.0)
    collect_parser.add_argument("--drop-caches", action=argparse.BooleanOptionalAction, default=True)
    collect_parser.add_argument("--skip-workload", action="store_true")
    collect_parser.set_defaults(func=cmd_collect)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
