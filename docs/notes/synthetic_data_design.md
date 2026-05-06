# LLM 合成数据集设计方案

Date: 2026-05-06

---

## 问题定义

消融实验需要一个**有 ground-truth 标签的数据集**来回答：

- "仅给 Binder 证据，模型预测的资源类别对不对？"
- "去掉文件访问证据后，准确率跌多少？"
- "加回内存压力信号，效果恢复多少？"

真实 eBPF trace 没有标签——那 17849 条事件没有"正确答案"。所以要靠 LLM 合成有标签的数据。

---

## 1. 合成思路：网格法

### 1.1 为什么不选择其他方案

| 方案 | 问题 |
|---|---|
| **模板法**（8 个模板 × N 个变体） | 多样性上限 = 模板数。微调 count 数字不影响分布，实际只有 8 种有效样本 |
| **参数采样法**（Python 随机撒点 → LLM 翻译成文本） | Python 的随机分布（均匀/Dirichlet）和真实 Android 行为分布无任何关系，第一步就失真了 |
| **网格法**（离散维度组合 → LLM 自由生成） | 网格保证覆盖，LLM 负责真实感 |

### 1.2 核心假设

LLM 在预训练阶段见过的 Android 系统行为描述，比人工设计的任何参数模型都接近真实分布。我们不应叫 LLM 翻译一组外来数字，而应利用它已有的世界知识。

网格法把两个目标拆开，各交给擅长的工具：

```
网格（Python 枚举组合） → 保证覆盖 —— 每个格子至少一个样本
LLM 自由生成             → 保证真实感 —— 每个样本内的数字、措辞、细节都来自 LLM 的知识
```

---

## 2. 网格定义：7 个离散维度

### 2.1 维度总览

| # | 维度 | 作用 | 离散值 |
|---|---|---|---|
| 1 | **情景** | 决定 ground truth | 拍照、导航、游戏、视频播放、桌面滑动、应用切换、休眠唤醒、后台下载 |
| 2 | **用户原型** | 决定行为节奏和后台复杂度 | 重度多任务者、轻量单应用户、视频/娱乐型、工作/工具型 |
| 3 | **前端 app** | 约束 Binder/文件访问的具体目标 | 相机、地图、Chrome、YouTube、微信、设置、桌面 Launcher |
| 4 | **设备压力** | 决定 memory_pressure 字段值 | normal、elevated、critical |
| 5 | **后台活动数** | 影响 Binder 噪声和 IPC 复杂度 | 0、1-2 个后台服务、3+ 个后台竞争 |
| 6 | **时刻** | 时间上下文偏置 | 早晨通勤、午休、下午办公、晚间娱乐、深夜 |
| 7 | **噪声比例** | 消融实验的核心对抗变量 | 低（0-10%）、中（15-25%）、高（35%+） |

### 2.2 各维度说明

**维度 1：情景（最关键维度，直接决定 ground truth）**

| 情景值 | 对应 `stage1_category` | 对应 `stage1_category_id` |
|---|---|---|
| 拍照/录像 | Camera Service | 250 |
| 导航 | Navigation/Location | 270 |
| 游戏 | Display Composition | 115 |
| 视频播放 | Media Codec | 260 |
| 桌面滑动 | Display Composition | 115 |
| 应用切换 | Android Service IPC | 110 |
| 休眠唤醒 | App Process Runtime | 280 |
| 后台下载 | Cache/File Cache | 200 |

同一类别对应多个情景（桌面滑动和游戏都是 Display Composition），LLM 会在 evidence 细节上区分：游戏有更高的 Input Interaction 比例和 GPU 相关进程；桌面滑动则有更多 Launcher 相关的 Binder 调用。

**维度 2：用户原型**

干预后台服务数量、切换应用的频率、是否有持续的媒体后台。

**维度 3：前端 app**

约束具体的 Binder 目标进程、文件访问路径类别。相机 app 会拉出 cameraserver Binder、dma-buf 图形管线；Chrome 会有更多网络/数据库类文件访问。

**维度 4：设备压力**

