#!/usr/bin/env python3
"""Ablation experiment runner for MEMO-Appflow.

Loads synthetic samples with ground truth, applies 6 ablation configurations,
calls LLM inference (DeepSeek API or local MAPLE engine), and compares
predictions with labels.

Usage:
  python ablation_runner.py \
    --api-key sk-xxx \
    --dataset dataset_synthetic \
    --output docs/ablation_results.md
"""

from __future__ import annotations

import argparse
import asyncio
import ctypes
import json
import random
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiohttp

ROOT = Path(__file__).resolve().parents[1]
_PROJECT_ROOT = ROOT.parent
_MAPLE_BUILD = _PROJECT_ROOT / "llm" / "maple" / "maple_engine" / "build"
DEEPSEEK_BASE = "https://api.deepseek.com"
DEEPSEEK_MODEL = "deepseek-chat"


# ────────────────────────────────────────────────────────────
# Local MAPLE engine via ctypes
# ────────────────────────────────────────────────────────────

def _find_maple_lib() -> str:
    lib_path = _MAPLE_BUILD / "libmaple_engine.so"
    if lib_path.exists():
        return str(lib_path)
    import glob as _g
    for m in _g.glob(str(_MAPLE_BUILD / "libmaple_engine*.so")):
        return m
    raise FileNotFoundError(f"libmaple_engine.so not found at {_MAPLE_BUILD}")


class MaplePromptBuilder:
    """Lightweight wrapper: builds MAPLE prompts via C API, no model needed."""

    def __init__(self):
        lib_path = _find_maple_lib()
        self._lib = ctypes.cdll.LoadLibrary(lib_path)
        self._lib.maple_build_prompt_standalone.argtypes = [
            ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t,
        ]
        self._lib.maple_build_prompt_standalone.restype = ctypes.c_int

    def build_prompt(self, context_json: str) -> str:
        buf = ctypes.create_string_buffer(8192)
        ctx_bytes = context_json.encode("utf-8")
        ret = self._lib.maple_build_prompt_standalone(ctx_bytes, buf, 8192)
        if ret != 0:
            raise RuntimeError(f"maple_build_prompt_standalone failed: {ret}")
        return buf.value.decode("utf-8")


class MapleEngine(MaplePromptBuilder):
    """Python ctypes wrapper around MAPLE C API (full engine with model)."""

    def __init__(self, model_path: str, n_threads: int = 8):
        super().__init__()  # loads library + prompt builder

        self._lib.maple_engine_create.argtypes = [
            ctypes.c_char_p, ctypes.c_int, ctypes.c_int,
            ctypes.c_float, ctypes.c_int,
        ]
        self._lib.maple_engine_create.restype = ctypes.c_void_p

        self._lib.maple_engine_destroy.argtypes = [ctypes.c_void_p]
        self._lib.maple_engine_destroy.restype = None

        self._lib.maple_engine_set_flags.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        self._lib.maple_engine_set_flags.restype = None

        self._lib.maple_predict_app_type.argtypes = [
            ctypes.c_void_p, ctypes.c_char_p,
            ctypes.c_char_p, ctypes.c_size_t,
        ]
        self._lib.maple_predict_app_type.restype = ctypes.c_int

        model_bytes = model_path.encode("utf-8")
        self._engine = self._lib.maple_engine_create(
            model_bytes, 2048, n_threads, ctypes.c_float(0.3), 64,
        )
        if not self._engine:
            raise RuntimeError(f"Failed to create MAPLE engine with model: {model_path}")
        self._lib.maple_engine_set_flags(self._engine, 0x3F)

    def close(self):
        if self._engine:
            self._lib.maple_engine_destroy(self._engine)
            self._engine = None

    def predict_app_type(self, context_json: str) -> tuple[str, str]:
        """Returns (predicted_category, raw_output)."""
        buf = ctypes.create_string_buffer(4096)
        ctx_bytes = context_json.encode("utf-8")
        ret = self._lib.maple_predict_app_type(self._engine, ctx_bytes, buf, 4096)
        if ret != 0:
            raise RuntimeError(f"maple_predict_app_type failed: {ret}")
        result = json.loads(buf.value.decode("utf-8"))
        top = result.get("top_categories", [])
        predicted = top[0]["name"] if top else ""
        raw = result.get("raw_output", "")
        return predicted, raw


