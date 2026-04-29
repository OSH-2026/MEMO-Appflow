#!/usr/bin/env python3
"""Convert MEMO bpf_trace_printk lines into structured JSONL evidence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable


LINE_RE = re.compile(
    r"^\s*(?P<task>.+)-(?P<tid>\d+)\s+"
    r"\[(?P<cpu>\d+)\].*?\s+"
    r"(?P<ts>\d+\.\d+): bpf_trace_printk: "
    r"(?P<msg>MEMO_\w+)\s*(?P<rest>.*)$"
)
KV_RE = re.compile(r"(\w+)=([^=]+?)(?=\s+\w+=|$)")


def evidence_category(path: str | None) -> str | None:
    if not path:
        return None
    if path.endswith(".jar") or "/framework/" in path:
        return "java_framework_or_classpath"
    if path.endswith(".so") or "/lib64/" in path:
        return "native_library"
    if "/__properties__/" in path:
        return "android_property_area"
    if path.startswith("/proc/"):
        return "procfs_process_state"
    if path.startswith("/sys/"):
        return "sysfs_kernel_state"
    if path.startswith("/apex/"):
        return "apex_runtime_asset"
    if path.startswith("/dev/"):
        return "device_or_ipc_node"
    return "other"


def read_lines(path: Path, encoding: str | None) -> Iterable[str]:
    encodings = [encoding] if encoding else ["utf-8", "utf-16"]
    last_error: UnicodeError | None = None
    for candidate in encodings:
        try:
            with path.open("r", encoding=candidate) as handle:
                yield from handle
            return
        except UnicodeError as exc:
            last_error = exc
    if last_error:
        raise last_error


def parse_line(line: str) -> dict[str, object] | None:
    match = LINE_RE.match(line)
    if not match:
        return None

    rest = {key: value.strip() for key, value in KV_RE.findall(match.group("rest"))}
    record: dict[str, object] = {
        "schema_version": "memo.ebpf.traceprint.v1",
        "timestamp_s": float(match.group("ts")),
        "cpu": int(match.group("cpu")),
        "trace_task": match.group("task").strip(),
        "trace_tid": int(match.group("tid")),
        "event_type": match.group("msg"),
    }

    for key, value in rest.items():
        if key in {"pid", "code", "to_proc"}:
            try:
                record[key] = int(value)
            except ValueError:
                record[key] = value
        else:
            record[key] = value

    if "path" in record:
        record["evidence_category"] = evidence_category(str(record["path"]))
    return record


def convert(input_path: Path, output_path: Path, encoding: str | None = None) -> int:
    count = 0
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as output:
        for line in read_lines(input_path, encoding):
            record = parse_line(line)
            if not record:
                continue
            output.write(json.dumps(record, ensure_ascii=False) + "\n")
            count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--encoding", choices=["utf-8", "utf-16"])
    args = parser.parse_args()

    count = convert(args.trace, args.out, args.encoding)
    print(json.dumps({"trace": str(args.trace), "out": str(args.out), "records": count}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
