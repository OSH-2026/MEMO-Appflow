#!/usr/bin/env python3
"""Synthetic dataset generator for MEMO-Appflow ablation experiments.

Grid-based synthesis: each sample independently random-samples 7 dimensions,
then calls DeepSeek API to generate a MAPLE UserContext with ground truth labels.

High concurrency via aiohttp — targets 100-200 concurrent requests.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiohttp

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / ".." / ".." / "dataset_synthetic"

# ────────────────────────────────────────────────────────────
# Grid dimensions
# ────────────────────────────────────────────────────────────

GRID_DIMENSIONS: dict[str, list[str]] = {
    "scenario": [
        "拍照/录像",
        "导航",
        "游戏",
        "视频播放",
        "桌面滑动",
        "应用切换",
        "休眠唤醒",
        "后台下载",
    ],
    "user_profile": [
        "重度多任务者",
        "轻量单应用户",
        "视频/娱乐型",
        "工作/工具型",
    ],
    "foreground_app": [
        "相机",
        "地图",
        "Chrome",
        "YouTube",
        "微信",
        "设置",
        "Launcher",
    ],
    "device_pressure": ["normal", "elevated", "critical"],
    "background_count": ["0", "1-2 个后台", "3+ 个后台"],
    "time_of_day": ["早晨通勤", "午休", "下午办公", "晚间娱乐", "深夜"],
    "noise_level": ["低（0-10%）", "中（15-25%）", "高（35%+）"],
}

# Scenario → ground truth mapping
SCENARIO_TO_GT: dict[str, tuple[str, int, str]] = {
    "拍照/录像": ("Camera Service", 250, "camera_pipeline"),
    "导航": ("Navigation/Location", 270, "location_pipeline"),
    "游戏": ("Display Composition", 115, "gpu_composition"),
    "视频播放": ("Media Codec", 260, "media_pipeline"),
    "桌面滑动": ("Display Composition", 115, "desktop_composition"),
    "应用切换": ("Android Service IPC", 110, "ipc_intensive"),
    "休眠唤醒": ("App Process Runtime", 280, "app_startup"),
    "后台下载": ("Cache/File Cache", 200, "file_io_intensive"),
}

CATEGORY_IDS: dict[str, int] = {
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

PROCESS_HINTS: dict[str, str] = {
    "system_server": "system service coordination",
    "surfaceflinger": "display compositor activity",
    "cameraserver": "camera service activity",
    "audioserver": "audio service activity",
    "netd": "network daemon activity",
    "servicemanager": "Binder service lookup",
    "hwservicemanage": "hardware service lookup",
    "zygote": "app process startup path",
    "mediacodec": "media codec activity",
    "mediaserver": "media server activity",
    "lmkd": "low memory killer activity",
    "kswapd0": "memory reclaim daemon activity",
    "installd": "package installation activity",
    "dex2oat": "dex compilation activity",
    "logd": "system logging activity",
    "vold": "volume/disk management activity",
    "wificond": "Wi-Fi management activity",
    "rild": "radio interface activity",
    "healthd": "battery/health monitoring",
}

SCENARIO_PROCESS_HINTS: dict[str, list[str]] = {
    "拍照/录像": ["cameraserver", "surfaceflinger", "mediaserver", "system_server"],
    "导航": ["surfaceflinger", "system_server", "netd", "servicemanager"],
    "游戏": ["surfaceflinger", "system_server", "audioserver", "servicemanager"],
    "视频播放": ["mediacodec", "mediaserver", "surfaceflinger", "audioserver"],
    "桌面滑动": ["surfaceflinger", "system_server", "servicemanager"],
    "应用切换": ["system_server", "servicemanager", "zygote", "surfaceflinger"],
    "休眠唤醒": ["system_server", "surfaceflinger", "zygote", "servicemanager"],
    "后台下载": ["system_server", "netd", "installd", "vold"],
}

SCENARIO_BINDER_RATIO: dict[str, str] = {
    "拍照/录像": "high",
    "导航": "medium",
    "游戏": "medium",
    "视频播放": "low",
    "桌面滑动": "medium",
    "应用切换": "very high",
    "休眠唤醒": "medium",
    "后台下载": "low",
}

NOISE_INSTRUCTIONS: dict[str, str] = {
    "低（0-10%）": "Keep evidence focused on the dominant scenario. At most 1 line may reference an irrelevant process or category.",
    "中（15-25%）": "About 1/4 of the evidence lines should come from unrelated categories and background processes not related to the scenario. Mix them naturally into the evidence list.",
    "高（35%+）": "About 1/3 of the evidence lines should be noise — categories and processes that have nothing to do with the scenario. The dominant signal should still be visible but clearly diluted.",
}

# Additional categories for noise injection (NOT the ground truth category for any scenario)
NOISE_CATEGORIES = [
    "Database",
    "Config File Access",
    "Other File Access",
    "Device/IPC Node Access",
    "Kernel Trace Setup",
    "System Property Access",
    "APEX Runtime Loading",
]

NOISE_PROCESSES = [
    "logd", "healthd", "wificond", "rild", "vold",
    "installd", "dex2oat", "netd",
]

DEEPSEEK_BASE = "https://api.deepseek.com"
DEEPSEEK_BASE_OVERRIDE: str | None = None
DEEPSEEK_MODEL = "deepseek-chat"


@dataclass
class GridCell:
    scenario: str
    user_profile: str
    foreground_app: str
    device_pressure: str
    background_count: str
    time_of_day: str
    noise_level: str


@dataclass
class GTSkeleton:
    stage1_category: str
    stage1_category_id: int
    stage2_app_id: int
    resource_demand_profile: str


def random_grid_cell() -> GridCell:
    return GridCell(
        scenario=random.choice(GRID_DIMENSIONS["scenario"]),
        user_profile=random.choice(GRID_DIMENSIONS["user_profile"]),
        foreground_app=random.choice(GRID_DIMENSIONS["foreground_app"]),
        device_pressure=random.choice(GRID_DIMENSIONS["device_pressure"]),
        background_count=random.choice(GRID_DIMENSIONS["background_count"]),
        time_of_day=random.choice(GRID_DIMENSIONS["time_of_day"]),
        noise_level=random.choice(GRID_DIMENSIONS["noise_level"]),
    )


def make_gt(cell: GridCell) -> GTSkeleton:
    gt_cat, gt_id, profile = SCENARIO_TO_GT[cell.scenario]
    return GTSkeleton(
        stage1_category=gt_cat,
        stage1_category_id=gt_id,
        stage2_app_id=gt_id,
        resource_demand_profile=profile,
    )


def build_categories_table() -> str:
    lines = []
    for name, cid in sorted(CATEGORY_IDS.items(), key=lambda x: x[1]):
        lines.append(f"  {cid}: {name}")
    return "\n".join(lines)


def build_process_hints_table(cell: GridCell) -> str:
    lines = []
    hint_names = SCENARIO_PROCESS_HINTS.get(cell.scenario, [])
    for name in hint_names:
        hint = PROCESS_HINTS.get(name, f"{name} process activity")
        lines.append(f"  {name}: {hint}")
    for name in NOISE_PROCESSES:
        hint = PROCESS_HINTS.get(name, f"{name} process activity")
        lines.append(f"  {name}: {hint} (background/noise)")
    return "\n".join(lines)


def build_prompt(cell: GridCell, gt: GTSkeleton, index: int) -> str:
    noise_instr = NOISE_INSTRUCTIONS[cell.noise_level]
    categories_table = build_categories_table()
    process_hints_table = build_process_hints_table(cell)
    binder_ratio = SCENARIO_BINDER_RATIO.get(cell.scenario, "medium")

    return f"""Generate ONE complete JSON object for an Android memory scheduling system that collects eBPF kernel events. The system feeds structured evidence to a local LLM which predicts the dominant resource demand category.