| 值 | memory_pressure 字段形态 | 对应的事件信号 |
|---|---|---|
| normal | `"normal: no direct reclaim tracepoint was observed"` | reclaim=0 |
| elevated | `"elevated: direct reclaim events observed count=N"` (N=1-5) | 偶有 reclaim |
| critical | `"critical: frequent direct reclaim and kswapd activity"` | 频繁 reclaim + kswapd_wake > 0 |

**维度 5：后台活动数**

直接影响 system_evidence 中 process 行的数量和类型。0 个后台 → 证据干净；3+ 个后台 → 大量不相干进程的 Binder/file 事件（"process twitter: count=340"、"process spotify: count=210"），这些对消融实验中的噪声场景构造很关键。

**维度 6：时刻**

主要作用于 prediction_time 字段和"用户为什么用这个 app"的合理性——深夜拍照的概率远低于深夜看 YouTube。也可作为消融实验中"去掉时间上下文"的对比项。

**维度 7：噪声比例**

| 值 | 效果 |
|---|---|
| 低（0-10%） | evidence 中几乎每行都与 dominant category 相关 |
| 中（15-25%） | 约 1/4 的 evidence 行来自无关类别和无关进程 |
| 高（35%+） | 约 1/3 的 evidence 行是噪声，dominant 信号被稀释 |

这个维度直接服务于消融实验——测模型在高噪声下准确率的下降幅度。

### 2.3 网格组合数量

理论笛卡尔积：
```
8（情景）× 4（用户）× 7（前端app）× 3（设备压力）× 3（后台）× 5（时刻）× 3（噪声）
= 8 × 4 × 7 × 3 × 3 × 5 × 3 = 30240 个格子
```

实际不会全采样，而是采用**分层覆盖策略**：

- 每种情景 ≥ 30 个样本（保证 8 种情景都有充分覆盖）
- 每种设备压力级别 ≥ 150 个样本（消融实验中 memory 模块的核心对比维度）
- 每种噪声级别 ≥ 100 个样本（噪声鲁棒性测试的核心对比维度）
- 总计约 **300-500 个样本**

---

## 3. 合成目标格式

### 3.1 为什么在 UserContext 层合成

不是伪造原始 eBPF 事件（`pid=962 code=5 to_proc=555`），而是直接生成 MAPLE engine 吃的 UserContext JSON：

- UserContext 是 MAPLE 的直接输入，消融实验的控制点都在这个层级
- LLM 擅长生成自然语言证据摘要，不擅长伪造逼真的内核 trace 序列
- 标签和证据在同一轮生成，一致性由 prompt 约束保证
- 和 `maple_context_from_ebpf.py` 的输出完全同构，MAPLE engine 零改动

### 3.2 样本结构

```json
{
  "id": "synth_camera_003",
  "description": "午休时间，重度用户正在用相机 app 拍照，后台有社交媒体和音乐服务在运行",
  "source": "synthetic",
  "grid_cell": {
    "scenario": "拍照",
    "user_profile": "重度多任务者",
    "foreground_app": "相机",
    "device_pressure": "normal",
    "background_count": "3+ 个后台",
    "time_of_day": "午休",
    "noise_level": "中（15-25%）"
  },
  "context": { ... },
  "ground_truth": { ... }
}
```

### 3.3 `context` 字段

与 `maple_context_from_ebpf.py` 输出完全相同的结构：

| 字段 | 类型 | 消融实验中作为控制变量 |
|---|---|---|
| `historical_app_categories` | `string[]` (≤5) | 清空 → 模拟"无 app 序列信号" |
| `historical_app_ids` | `int[]` (≤5) | 同上 |
| `prediction_time` | string | 清空 → 模拟"无时间上下文" |
| `points_of_interest` | `string[]` (≤5) | 清空 → 模拟"无进程推断信号" |
| `installed_apps` | `{string: [int]}` (≤5 对) | 替换为随机 ID → 模拟"无候选信号" |
| `system_evidence` | `string[]` (≤10) | **核心变量**：按消融需求删减不同维度行 |
| `memory_pressure` | string | 替换为 `"normal"` → 模拟"无内存压力信号" |
| `scheduler_goal` | string | 固定值 |