def _parse_app_type_py(raw: str) -> str:
    """Python reimplementation of ResultParser::parse_app_type for DeepSeek path.
    Mirrors the C++ logic so both paths use the same parsing."""
    import re
    # Pattern 1: "Name (number)" or "Name (number%)" or "Name (number records)"
    m = re.search(r'\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,4})\s*\(\s*(\d+)\s*(?:%|records)?\s*\)', raw)
    if m:
        name = m.group(1).strip()
        if name not in ('MEMO_BINDER', 'MEMO_OPENAT', 'MEMO_RECLAIM_BEGIN', 'App'):
            return name

    # Pattern 2: bare known category name
    for cat in ALL_CATEGORIES:
        if cat.lower() in raw.lower():
            return cat

    # Pattern 3: first line as fallback
    line = raw.strip().split('\n')[0].strip().strip('"').strip("'")
    for cat in ALL_CATEGORIES:
        if cat.lower() in line.lower():
            return cat

    return line

# ────────────────────────────────────────────────────────────
# Category definitions (must match data)
# ────────────────────────────────────────────────────────────

ALL_CATEGORIES = [
    "Android Service IPC", "Display Composition", "Native Runtime Loading",
    "Framework Loading", "System Property Access", "APEX Runtime Loading",
    "Kernel Trace Setup", "Process State Inspection", "Database",
    "Dex/OAT Loading", "Cache/File Cache", "Config File Access",
    "Memory Management", "Other File Access", "Device/IPC Node Access",
    "Input Interaction", "Camera Service", "Media Codec",
    "Navigation/Location", "App Process Runtime", "Android System Services",
]

# ────────────────────────────────────────────────────────────
# Ablation configurations
# ────────────────────────────────────────────────────────────

ABLATION_CONFIGS = {
    "A": {
        "label": "全量 eBPF 证据",
        "keep_system_evidence": True,
        "keep_points_of_interest": True,
        "keep_historical": True,
        "keep_memory_pressure": True,
        "keep_prediction_time": True,
    },
    "B": {
        "label": "仅 Binder 证据",
        "keep_system_evidence": "binder_only",
        "keep_points_of_interest": True,
        "keep_historical": False,
        "keep_memory_pressure": False,
        "keep_prediction_time": False,
    },
    "C": {
        "label": "仅文件访问证据",
        "keep_system_evidence": "file_only",
        "keep_points_of_interest": False,
        "keep_historical": False,
        "keep_memory_pressure": False,
        "keep_prediction_time": False,
    },
    "D": {
        "label": "仅内存压力证据",
        "keep_system_evidence": False,
        "keep_points_of_interest": False,
        "keep_historical": False,
        "keep_memory_pressure": True,
        "keep_prediction_time": False,
    },
    "E": {
        "label": "仅 app 序列 (baseline)",
        "keep_system_evidence": False,
        "keep_points_of_interest": False,
        "keep_historical": True,
        "keep_memory_pressure": False,
        "keep_prediction_time": True,
    },
    "F": {
        "label": "随机 baseline",
        "keep_system_evidence": False,
        "keep_points_of_interest": False,
        "keep_historical": False,
        "keep_memory_pressure": False,
        "keep_prediction_time": False,
    },
    "EB": {
        "label": "app序列 + Binder",
        "keep_system_evidence": "binder_only",
        "keep_points_of_interest": True,
        "keep_historical": True,
        "keep_memory_pressure": False,
        "keep_prediction_time": True,
    },
    "EC": {
        "label": "app序列 + 文件访问",
        "keep_system_evidence": "file_only",
        "keep_points_of_interest": False,
        "keep_historical": True,
        "keep_memory_pressure": False,
        "keep_prediction_time": True,
    },
    "ED": {
        "label": "app序列 + 内存压力",
        "keep_system_evidence": False,
        "keep_points_of_interest": False,
        "keep_historical": True,
        "keep_memory_pressure": True,
        "keep_prediction_time": True,
    },
    "BC": {
        "label": "Binder + 文件访问",
        "keep_system_evidence": True,
        "keep_points_of_interest": True,
        "keep_historical": False,
        "keep_memory_pressure": False,
        "keep_prediction_time": False,
    },
    "BD": {
        "label": "Binder + 内存压力",
        "keep_system_evidence": "binder_only",
        "keep_points_of_interest": True,
        "keep_historical": False,
        "keep_memory_pressure": True,
        "keep_prediction_time": False,
    },
    "CD": {
        "label": "文件访问 + 内存压力",
        "keep_system_evidence": "file_only",
        "keep_points_of_interest": False,
        "keep_historical": False,
        "keep_memory_pressure": True,
        "keep_prediction_time": False,
    },
    "EBC": {
        "label": "app序列 + Binder + 文件",
        "keep_system_evidence": True,
        "keep_points_of_interest": True,
        "keep_historical": True,
        "keep_memory_pressure": False,
        "keep_prediction_time": True,
    },
    "EBD": {
        "label": "app序列 + Binder + 内存",
        "keep_system_evidence": "binder_only",
        "keep_points_of_interest": True,
        "keep_historical": True,
        "keep_memory_pressure": True,
        "keep_prediction_time": True,
    },
    "ECD": {
        "label": "app序列 + 文件 + 内存",
        "keep_system_evidence": "file_only",
        "keep_points_of_interest": False,
        "keep_historical": True,
        "keep_memory_pressure": True,
        "keep_prediction_time": True,
    },
    "BCD": {
        "label": "Binder + 文件 + 内存",
        "keep_system_evidence": True,
        "keep_points_of_interest": True,
        "keep_historical": False,
        "keep_memory_pressure": True,
        "keep_prediction_time": False,
    },
}