Fixed scene parameters for this sample:
  Scenario: {cell.scenario}
  User profile: {cell.user_profile}
  Foreground app: {cell.foreground_app}
  Device pressure: {cell.device_pressure}
  Background services: {cell.background_count}
  Time: {cell.time_of_day}
  Noise level: {cell.noise_level}
  Binder-to-Openat ratio: {binder_ratio}

GROUND TRUTH for this sample:
  stage1_category = "{gt.stage1_category}"
  stage1_category_id = {gt.stage1_category_id}

REQUIRED OUTPUT FORMAT (exact JSON, no markdown fences):

{{
  "id": "synth_{cell.scenario.replace(' ', '_').replace('/', '_')}_{index:03d}",
  "description": "<one natural Chinese sentence describing what the user is doing, consistent with the scene parameters>",
  "source": "synthetic",
  "grid_cell": {{
    "scenario": "{cell.scenario}",
    "user_profile": "{cell.user_profile}",
    "foreground_app": "{cell.foreground_app}",
    "device_pressure": "{cell.device_pressure}",
    "background_count": "{cell.background_count}",
    "time_of_day": "{cell.time_of_day}",
    "noise_level": "{cell.noise_level}"
  }},
  "context": {{
    "historical_app_categories": [<3-5 category strings, first one MUST be "{gt.stage1_category}">],
    "historical_app_ids": [<matching IDs, first one MUST be {gt.stage1_category_id}>],
    "prediction_time": "<Weekday HH:MM AM/PM consistent with time parameter>",
    "points_of_interest": [<3-5 process hints, in Chinese, relevant to this scenario>],
    "installed_apps": {{ "<category>": [<id>], ... (3-5 entries, first must be "{gt.stage1_category}": [{gt.stage1_category_id}]) }},
    "system_evidence": [<7-10 natural language evidence lines, see rules below>],
    "memory_pressure": "<must match device_pressure parameter, detailed format below>",
    "scheduler_goal": "Bridge MEMO eBPF evidence into MAPLE. Infer near-future resource demand and memory scheduling; treat app sequence statistics only as a weak baseline."
  }},
  "ground_truth": {{
    "stage1_category": "{gt.stage1_category}",
    "stage1_category_id": {gt.stage1_category_id},
    "stage2_app_id": {gt.stage1_category_id},
    "resource_demand_profile": "{gt.resource_demand_profile}",
    "expected_memory_action": "<one Chinese sentence: what the scheduler should do given this scenario and device pressure>",
    "confidence_note": "<high/medium/low: how clearly the evidence points to the gt category>"
  }}
}}