### 3.4 `ground_truth` 字段

| 字段 | 来源 | 说明 |
|---|---|---|
| `stage1_category` | 由网格的情景维度直接决定 | 正确的资源需求类别 |
| `stage1_category_id` | 同上 | 对应 `CATEGORY_IDS` 的值 |
| `stage2_app_id` | 同上 | 同 stage1_category_id（Stage 2 本质是选类别 ID） |
| `resource_demand_profile` | 同上 | 场景标签 |
| `expected_memory_action` | 由情景 + 设备压力共同决定 | 该场景期望的系统调度动作 |
| `confidence_note` | LLM 自评 | high/medium/low，用于过滤低质量样本 |

---

## 4. 生成流程

### 4.1 Pipeline

```
┌─────────────────────────────────────────┐
│ 1. Python 枚举网格                           │
│    从 7 个维度各选一个值，生成 (grid_cell,    │
│    ground_truth 骨架) 的 pair               │
│    按分层覆盖策略决定每种 grid_cell 的份数      │
├─────────────────────────────────────────┤
│ 2. LLM 自由生成                              │
│    Prompt：给出 grid_cell 的各维度值 +       │
│    schema 模板 + 约束，LLM 自己决定：           │
│    - system_evidence 里每行的具体数字          │
│    - points_of_interest 的具体 process        │
│    - description 的自然语言表达                │
│    - 各 evidence 行之间的内在一致性             │
├─────────────────────────────────────────┤
│ 3. Schema 校验                               │
│    - JSON 结构是否完整                         │
│    - ground_truth 是否与 grid_cell 一致       │
│    - category 数值是否在 CATEGORY_IDS 范围内   │
│    - memory_pressure 是否匹配 device_pressure │
├─────────────────────────────────────────┤
│ 4. 质量检查                                  │
│    - 同一 grid_cell 的样本间 evidence 行去重   │
│    - 余弦相似度检查（类别分布向量）             │
│    - 过滤 confidence_note=low 的样本           │
└─────────────────────────────────────────┘
```

### 4.2 生成 Prompt 模板

```
You are generating synthetic training data for an Android memory scheduling
system that collects eBPF kernel events and feeds structured evidence to a
local LLM. The LLM reads the evidence and predicts the dominant resource
demand category.

Generate ONE complete JSON object. Here are the fixed scene parameters
for this sample:

  Scenario:       {scenario}
  User profile:   {user_profile}
  Foreground app: {foreground_app}
  Device pressure: {device_pressure}
  Background:     {background_count} services
  Time:           {time_of_day}
  Noise level:    {noise_level}

GROUND TRUTH for this sample:
  stage1_category = "{gt_category}"
  stage1_category_id = {gt_id}

REQUIRED OUTPUT FORMAT:

{
  "id": "synth_{scenario}_{index}",
  "description": "<one natural sentence describing what the user is doing>",
  "source": "synthetic",
  "grid_cell": { ... },
  "context": {
    "historical_app_categories": [<3-5 category strings, the FIRST must be {gt_category}>],
    "historical_app_ids": [<matching IDs from CATEGORY_IDS table below>],
    "prediction_time": "<Weekday HH:MM AM/PM consistent with time parameter>",
    "points_of_interest": [<3-5 process hints relevant to this scenario>],
    "installed_apps": { "<category>": [<id>], ... },
    "system_evidence": [<7-10 natural language evidence lines, see rules below>],
    "memory_pressure": "<must match device_pressure parameter>",
    "scheduler_goal": "Bridge MEMO eBPF evidence into MAPLE. Infer near-future resource demand and memory scheduling; treat app sequence statistics only as a weak baseline."
  },
  "ground_truth": {
    "stage1_category": "{gt_category}",
    "stage1_category_id": {gt_id},
    "stage2_app_id": {gt_id},
    "resource_demand_profile": "{resource_profile}",
    "expected_memory_action": "<one sentence: what the scheduler should do>",
    "confidence_note": "<high/medium/low: how clearly the evidence points to the gt category>"
  }
}

RULES FOR system_evidence — FOLLOW THESE CAREFULLY:

1. Start with: "<N> eBPF records in the observed Android emulator window"
   Pick N that makes sense for {scenario} and {user_profile} (3000-25000).

2. Include 2-3 lines about event_type distribution:
   "event_type MEMO_BINDER: count=<N>" and/or "event_type MEMO_OPENAT: count=<N>"
   Binder-to-Openat ratio should fit the scenario (camera/navigation → high Binder;
   app launch → high Openat).

3. Include 3-4 lines about MAPLE evidence/resource category counts.
   The dominant category ({gt_category}) MUST have the highest count or close to it.

4. Include 2-3 lines about process activity:
   "process <name>: count=<N>"
   Process names must be realistic for {foreground_app} and {scenario}.
   {noise_level}: include {noise_instruction}

5. Every number, every process name, every category count must be consistent
   with the scene parameters. Do NOT copy numbers from any real trace — generate
   fresh counts that fit the described scenario.

6. Vary your writing style across samples. Sometimes use "X events in category Y",
   sometimes "category Y: X records", sometimes "observed X Y events".

CATEGORY_IDS REFERENCE TABLE:
{category_ids_table}

PROCESS HINTS REFERENCE:
{process_hints_table}

Output ONLY the JSON object. No markdown, no code fences, no extra commentary.
```