BINDER_KEYWORDS = ["MEMO_BINDER", "Binder", "Android Service IPC", "binder"]
FILE_KEYWORDS = ["MEMO_OPENAT", "Openat", "OPENAT", "file access",
                 "Native Runtime", "Dex/OAT", "Framework Loading",
                 "Database", "Cache/File", "Config File", "Other File",
                 "Process State", "System Property", "APEX Runtime",
                 "Kernel Trace", "Device/IPC"]


def apply_ablation(context: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    """Apply ablation config to context, returning modified copy."""
    ctx = json.loads(json.dumps(context))  # deep copy

    if config["keep_system_evidence"] is False:
        ctx["system_evidence"] = []
    elif config["keep_system_evidence"] == "binder_only":
        ctx["system_evidence"] = [
            line for line in ctx.get("system_evidence", [])
            if any(kw.lower() in line.lower() for kw in BINDER_KEYWORDS)
        ]
    elif config["keep_system_evidence"] == "file_only":
        ctx["system_evidence"] = [
            line for line in ctx.get("system_evidence", [])
            if any(kw.lower() in line.lower() for kw in FILE_KEYWORDS)
        ]

    if not config["keep_points_of_interest"]:
        ctx["points_of_interest"] = []

    if not config["keep_historical"]:
        ctx["historical_app_categories"] = []
        ctx["historical_app_ids"] = []

    if not config["keep_memory_pressure"]:
        ctx["memory_pressure"] = ""

    if not config["keep_prediction_time"]:
        ctx["prediction_time"] = ""

    return ctx


def build_stage1_prompt(context: dict[str, Any]) -> str:
    """Build a clean, unbiased prompt for Stage 1 category prediction."""
    evidence = context.get("system_evidence", [])
    hints = context.get("points_of_interest", [])
    categories = context.get("historical_app_categories", [])
    mp = context.get("memory_pressure", "")
    pred_time = context.get("prediction_time", "")

    parts = []

    parts.append(
        "You are an Android system evidence analyzer. Below is eBPF trace evidence "
        "from an Android device. Your task is to identify the DOMINANT resource-demand "
        "category based on the event counts in the evidence.\n"
    )

    if evidence:
        parts.append("=== System Evidence (eBPF trace summary) ===")
        for line in evidence:
            parts.append(f"  {line}")
        parts.append("")

    if mp:
        parts.append(f"Memory pressure: {mp}\n")

    if categories:
        parts.append(f"Top categories detected: {', '.join(categories)}\n")

    if hints:
        parts.append(f"System hints: {', '.join(hints)}\n")

    if pred_time:
        parts.append(f"Time: {pred_time}\n")

    parts.append("=== Available Categories ===")
    for cat in ALL_CATEGORIES:
        parts.append(f"  - {cat}")
    parts.append("")

    parts.append(
        "Which category is the DOMINANT resource demand based on the evidence? "
        "Look at which category has the highest count or most evidence lines. "
        "Output ONLY the exact category name, nothing else. "
        "Do NOT explain, do NOT output percentages. Just the category name."
    )

    return "\n".join(parts)


def match_category(raw_output: str) -> str:
    """Match model output to closest known category."""
    cleaned = raw_output.strip().strip('"').strip("'").strip()

    # Exact match
    for cat in ALL_CATEGORIES:
        if cleaned == cat:
            return cat

    # Case-insensitive exact
    lower = cleaned.lower()
    for cat in ALL_CATEGORIES:
        if lower == cat.lower():
            return cat

    # Substring match (longest wins, avoids "IPC" matching "Android Service IPC" partially)
    best = ""
    for cat in ALL_CATEGORIES:
        if cat.lower() in lower and len(cat) > len(best):
            best = cat
    if best:
        return best

    # Fallback: return raw (will likely be wrong)
    return cleaned


@dataclass
class AblationResult:
    config_id: str
    sample_id: str
    gt_category: str
    predicted: str
    top1_correct: bool
    raw_output: str
    latency_ms: float


def load_samples(dataset_dir: Path) -> list[dict[str, Any]]:
    samples_dir = dataset_dir / "samples"
    if not samples_dir.exists():
        raise FileNotFoundError(f"Samples directory not found: {samples_dir}")
    samples = []
    for path in sorted(samples_dir.glob("*.json")):
        samples.append(json.loads(path.read_text(encoding="utf-8")))
    return samples


class DeepSeekInference:
    """Calls DeepSeek API for MAPLE inference."""

    def __init__(self, api_key: str, concurrency: int = 200, base_url: str = DEEPSEEK_BASE):
        self.api_key = api_key
        self.concurrency = concurrency
        self.base_url = base_url
        self.semaphore: asyncio.Semaphore | None = None
        self.session: aiohttp.ClientSession | None = None

    async def __aenter__(self):
        self.semaphore = asyncio.Semaphore(self.concurrency)
        connector = aiohttp.TCPConnector(limit=self.concurrency, limit_per_host=self.concurrency)
        timeout = aiohttp.ClientTimeout(total=60)
        self.session = aiohttp.ClientSession(connector=connector, timeout=timeout)
        return self

    async def __aexit__(self, *args):
        if self.session:
            await self.session.close()

    async def predict_one(self, prompt: str, retries: int = 2) -> tuple[str, float]:
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": DEEPSEEK_MODEL,
            "messages": [
                {"role": "system", "content": "You are a precise classifier. Output only the requested category name, no explanations."},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.0,
            "max_tokens": 64,
            "stream": False,
        }

        for attempt in range(retries + 1):
            try:
                async with self.semaphore:
                    t0 = time.perf_counter()
                    async with self.session.post(
                        f"{self.base_url}/v1/chat/completions",
                        headers=headers,
                        json=payload,
                    ) as resp:
                        latency = (time.perf_counter() - t0) * 1000
                        if resp.status == 429:
                            await asyncio.sleep(1 + attempt)
                            continue
                        if resp.status != 200:
                            if attempt < retries:
                                await asyncio.sleep(0.5)
                                continue
                            return f"HTTP_{resp.status}", latency

                        data = await resp.json()
                        content = data["choices"][0]["message"]["content"]
                        return content, latency

            except (aiohttp.ClientError, asyncio.TimeoutError) as e:
                if attempt < retries:
                    await asyncio.sleep(0.5)
                    continue
                return f"ERROR:{e}", 0

        return "ERROR:retries_exhausted", 0


async def run_ablation_async(
    infer: DeepSeekInference,
    samples: list[dict[str, Any]],
    prompt_builder: MapleEngine | None = None,
    configs: list[str] | None = None,
) -> dict[str, list[AblationResult]]:
    """Run all ablation configs on all samples using DeepSeek API.

    If prompt_builder is provided, uses MAPLE PromptBuilder to construct
    prompts (identical to local inference path). Otherwise uses built-in
    build_stage1_prompt().
    """
    if configs is None:
        configs = ["A", "B", "C", "D", "E", "EB", "EC", "ED", "BC", "BD", "CD", "EBC", "EBD", "ECD", "BCD"]
    all_results: dict[str, list[AblationResult]] = defaultdict(list)

    tasks: list[tuple[str, str, str, str]] = []
    for sample in samples:
        original_context = sample["context"]
        gt_category = sample["ground_truth"]["stage1_category"]
        sample_id = sample["id"]

        for config_id in configs:
            config = ABLATION_CONFIGS[config_id]
            ablated = apply_ablation(original_context, config)

            if prompt_builder:
                prompt = prompt_builder.build_prompt(json.dumps(ablated, ensure_ascii=False))
            else:
                prompt = build_stage1_prompt(ablated)

            tasks.append((config_id, sample_id, gt_category, prompt))

        random_cat = random.choice(ALL_CATEGORIES)
        all_results["F"].append(AblationResult(
            config_id="F", sample_id=sample_id,
            gt_category=gt_category, predicted=random_cat,
            top1_correct=(random_cat == gt_category),
            raw_output="random", latency_ms=0.0,
        ))

    total = len(tasks)
    print(f"Running {total} inferences with concurrency={infer.concurrency}...")
    t_start = time.perf_counter()

    async def run_one(config_id, sample_id, gt, prompt):
        raw, lat = await infer.predict_one(prompt)
        predicted = _parse_app_type_py(raw)
        correct = (predicted == gt)
        return AblationResult(
            config_id=config_id, sample_id=sample_id,
            gt_category=gt, predicted=predicted,
            top1_correct=correct, raw_output=raw, latency_ms=lat,
        )

    coros = [run_one(cid, sid, gt, prompt) for cid, sid, gt, prompt in tasks]
    results_list = await asyncio.gather(*coros)

    for r in results_list:
        all_results[r.config_id].append(r)

    elapsed = time.perf_counter() - t_start
    print(f"Done in {elapsed:.1f}s ({elapsed/total*1000:.0f}ms per inference)")

    return dict(all_results)


def run_ablation_local(args: argparse.Namespace,
                       samples: list[dict[str, Any]],
                       configs: list[str] | None = None) -> dict[str, list[AblationResult]]:
    """Run ablation using local MAPLE engine (one model load, many inferences)."""
    if configs is None:
        configs = ["A", "B", "C", "D", "E", "EB", "EC", "ED", "BC", "BD", "CD", "EBC", "EBD", "ECD", "BCD"]
    include_random = "F" not in (configs or [])
    all_results: dict[str, list[AblationResult]] = defaultdict(list)

    print(f"\nLoading MAPLE model: {args.model}")
    t_load = time.perf_counter()
    engine = MapleEngine(model_path=args.model, n_threads=args.n_threads)
    load_time = time.perf_counter() - t_load
    print(f"  Model loaded in {load_time:.1f}s")

    try:
        total = len(samples) * len(configs)
        done = 0
        t_start = time.perf_counter()

        for sample in samples:
            original_context = sample["context"]
            gt_category = sample["ground_truth"]["stage1_category"]
            sample_id = sample["id"]

            for config_id in configs:
                config = ABLATION_CONFIGS[config_id]
                ablated = apply_ablation(original_context, config)
                context_json = json.dumps(ablated, ensure_ascii=False)

                try:
                    t0 = time.perf_counter()
                    predicted, raw = engine.predict_app_type(context_json)
                    lat = (time.perf_counter() - t0) * 1000

                    correct = (predicted == gt_category)
                    all_results[config_id].append(AblationResult(
                        config_id=config_id, sample_id=sample_id,
                        gt_category=gt_category, predicted=predicted,
                        top1_correct=correct, raw_output=raw, latency_ms=lat,
                    ))
                except Exception as e:
                    all_results[config_id].append(AblationResult(
                        config_id=config_id, sample_id=sample_id,
                        gt_category=gt_category, predicted=f"ERROR:{e}",
                        top1_correct=False, raw_output=str(e), latency_ms=0.0,
                    ))

                done += 1
                print(f"\rProgress: {done}/{total}", end="", file=sys.stderr, flush=True)

        elapsed = time.perf_counter() - t_start
        print(f"\nDone in {elapsed:.1f}s ({elapsed/total*1000:.0f}ms per inference)")
    finally:
        engine.close()

    return dict(all_results)


def compute_metrics(results: dict[str, list[AblationResult]],
                    noise_levels: dict[str, str] | None = None) -> dict[str, dict[str, Any]]:
    """Compute accuracy metrics, optionally broken down by noise level."""
    metrics: dict[str, dict[str, Any]] = {}
    for config_id, config_results in results.items():
        if not config_results:
            continue
        n = len(config_results)
        top1_correct = sum(1 for r in config_results if r.top1_correct)
        latencies = [r.latency_ms for r in config_results if r.latency_ms > 0]
        avg_lat = sum(latencies) / len(latencies) if latencies else 0

        metrics[config_id] = {
            "top1_accuracy": top1_correct / n,
            "avg_latency_ms": avg_lat,
            "num_samples": n,
        }

        # Noise-level breakdown if available
        if noise_levels:
            for level in ["低（0-10%）", "中（15-25%）", "高（35%+）"]:
                level_results = [
                    r for r in config_results
                    if noise_levels.get(r.sample_id, "") == level
                ]
                if level_results:
                    ln = len(level_results)
                    lc = sum(1 for r in level_results if r.top1_correct)
                    metrics[config_id][f"top1_accuracy_{level}"] = lc / ln
                    metrics[config_id][f"num_{level}"] = ln

    return metrics


def format_results_table(metrics: dict[str, dict[str, Any]]) -> str:
    lines = []
    lines.append("| 实验组 | 配置 | Top-1 准确率 | 低噪声准确率 | 中噪声准确率 | 高噪声准确率 | 平均延迟 (ms) | 样本数 |")
    lines.append("|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|")

    for config_id in ["A", "B", "C", "D", "E", "EB", "EC", "ED", "BC", "BD", "CD", "EBC", "EBD", "ECD", "BCD", "F"]:
        m = metrics.get(config_id, {})
        cfg = ABLATION_CONFIGS[config_id]
        top1 = m.get("top1_accuracy", 0) * 100
        low = m.get("top1_accuracy_低（0-10%）", 0) * 100
        mid = m.get("top1_accuracy_中（15-25%）", 0) * 100
        high = m.get("top1_accuracy_高（35%+）", 0) * 100
        avg_lat = m.get("avg_latency_ms", 0)
        n = int(m.get("num_samples", 0))
        lines.append(
            f"| {config_id} | {cfg['label']} | {top1:.1f}% | {low:.1f}% | {mid:.1f}% | {high:.1f}% | {avg_lat:.0f} | {n} |"
        )

    lines.append("")
    lines.append(f"*生成时间: {datetime.now(timezone.utc).isoformat(timespec='seconds')}*")
    return "\n".join(lines)


def print_detailed_breakdown(results: dict[str, list[AblationResult]]):
    """Print per-sample details for debugging."""
    for config_id in ["A", "B", "C", "D", "E", "EB", "EC", "ED", "BC", "BD", "CD", "EBC", "EBD", "ECD", "BCD"]:
        config_results = results.get(config_id, [])
        if not config_results:
            continue
        correct = sum(1 for r in config_results if r.top1_correct)
        total = len(config_results)
        print(f"\n--- Config {config_id}: {correct}/{total} correct ({correct/total*100:.1f}%) ---")

        # Show mistakes
        mistakes = [r for r in config_results if not r.top1_correct]
        if mistakes:
            pred_counter = Counter(r.predicted for r in mistakes)
            print(f"  Error predictions: {dict(pred_counter.most_common(5))}")


def main():
    parser = argparse.ArgumentParser(
        description="Run ablation experiments on synthetic MAPLE data."
    )
    parser.add_argument("--api-key", default=None, help="DeepSeek API key (required unless --local)")
    parser.add_argument("--dataset", type=Path, required=True,
                        help="Path to synthetic dataset directory")
    parser.add_argument("--output", type=Path, default=None,
                        help="Path to write Markdown results table")
    parser.add_argument("--results-json", type=Path, default=None,
                        help="Path to write detailed results JSON")
    parser.add_argument("--concurrency", type=int, default=200,
                        help="Max concurrent API requests")
    parser.add_argument("--limit", type=int, default=0,
                        help="Limit number of samples (0 = all)")
    parser.add_argument("--base-url", default=DEEPSEEK_BASE,
                        help="API base URL")
    parser.add_argument("--configs", default=None,
                        help="Comma-separated config IDs to run (default: all non-F)")
    parser.add_argument("--verbose", action="store_true",
                        help="Print per-sample predictions")
    parser.add_argument("--local", action="store_true",
                        help="Use local MAPLE engine instead of DeepSeek API")
    parser.add_argument("--model", default=None,
                        help="Path to GGUF model file (required for --local)")
    parser.add_argument("--n-threads", type=int, default=8,
                        help="CPU threads for local MAPLE inference")
    args = parser.parse_args()

    print(f"Loading samples from {args.dataset}...")
    samples = load_samples(args.dataset)
    if args.limit > 0:
        samples = samples[:args.limit]
    print(f"Loaded {len(samples)} samples.")

    # Build noise level lookup
    noise_levels = {}
    for s in samples:
        noise_levels[s["id"]] = s.get("grid_cell", {}).get("noise_level", "")

    noise_dist = Counter(noise_levels.values())
    print(f"Noise distribution: {dict(noise_dist.most_common())}")

    # Parse config filter
    target_configs = None
    if args.configs:
        target_configs = [c.strip() for c in args.configs.split(",")]

    if args.local:
        if not args.model:
            print("ERROR: --model is required for --local mode", file=sys.stderr)
            return 1
        results = run_ablation_local(args, samples, configs=target_configs)
    else:
        if not args.api_key:
            print("ERROR: --api-key is required (or use --local)", file=sys.stderr)
            return 1
        # Use MAPLE PromptBuilder to build prompts (same as local path)
        prompt_builder = MaplePromptBuilder()
        async def _run():
            async with DeepSeekInference(args.api_key, args.concurrency, args.base_url) as infer:
                return await run_ablation_async(infer, samples, prompt_builder=prompt_builder, configs=target_configs)
        results = asyncio.run(_run())

    if args.verbose:
        print_detailed_breakdown(results)

    metrics = compute_metrics(results, noise_levels)
    table = format_results_table(metrics)

    print("\n" + "=" * 70)
    print("ABLATION RESULTS")
    print("=" * 70)
    print(table)

    if args.output:
        header = (
            "# MEMO-Appflow 消融实验结果\n\n"
            f"推理模型: DeepSeek Chat API\n"
            f"数据集: `{args.dataset}` ({len(samples)} 样本)\n"
            f"噪声分布: {dict(noise_dist.most_common())}\n\n"
            "## 结果汇总\n\n"
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(header + table + "\n", encoding="utf-8")
        print(f"\nResults written to {args.output}")

    if args.results_json:
        serializable = {}
        for config_id, config_results in results.items():
            serializable[config_id] = [r.__dict__ for r in config_results]
        args.results_json.parent.mkdir(parents=True, exist_ok=True)
        args.results_json.write_text(
            json.dumps({
                "metrics": metrics,
                "results": serializable,
                "dataset": str(args.dataset),
                "num_samples": len(samples),
            }, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Detailed results written to {args.results_json}")


if __name__ == "__main__":
    main()