RULES FOR system_evidence — FOLLOW THESE CAREFULLY:

1. Start with: "<N> eBPF records in the observed Android emulator window"
   Pick N between 3000-25000 that makes sense for the scenario and user profile.

2. Include 2-3 lines about event_type distribution:
   "event_type MEMO_BINDER: count=<N>" and/or "event_type MEMO_OPENAT: count=<N>"
   Binder-to-Openat ratio should be {binder_ratio}. Camera/navigation/gaming → high Binder; app launch/download → high Openat.

3. Include 3-4 lines about MAPLE evidence/resource category counts.
   The dominant category "{gt.stage1_category}" MUST have the highest count or close to it.
   Use exact category names from the CATEGORY_IDS table.

4. Include 2-3 lines about process activity: "process <name>: count=<N>"
   Process names must be realistic for {cell.foreground_app} and the scenario.

5. {noise_instr}

6. Every number, process name, and category count must be internally consistent with the scene parameters. Generate fresh counts — do not copy from any template.

7. Vary writing style: sometimes "X events in category Y", sometimes "category Y: X records", sometimes "observed X Y events".

RULES FOR memory_pressure:
  "normal" → "normal: no direct reclaim tracepoint was observed"
  "elevated" → "elevated: direct reclaim events observed count=<N>" (N=1-5)
  "critical" → "critical: frequent direct reclaim and kswapd activity, reclaim_count=<N>" (N=6+)

RULES FOR description: write in natural Chinese. Vary sentence structure across samples.

