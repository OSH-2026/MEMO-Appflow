#!/usr/bin/env python3
"""Convert MEMO Android eBPF evidence into MAPLE scenario JSON.

The MAPLE engine supplied in llm/maple expects a compact user context.  This
adapter keeps the MAPLE executable as the model entry point, while feeding it
low-level eBPF facts instead of an app-sequence-only baseline.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any


CATEGORY_LABELS = {
    "native_library": "Native Runtime Loading",
    "java_framework_or_classpath": "Framework Loading",
    "android_property_area": "System Property Access",
    "apex_runtime_asset": "APEX Runtime Loading",
    "sysfs_kernel_state": "Kernel Trace Setup",
    "procfs_process_state": "Process State Inspection",
    "device_or_ipc_node": "Device/IPC Node Access",
    "database": "Database",
    "dex_or_oat": "Dex/OAT Loading",
    "cache": "Cache/File Cache",
    "config": "Config File Access",
    "other": "Other File Access",
    "binder_ipc": "Android Service IPC",
    "memory_reclaim": "Memory Management",
}

CATEGORY_IDS = {
    "Android Service IPC": 110,
    "Display Composition": 115,
    "Native Runtime Loading": 120,
    "Framework Loading": 130,
    "System Property Access": 140,
    "APEX Runtime Loading": 150,
    "Kernel Trace Setup": 160,
    "Process State Inspection": 170,
    "Database": 180,
    "Dex/OAT Loading": 190,
    "Cache/File Cache": 200,
    "Config File Access": 210,
    "Memory Management": 220,
    "Other File Access": 230,
    "Device/IPC Node Access": 235,
    "Input Interaction": 240,
    "Camera Service": 250,
    "Media Codec": 260,
    "Navigation/Location": 270,
    "App Process Runtime": 280,
    "Android System Services": 290,
}

PROCESS_HINTS = {
    "system_server": "system service coordination",
    "surfaceflinger": "display compositor activity",
    "cameraserver": "camera service activity",
    "audioserver": "audio service activity",
    "netd": "network daemon activity",
    "servicemanager": "Binder service lookup",
    "hwservicemanage": "hardware service lookup",
    "zygote": "app process startup path",
}

PROCESS_CATEGORY_RULES = [
    ("Display Composition", ("surfaceflinger", "renderthread", "hwc", "gralloc", "android.display", "gl-map")),
    ("Memory Management", ("lmkd", "kswapd", "oomadjuster")),
    ("Input Interaction", ("input", "inputdispatcher")),
    ("Camera Service", ("camera", "cameraserver")),
    ("Media Codec", ("mediacodec", "mediaswcodec", "c2@", "codec", "sounddecoder")),
    ("Navigation/Location", ("maps", "location")),
    ("App Process Runtime", ("app_process", "zygote")),
    ("Android System Services", ("system_server", "servicemanager", "hwservicemanage", "activitymanager")),
]

NOISE_PROCESSES = {
    "cat",
    "grep",
    "head",
    "main",
    "sh",
    "su",
    "tail",
    "toybox",
}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def event_category(event: dict[str, Any]) -> str:
    event_type = str(event.get("event_type", ""))
    if event_type in {"MEMO_BINDER", "binder"}:
        return "Android Service IPC"
    if event_type in {"MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "memory"}:
        return "Memory Management"
    raw_category = event.get("evidence_category") or event.get("path_category") or event.get("service_category")
    return CATEGORY_LABELS.get(str(raw_category), CATEGORY_LABELS.get(str(raw_category).lower(), "Other File Access"))


def process_name(event: dict[str, Any]) -> str:
    return str(event.get("comm") or event.get("trace_task") or event.get("process") or "unknown")


def top_items(counter: Counter[str], limit: int) -> list[tuple[str, int]]:
    return [(name, count) for name, count in counter.most_common(limit) if name]


def signal_process_counts(events: list[dict[str, Any]]) -> Counter[str]:
    counts = Counter(process_name(event) for event in events)
    return Counter(
        {
            name: count
            for name, count in counts.items()
            if name not in NOISE_PROCESSES and not name.startswith("binder:")
        }
    )


def process_category(name: str) -> str | None:
    lowered = name.lower()
    for category, needles in PROCESS_CATEGORY_RULES:
        if any(needle in lowered for needle in needles):
            return category
    return None


def process_category_counts(events: list[dict[str, Any]]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for name, count in signal_process_counts(events).items():
        category = process_category(name)
        if category:
            counts[category] += count
    return counts


def prediction_time(events: list[dict[str, Any]]) -> str:
    for event in events:
        value = event.get("wall_time") or event.get("timestamp")
        if isinstance(value, str) and value:
            return value
    return datetime.now().strftime("%A %I:%M%p")


def build_summary(events: list[dict[str, Any]], *, max_evidence: int = 10) -> dict[str, Any]:
    event_counts = Counter(str(event.get("event_type", "unknown")) for event in events)
    category_counts = Counter(event_category(event) for event in events)
    category_counts.update(process_category_counts(events))
    process_counts = Counter(process_name(event) for event in events)
    signal_processes = signal_process_counts(events)
    path_categories = Counter(str(event.get("evidence_category") or event.get("path_category") or "") for event in events)
    reclaim_count = sum(
        1
        for event in events
        if str(event.get("event_type", "")) in {"MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "memory"}
    )

    evidence_lines = [
        f"{len(events)} eBPF records in the observed Android emulator window",
    ]
    for name, count in top_items(event_counts, 4):
        evidence_lines.append(f"event_type {name}: count={count}")
    for name, count in top_items(category_counts, 6):
        evidence_lines.append(f"MAPLE evidence/resource category {name}: count={count}")
    for name, count in top_items(signal_processes, 5):
        evidence_lines.append(f"process {name}: count={count}")

    memory_pressure = "normal: no direct reclaim tracepoint was observed"
    if reclaim_count:
        memory_pressure = f"elevated: direct reclaim related events observed count={reclaim_count}"

    return {
        "event_counts": dict(sorted(event_counts.items())),
        "category_counts": dict(sorted(category_counts.items())),
        "process_counts": dict(sorted(process_counts.items())),
        "path_category_counts": dict(sorted((k, v) for k, v in path_categories.items() if k)),
        "memory_pressure": memory_pressure,
        "system_evidence": evidence_lines[:max_evidence],
    }


def build_maple_scenario(events: list[dict[str, Any]], *, scenario_id: str, description: str) -> dict[str, Any]:
    if not events:
        raise ValueError("No eBPF events were provided.")

    summary = build_summary(events)
    category_counts = Counter(summary["category_counts"])
    process_counts = signal_process_counts(events)

    categories = [name for name, _ in top_items(category_counts, 5)]
    if not categories:
        categories = ["Android Service IPC"]

    installed_apps = {
        category: [CATEGORY_IDS.get(category, 999)]
        for category in categories
    }
    historical_ids = [ids[0] for ids in installed_apps.values()]

    points = []
    for name, _ in top_items(process_counts, 5):
        points.append(PROCESS_HINTS.get(name, f"{name} process activity"))

    context = {
        "historical_app_categories": categories,
        "historical_app_ids": historical_ids,
        "prediction_time": prediction_time(events),
        "points_of_interest": points[:5],
        "installed_apps": installed_apps,
        "system_evidence": summary["system_evidence"],
        "memory_pressure": summary["memory_pressure"],
        "scheduler_goal": (
            "Bridge MEMO eBPF evidence into MAPLE. Infer near-future resource demand and memory scheduling; "
            "treat app sequence statistics only as a weak baseline."
        ),
    }

    return {
        "id": scenario_id,
        "description": description,
        "source": "android_ebpf",
        "context": context,
        "ebpf_summary": summary,
    }


def write_scenarios(path: Path, scenario: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": "memo.maple_scenarios.v1",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "scenarios": [scenario],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Convert eBPF JSONL evidence into MAPLE scenario JSON.")
    parser.add_argument("--events", type=Path, required=True, help="eBPF JSONL event file.")
    parser.add_argument("--out", type=Path, required=True, help="Output MAPLE scenarios JSON.")
    parser.add_argument("--scenario-id", default="ebpf_observed_window")
    parser.add_argument(
        "--description",
        default="Android emulator workload summarized from eBPF tracepoints",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    events = load_jsonl(args.events)
    scenario = build_maple_scenario(events, scenario_id=args.scenario_id, description=args.description)
    write_scenarios(args.out, scenario)
    print(json.dumps({"events": len(events), "out": str(args.out)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