### 4.3 噪声生成指令

根据 `noise_level` 维度，prompt 中的 `{noise_instruction}` 动态变化：

| noise_level | noise_instruction |
|---|---|
| 低（0-10%） | "Keep evidence focused on the dominant scenario. At most 1 line may reference an irrelevant process or category." |
| 中（15-25%） | "About 1/4 of the evidence lines should come from unrelated categories and background processes not related to {scenario}. Mix them naturally into the evidence list." |
| 高（35%+） | "About 1/3 of the evidence lines should be noise — categories and processes that have nothing to do with {scenario}. The dominant signal should still be visible but clearly diluted." |

### 4.4 分层采样脚本框架

```python
# dataset_synthesizer.py 的核心采样逻辑

GRID_DIMENSIONS = {
    "scenario": ["拍照", "导航", "游戏", "视频播放", "桌面滑动", "应用切换", "休眠唤醒", "后台下载"],
    "user_profile": ["重度多任务者", "轻量单应用户", "视频/娱乐型", "工作/工具型"],
    "foreground_app": ["相机", "地图", "Chrome", "YouTube", "微信", "设置", "Launcher"],
    "device_pressure": ["normal", "elevated", "critical"],
    "background_count": ["0", "1-2 个后台", "3+ 个后台"],
    "time_of_day": ["早晨通勤", "午休", "下午办公", "晚间娱乐", "深夜"],
    "noise_level": ["低", "中", "高"],
}

SCENARIO_TO_GT = {
    "拍照":     ("Camera Service",        250, "camera_pipeline"),
    "导航":     ("Navigation/Location",   270, "location_pipeline"),
    "游戏":     ("Display Composition",   115, "gpu_composition"),
    "视频播放":  ("Media Codec",           260, "media_pipeline"),
    "桌面滑动":  ("Display Composition",   115, "desktop_composition"),
    "应用切换":  ("Android Service IPC",   110, "ipc_intensive"),
    "休眠唤醒":  ("App Process Runtime",   280, "app_startup"),
    "后台下载":  ("Cache/File Cache",      200, "file_io_intensive"),
}

def sample_grid(target_per_scenario=30):
    """分层覆盖：保证每种情景至少 N 个样本，噪声和设备压力均衡分布"""
    samples = []
    for scenario, (gt_cat, gt_id, profile) in SCENARIO_TO_GT.items():
        # 对该情景，均衡采样其他 6 个维度
        combos = itertools.product(
            GRID_DIMENSIONS["user_profile"],
            GRID_DIMENSIONS["foreground_app"],
            GRID_DIMENSIONS["device_pressure"],
            GRID_DIMENSIONS["background_count"],
            GRID_DIMENSIONS["time_of_day"],
            GRID_DIMENSIONS["noise_level"],
        )
        # 随机抽取 target_per_scenario 个组合
        sampled = random.sample(list(combos), k=target_per_scenario)
        for combo in sampled:
            samples.append({
                "grid_cell": dict(zip(
                    ["user_profile", "foreground_app", "device_pressure",
                     "background_count", "time_of_day", "noise_level"], combo
                )),
                "scenario": scenario,
                "gt_category": gt_cat,
                "gt_id": gt_id,
                "resource_profile": profile,
            })
    return samples
```