RULES FOR points_of_interest: output 3-5 Chinese hints like "相机服务活跃", "显示合成器工作负载高", "应用进程正在启动". Use the process hints table for reference but write in Chinese.

CATEGORY_IDS REFERENCE TABLE:
{categories_table}

PROCESS HINTS REFERENCE:
{process_hints_table}

Output ONLY the JSON object. No markdown fences, no extra commentary."""


MEMORY_PRESSURE_TEMPLATES: dict[str, list[str]] = {
    "normal": [
        "normal: no direct reclaim tracepoint was observed",
    ],
    "elevated": [
        "elevated: direct reclaim events observed count=2",
        "elevated: direct reclaim events observed count=3",
        "elevated: direct reclaim events observed count=4",
        "elevated: direct reclaim events observed count=1",
        "elevated: direct reclaim events observed count=5",
    ],
    "critical": [
        "critical: frequent direct reclaim and kswapd activity, reclaim_count=7",
        "critical: frequent direct reclaim and kswapd activity, reclaim_count=9",
        "critical: frequent direct reclaim and kswapd activity, reclaim_count=12",
        "critical: frequent direct reclaim and kswapd activity, reclaim_count=6",
        "critical: frequent direct reclaim and kswapd activity, reclaim_count=15",
    ],
}


def validate_sample(sample: dict[str, Any]) -> list[str]:
    """Validate a synthetic sample. Returns list of error messages (empty = valid)."""
    errors: list[str] = []

    # Required top-level fields
    for key in ("id", "description", "source", "grid_cell", "context", "ground_truth"):
        if key not in sample:
            errors.append(f"missing top-level field: {key}")
    if errors:
        return errors

    ctx = sample.get("context", {})
    gt = sample.get("ground_truth", {})
    grid = sample.get("grid_cell", {})

    # Check context fields
    for field in ("historical_app_categories", "historical_app_ids", "prediction_time",
                  "points_of_interest", "installed_apps", "system_evidence",
                  "memory_pressure", "scheduler_goal"):
        if field not in ctx:
            errors.append(f"context missing field: {field}")

    # Check ground_truth fields
    for field in ("stage1_category", "stage1_category_id", "stage2_app_id",
                  "resource_demand_profile", "expected_memory_action", "confidence_note"):
        if field not in gt:
            errors.append(f"ground_truth missing field: {field}")

    # Validate system_evidence
    evidence = ctx.get("system_evidence", [])
    if not isinstance(evidence, list) or len(evidence) < 5:
        errors.append(f"system_evidence too short: {len(evidence) if isinstance(evidence, list) else 'not a list'}")
    if isinstance(evidence, list) and evidence:
        if not isinstance(evidence[0], str) or "eBPF records" not in evidence[0]:
            errors.append("first evidence line must contain 'eBPF records' count")

    # Validate ground truth consistency with grid
    scenario = grid.get("scenario", "")
    if scenario in SCENARIO_TO_GT:
        expected_cat, expected_id, _ = SCENARIO_TO_GT[scenario]
        if gt.get("stage1_category") != expected_cat:
            errors.append(f"gt category mismatch: {gt.get('stage1_category')} != {expected_cat}")
        if gt.get("stage1_category_id") != expected_id:
            errors.append(f"gt id mismatch: {gt.get('stage1_category_id')} != {expected_id}")

    # Validate category IDs
    for cat in ctx.get("historical_app_categories", []):
        if cat not in CATEGORY_IDS:
            errors.append(f"unknown category in historical_app_categories: {cat}")
    for cat in ctx.get("installed_apps", {}):
        if cat not in CATEGORY_IDS:
            errors.append(f"unknown category in installed_apps: {cat}")

    # Validate memory_pressure
    mp = ctx.get("memory_pressure", "")
    device_pressure = grid.get("device_pressure", "")
    if device_pressure == "normal" and "elevated" in mp.lower():
        errors.append("device_pressure=normal but memory_pressure says elevated")
    if device_pressure == "normal" and "critical" in mp.lower():
        errors.append("device_pressure=normal but memory_pressure says critical")

    return errors


def extract_json_from_response(text: str) -> str | None:
    """Extract JSON object from LLM response, handling markdown fences."""
    text = text.strip()
    # Remove markdown code fences if present
    if text.startswith("```"):
        lines = text.split("\n")
        # Remove first line (```json or ```)
        if lines:
            lines = lines[1:]
        # Remove last line if it's ```
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    # Find first { and last }
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None
    return text[start:end + 1]


class DeepSeekSynthesizer:
    def __init__(self, api_key: str, concurrency: int = 150):
        self.api_key = api_key
        self.concurrency = concurrency
        self.semaphore: asyncio.Semaphore | None = None
        self.session: aiohttp.ClientSession | None = None
        self.stats: dict[str, int] = Counter()

    async def __aenter__(self):
        self.semaphore = asyncio.Semaphore(self.concurrency)
        connector = aiohttp.TCPConnector(limit=self.concurrency, limit_per_host=self.concurrency)
        timeout = aiohttp.ClientTimeout(total=120)
        self.session = aiohttp.ClientSession(connector=connector, timeout=timeout)
        return self

    async def __aexit__(self, *args):
        if self.session:
            await self.session.close()

    async def generate_one(self, cell: GridCell, gt: GTSkeleton, index: int,
                           retries: int = 3) -> dict[str, Any] | None:
        prompt = build_prompt(cell, gt, index)
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": DEEPSEEK_MODEL,
            "messages": [
                {"role": "system", "content": "You are a precise data generator. Output ONLY valid JSON, no markdown fences, no extra text."},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.7,
            "max_tokens": 2048,
            "stream": False,
        }

        for attempt in range(retries):
            try:
                async with self.semaphore:
                    base = DEEPSEEK_BASE_OVERRIDE or DEEPSEEK_BASE
                    async with self.session.post(
                        f"{base}/v1/chat/completions",
                        headers=headers,
                        json=payload,
                    ) as resp:
                        if resp.status == 429:
                            self.stats["rate_limited"] += 1
                            await asyncio.sleep(2 ** attempt)
                            continue
                        if resp.status != 200:
                            body = await resp.text()
                            self.stats[f"http_{resp.status}"] += 1
                            if attempt < retries - 1:
                                await asyncio.sleep(1)
                                continue
                            print(f"  [ERROR] HTTP {resp.status}: {body[:200]}", file=sys.stderr)
                            return None

                        data = await resp.json()
                        content = data["choices"][0]["message"]["content"]
                        json_str = extract_json_from_response(content)
                        if json_str is None:
                            self.stats["parse_failure"] += 1
                            if attempt < retries - 1:
                                await asyncio.sleep(0.5)
                                continue
                            return None

                        sample = json.loads(json_str)
                        errors = validate_sample(sample)
                        if errors:
                            self.stats["validation_failure"] += 1
                            if attempt < retries - 1:
                                await asyncio.sleep(0.5)
                                continue
                            print(f"  [VALIDATE] {sample.get('id', '?')}: {errors[:3]}", file=sys.stderr)
                            return None

                        self.stats["success"] += 1
                        return sample

            except (aiohttp.ClientError, asyncio.TimeoutError, json.JSONDecodeError) as e:
                self.stats["exception"] += 1
                if attempt < retries - 1:
                    await asyncio.sleep(1)
                    continue
                print(f"  [ERROR] {type(e).__name__}: {e}", file=sys.stderr)
                return None

        return None

    async def generate_batch(self, num_samples: int, seed: int = 42) -> list[dict[str, Any]]:
        random.seed(seed)
        cells_and_gts: list[tuple[GridCell, GTSkeleton]] = []
        for _ in range(num_samples):
            cell = random_grid_cell()
            gt = make_gt(cell)
            cells_and_gts.append((cell, gt))

        total = len(cells_and_gts)
        print(f"Generating {total} samples with concurrency={self.concurrency}...")
        t_start = time.monotonic()

        tasks = [
            self.generate_one(cell, gt, i + 1)
            for i, (cell, gt) in enumerate(cells_and_gts)
        ]
        results = await asyncio.gather(*tasks)

        elapsed = time.monotonic() - t_start
        samples = [r for r in results if r is not None]
        print(f"Done in {elapsed:.1f}s: {len(samples)}/{total} valid samples "
              f"({len(samples)/total*100:.1f}% yield)")
        print(f"Stats: {dict(self.stats)}")
        return samples


def save_dataset(samples: list[dict[str, Any]], out_dir: Path) -> Path:
    samples_dir = out_dir / "samples"
    samples_dir.mkdir(parents=True, exist_ok=True)

    for sample in samples:
        sid = sample["id"]
        path = samples_dir / f"{sid}.json"
        path.write_text(json.dumps(sample, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # Build manifest
    grid_dist: dict[str, Counter[str]] = defaultdict(Counter)
    gt_dist: Counter[str] = Counter()
    noise_dist: Counter[str] = Counter()
    pressure_dist: Counter[str] = Counter()

    for s in samples:
        for dim, val in s.get("grid_cell", {}).items():
            grid_dist[dim][val] += 1
        gt_dist[s.get("ground_truth", {}).get("stage1_category", "?")] += 1
        noise_dist[s.get("grid_cell", {}).get("noise_level", "?")] += 1
        pressure_dist[s.get("grid_cell", {}).get("device_pressure", "?")] += 1

    manifest = {
        "schema_version": "memo.synthetic_dataset.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "total_samples": len(samples),
        "grid_dimensions": {
            dim: {
                "values": values,
                "distribution": dict(grid_dist.get(dim, {}).most_common()),
            }
            for dim, values in GRID_DIMENSIONS.items()
        },
        "ground_truth_distribution": dict(gt_dist.most_common()),
        "noise_level_distribution": dict(noise_dist.most_common()),
        "device_pressure_distribution": dict(pressure_dist.most_common()),
        "category_ids": CATEGORY_IDS,
        "scenario_to_gt": {
            scenario: {"category": cat, "id": cid, "profile": prof}
            for scenario, (cat, cid, prof) in SCENARIO_TO_GT.items()
        },
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"\nSaved {len(samples)} samples to {samples_dir}/")
    print(f"Manifest: {manifest_path}")
    print(f"\nGround truth distribution:")
    for cat, count in gt_dist.most_common():
        print(f"  {cat}: {count}")
    print(f"\nNoise level distribution:")
    for level, count in noise_dist.most_common():
        print(f"  {level}: {count}")
    print(f"\nDevice pressure distribution:")
    for level, count in pressure_dist.most_common():
        print(f"  {level}: {count}")

    return manifest_path


def main():
    parser = argparse.ArgumentParser(
        description="Synthesize labeled eBPF evidence samples for MAPLE ablation experiments."
    )
    parser.add_argument("--num-samples", type=int, default=50,
                        help="Number of samples to generate")
    parser.add_argument("--concurrency", type=int, default=150,
                        help="Max concurrent DeepSeek API requests")
    parser.add_argument("--api-key", required=True,
                        help="DeepSeek API key")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="Output directory for dataset")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed for reproducibility")
    parser.add_argument("--base-url", default=DEEPSEEK_BASE,
                        help="DeepSeek API base URL")
    args = parser.parse_args()

    # Patch base URL for prompt builder
    global DEEPSEEK_BASE_OVERRIDE
    DEEPSEEK_BASE_OVERRIDE = args.base_url

    async def run():
        async with DeepSeekSynthesizer(args.api_key, args.concurrency) as synth:
            samples = await synth.generate_batch(args.num_samples, args.seed)
        if samples:
            save_dataset(samples, args.out)
        else:
            print("No valid samples generated.", file=sys.stderr)
            return 1
        return 0

    return asyncio.run(run())


if __name__ == "__main__":
    raise SystemExit(main())