---

## 5. 消融实验使用方法

### 5.1 变量控制

对每个合成样本的 `context` 字段做裁剪，生成 6 组实验输入：

| 实验组 | `system_evidence` | `points_of_interest` | `historical_app_*` | `memory_pressure` |
|---|---|---|---|---|
| A（完整 eBPF） | 保留全部 | 保留全部 | 保留 | 保留 |
| B（仅 Binder） | 仅保留含 "MEMO_BINDER"、"Binder"、"Android Service IPC" 的行 | 保留 | 清空 | 清空 |
| C（仅文件访问） | 仅保留含 "MEMO_OPENAT"、文件路径类别名的行 | 清空 | 清空 | 清空 |
| D（仅内存压力） | 清空 | 清空 | 清空 | 保留 |
| E（仅 app 序列） | 清空 | 清空 | 保留 | 清空 |
| F（随机 baseline） | 不调模型，随机从 18 个类别中选 | | | |

网格法的优势在这里体现：`noise_level` 维度生了不同噪声级别的样本。消融实验可以额外做"高噪声子集"的分析——看各组在高噪声条件下的准确率退化程度。

### 5.2 评估指标

```
Top-1 准确率 = (预测类别 == ground_truth.stage1_category) 的样本数 / 总样本数
Top-3 准确率 = (ground_truth.stage1_category 在 Top-3 预测中) 的样本数 / 总样本数
噪声退化率   = (低噪声准确率 - 高噪声准确率) / 低噪声准确率
平均推理延迟 = 端到端推理时间平均值
```

### 5.3 期望结果

| 实验组 | 低噪声准确率 | 高噪声准确率 | 关键结论 |
|---|---|---|---|
| A（全量 eBPF） | 80-90% | 50-65% | full pipeline 上限 |
| B（仅 Binder） | 60-70% | 35-50% | Binder 信号贡献 |
| C（仅文件） | 40-55% | 20-35% | 文件信号贡献 |
| D（仅内存） | 25-35% | 25-35% | 内存信号独立性 |
| E（仅序列） | 20-30% | 10-20% | 传统 baseline 上限 |
| F（随机） | ~6% | ~6% | 随机基线 |

这样一组结果能直观支撑项目的主要论证：eBPF 系统证据优于 app 序列 baseline，且各维度证据对最终预测都有独立贡献。

---

## 6. 总结

| 方面 | 做法 |
|---|---|
| 多样性保证 | 7 维度网格分层采样，LLM 在每个格子内自由生成细节 |
| Ground truth | 由情景维度直接映射，不依赖 LLM 判断 |
| 真实性 | LLM 凭预训练知识决定证据行内的具体数值和措辞 |
| 消融支持 | 噪声维度生成不同难度的样本；context 字段可按需裁剪 |
| 规模 | 300-500 个样本（8 种情景 × ~30-60 个变体） |
| 可复现 | 分层采样用固定种子，同一 grid_cell 多次采样生成变体 |

---

## 7. 输出物清单

| 文件 | 说明 |
|---|---|
| `scripts/android_ebpf/dataset_synthesizer.py` | 分层采样 + 批量调用 LLM 生成 |
| `dataset_synthetic/samples/*.json` | 每个合成样本的完整 JSON |
| `dataset_synthetic/manifest.json` | 数据集元信息（样本数、网格分布、质量报告） |
| `scripts/android_ebpf/ablation_runner.py` | 加载合成数据 → 6 组裁剪 → 跑 MAPLE → 输出结果表 |
| `docs/ablation_results.md` | 消融实验结果报告 |
