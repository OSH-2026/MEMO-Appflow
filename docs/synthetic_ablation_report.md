# 合成数据 + 消融实验：完整 Pipeline 与结果报告

Date: 2026-05-06

---

## 0. 本次涉及的文件

### 新增文件

| 文件 | 说明 |
|---|---|
| `scripts/android_ebpf/dataset_synthesizer.py` | 合成数据生成器。7 维网格随机采样 + DeepSeek API 高并发生成 |
| `scripts/android_ebpf/ablation_runner.py` | 消融实验跑分器。支持 `--local` (CPU MAPLE) 和 API 两种推理路径，15 组消融配置，`--configs` 增量运行 |
| `scripts/android_ebpf/ablation_batch.cpp` | C++ 批量推理（备用方案，当前 pipeline 未使用，通过 MAPLE C API 完成推理） |
| `docs/synthetic_ablation_report.md` | 本文档 |

### 修改文件

| 文件 | 改动 | 原因 |
|---|---|---|
| `llm/maple/maple_engine/src/prompt_builder.cpp` | 示例泛化 + evidence 模式展示候选类别列表 | 第 4.1 节详述 |
| `llm/maple/maple_engine/src/result_parser.cpp` | 新增 ID→名称反向映射 + 宽松正则解析 | 第 4.2 节详述 |
| `llm/maple/maple_engine/include/maple_engine.h` | 新增 `maple_build_prompt_standalone()` 声明 | 第 4.3 节详述 |
| `llm/maple/maple_engine/src/maple_engine.cpp` | 实现 `maple_build_prompt_standalone()` | 第 4.3 节详述 |
| `llm/maple/maple_engine/CMakeLists.txt` | 添加 CUDA linker 支持（CPU 模式下无害）| 此处未改原逻辑，仅增加条件链接 |

### 未修改的文件

`llm/maple/maple_engine/src/llama_backend.cpp` 保持原始状态（`n_gpu_layers = 0`，纯 CPU）。MAPLE engine 的其余文件、Android app 代码、eBPF 脚本均未修改。

### 被 .gitignore 排除

`dataset_synthetic/` 目录和 `docs/ablation_results*.md` 结果文件已加入 `.gitignore`，不纳入版本控制。

---

## 1. Pipeline 总览

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. 合成数据生成                                                       │
│    dataset_synthesizer.py                                            │
│    7维网格随机采样 → DeepSeek API (256并发) → 1000条有标签样本          │
├─────────────────────────────────────────────────────────────────────┤
│ 2. 消融实验                                                          │
│    ablation_runner.py                                                │
│    1000样本 × 15组配置 → MAPLE PromptBuilder 构建 prompt               │
│    ├── 本地路径: Qwen3.5-0.8B + MAPLE engine (CPU, llama.cpp)         │
│    └── API路径:  DeepSeek API (deepseek-v4-flash)                     │
├─────────────────────────────────────────────────────────────────────┤
│ 3. 结果解析                                                          │
│    本地: result_parser.cpp (C++ regex)                                │
│    API:  _parse_app_type_py() (Python 同逻辑)                          │
├─────────────────────────────────────────────────────────────────────┤
│ 4. 指标输出                                                          │
│    Top-1准确率 × 噪声级别分层 × 推理延迟                               │
└─────────────────────────────────────────────────────────────────────┘
```

### 两条推理路径使用完全相同的 prompt

这是本次工作的核心设计决策。`maple_build_prompt_standalone()` 是新增的 C API，它调用 MAPLE 的 `PromptBuilder::build_app_type_prompt()`，与本地 MAPLE engine 内部使用的 prompt 构建函数完全相同。DeepSeek API 路径调用此函数获取 prompt 后发送给远端模型，解析时使用与 `result_parser.cpp` 相同逻辑的 Python 实现 `_parse_app_type_py()`。两条路径的唯一区别是**谁来推理**——同一个 PromptBuilder 构建的同一段 prompt。

---

## 2. 合成数据生成

### 2.1 网格法

7 个离散维度，每个样本独立随机从各维度取值，不枚举全组合：

| 维度 | 值 |
|---|---|
| 情景 | 拍照/录像、导航、游戏、视频播放、桌面滑动、应用切换、休眠唤醒、后台下载 |
| 用户原型 | 重度多任务者、轻量单应用户、视频/娱乐型、工作/工具型 |
| 前端 app | 相机、地图、Chrome、YouTube、微信、设置、Launcher |
| 设备压力 | normal、elevated、critical |
| 后台活动数 | 0、1-2 个后台、3+ 个后台 |
| 时刻 | 早晨通勤、午休、下午办公、晚间娱乐、深夜 |
| 噪声比例 | 低（0-10%）、中（15-25%）、高（35%+） |

### 2.2 Ground Truth 映射

情景维度直接决定 ground truth 的 `stage1_category` 和 `stage1_category_id`：

| 情景 | stage1_category | ID |
|---|---|---|
| 拍照/录像 | Camera Service | 250 |
| 导航 | Navigation/Location | 270 |
| 游戏 | Display Composition | 115 |
| 视频播放 | Media Codec | 260 |
| 桌面滑动 | Display Composition | 115 |
| 应用切换 | Android Service IPC | 110 |
| 休眠唤醒 | App Process Runtime | 280 |
| 后台下载 | Cache/File Cache | 200 |

### 2.3 生成流程

1. Python 随机生成 grid_cell（7 维各取一值）
2. 构建 prompt，内含 grid_cell 参数 + CATEGORY_IDS 表 + 场景特定的 process hints
3. DeepSeek API（256 并发）生成完整的 UserContext JSON
4. Schema 校验：字段完整性、GT 一致性、类别 ID 范围、memory_pressure 匹配
5. 输出到 `dataset_synthetic/samples/*.json` + `manifest.json`

### 2.4 数据集统计

1000 条样本，一次生成耗时 40.5s（256 并发），100% 产出率。

| 指标 | 分布 |
|---|---|
| Ground truth | Display Composition 252, Media Codec 132, Cache/File Cache 128, Navigation/Location 126, App Process Runtime 123, Android Service IPC 123, Camera Service 116 |
| 噪声级别 | 高 345, 中 333, 低 322 |
| 设备压力 | critical 354, normal 331, elevated 315 |

---

## 3. 消融实验设计

### 3.1 四个证据维度

| 维度 | 缩写 | 对应字段 | 含义 |
|---|---|---|---|
| B | Binder | system_evidence 中 Binder 相关行 | Android IPC 通信信号 |
| C | File | system_evidence 中文件访问相关行 | 文件访问模式信号 |
| D | Memory | memory_pressure | 内存压力信号 |
| E | Historical | historical_app_categories + historical_app_ids + prediction_time | 传统 app 序列信号 |

### 3.2 15 种组合

全部 4 个维度的 2⁴ - 1 = 15 种组合：

| ID | 配置 | B | C | D | E |
|---|---|---|---|---|---|
| A | 全量 eBPF | ✓ | ✓ | ✓ | ✓ |
| B | 仅 Binder | ✓ | | | |
| C | 仅文件 | | ✓ | | |
| D | 仅内存 | | | ✓ | |
| E | 仅 app 序列 | | | | ✓ |
| EB | E + Binder | ✓ | | | ✓ |
| EC | E + 文件 | | ✓ | | ✓ |
| ED | E + 内存 | | | ✓ | ✓ |
| BC | Binder + 文件 | ✓ | ✓ | | |
| BD | Binder + 内存 | ✓ | | ✓ | |
| CD | 文件 + 内存 | | ✓ | ✓ | |
| EBC | E + Binder + 文件 | ✓ | ✓ | | ✓ |
| EBD | E + Binder + 内存 | ✓ | | ✓ | ✓ |
| ECD | E + 文件 + 内存 | | ✓ | ✓ | ✓ |
| BCD | Binder + 文件 + 内存 | ✓ | ✓ | ✓ | |
| F | 随机 baseline | | | | |

每组独立裁剪 `system_evidence`、`points_of_interest`、`historical_app_*`、`memory_pressure`、`prediction_time` 字段。

---

## 4. 对 MAPLE 引擎的修改

### 4.1 prompt_builder.cpp — 示例偏差修复（两次修改）

**问题**：`build_app_type_prompt()` 硬编码了 `Example format: Android Service IPC (70%), Display Composition (20%), Native Runtime Loading (10%)`。Qwen3.5-0.8B 小模型将示例中的具体类别名当作推荐答案，几乎所有预测都输出 `Android Service IPC`。

**修改 1**：将示例替换为占位符，避免模型复制示例中的类别名。

```diff
- Example format: Android Service IPC (70%), Display Composition (20%), Native Runtime Loading (10%)
+ Example format (these are placeholder names, do NOT output them): ExampleCategoryA (70%), ExampleCategoryB (20%), ExampleCategoryC (10%)
```

同时添加了明确指令：`Look at the evidence counts carefully. The category with the highest event count in the Low-level eBPF evidence is the dominant one.`

**问题 2**：evidence-driven 模式下，`append_context()` 的 `include_installed_apps` 参数为 `false`。这意味着模型看不到合法的候选类别列表（`installed_apps`），只能凭记忆输出类别名。0.8B 模型经常输出不存在的类别名（如 `Memory`、`MEMO_BINDER`），导致后续正则解析失败。

**修改 2**：无论 evidence-driven 与否，都展示候选类别列表。

```diff
- const bool include_id_context = !evidence_driven;
- append_context(oss, ctx, include_id_context, include_id_context);
+ // Always show candidate categories so the model knows exact names to pick
+ append_context(oss, ctx, /*include_app_ids=*/true, /*include_installed_apps=*/true);
```

### 4.2 result_parser.cpp — 解析容错（新增逻辑）

**问题**：0.8B 模型经常输出不符合 `Name (XX%)` 正则的格式：

- `Android Service IPC (110)` — 把 category ID 放在括号里
- `Cache/File Cache (1800 records)` — 把 event count 放在括号里
- `280 (75%)` — 输出 ID 而非名称
- 纯类别名，无括号

原有的多级正则匹配都要求 `%` 或 `app/category` 关键词，大量合法输出被丢弃（空字符串）。

**修改**：新增 Pattern 1（优先匹配），接受 `Name (number)` / `Name (number%)` / `Name (number records)` 三种格式。同时新增 ID→名称反向映射表，当模型输出 ID 时自动还原为类别名。最后添加纯文本子串匹配作为兜底。

```cpp
// Pattern 1: "Name (number)" or "Name (number%)" or "Name (number records)"
std::regex re(R"(\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,4})\s*\(\s*(\d+)\s*(?:%|records)?\s*\))");

// Pattern 2: "Number (XX%)" → reverse ID lookup  
std::regex re(R"(^\s*(\d+)\s*\(\s*(\d+(?:\.\d+)?)\s*%\s*\)\s*$)");

// Pattern 4 (fallback): substring match against known categories
static const std::map<int, std::string> ID_TO_CATEGORY = { ... };
```

### 4.3 maple_engine.h / maple_engine.cpp — 新增 C API

新增 `maple_build_prompt_standalone()` 函数，允许外部调用者（如 Python 脚本）使用 MAPLE 的 PromptBuilder 构建 prompt，无需加载模型。这是 DeepSeek API 路径与本地路径使用相同 prompt 的关键。

```c
int maple_build_prompt_standalone(const char* context_json,
                                  char* out_buf, size_t out_buf_size);
```

### 4.4 llama_backend.cpp — 保持 CPU 模式

`n_gpu_layers = 0`，纯 CPU 推理，无需 CUDA 依赖。

---

## 5. 实验结果

### 5.1 测试模型

| 模型 | 推理方式 | 说明 |
|---|---|---|
| **Qwen3.5-0.8B (Q4_K_M)** | 本地 CPU (llama.cpp, 8线程) | 532MB, Gated DeltaNet, 256K context |
| **DeepSeek V4 Flash** | API (`deepseek-chat` 接口) | 远端大模型, 256 并发 |

### 5.2 1000 条消融结果

使用**相同的 MAPLE PromptBuilder 构建的 prompt**：

| 组 | 配置 | DeepSeek V4 Flash | Qwen3.5-0.8B CPU | Δ |
|:---|---:|---:|---:|---:|
| **A** | 全量 eBPF | **88.8%** | **63.0%** | +25.8pp |
| **BC** | Binder + 文件 | **91.0%** | 52.8% | +38.2pp |
| **BCD** | Binder + 文件 + 内存 | 88.1% | 54.3% | +33.8pp |
| **EBC** | E + Binder + 文件 | 85.6% | 60.9% | +24.7pp |
| E | 仅 app 序列 | 51.1% | 30.1% | +21.0pp |
| ED | E + 内存 | 50.0% | 26.8% | +23.2pp |
| EC | E + 文件 | 28.1% | 35.9% | −7.8pp |
| ECD | E + 文件 + 内存 | 33.1% | 34.0% | −0.9pp |
| CD | 文件 + 内存 | 21.9% | 19.5% | +2.4pp |
| C | 仅文件 | 19.9% | 21.6% | −1.7pp |
| B | 仅 Binder | 17.1% | 27.4% | −10.3pp |
| EB | E + Binder | 16.7% | 27.3% | −10.6pp |
| EBD | E + Binder + 内存 | 17.2% | 26.5% | −9.3pp |
| BD | Binder + 内存 | 15.3% | 27.1% | −11.8pp |
| D | 仅内存 | 12.1% | 12.9% | −0.8pp |
| F | 随机 | 5.6% | 4.8% | +0.8pp |

### 5.3 核心发现

**1. Binder + File = 全量。** BC (91.0%) 和 BCD (88.1%) 在 DeepSeek 上不逊于 A (88.8%)，说明 Binder 和 File 证据的交叉信息是决定性信号，E（历史序列）和 D（内存压力）的边际贡献几乎为零。

**2. Binder 单独用有毒。** 所有含 Binder 不含 File 的组合（B/EB/BD/EBD）在 DeepSeek 上跌到 15-17%，比纯随机好不了多少。Binder 事件 70%+ 指向 "Android Service IPC"，大模型被这个强偏置带偏，覆盖了其他信号。小模型受影响较小（27%），因为它本来就读不太懂证据。

**3. 大小模型差距集中在多源证据解读。** 不需要读 evidence 的配置（E/F）差距仅 1-21pp；需要从多行自然语言 evidence 中综合推理的配置（A/BC/BCD）差距 26-38pp。这量化了小模型的瓶颈。

**4. 小模型 63% 已远超随机基线（5%）。** Qwen3.5-0.8B 的全量 eBPF 准确率是随机基线的 13 倍，证明了即使是最小的端侧模型也能从 eBPF 系统证据中提取有用信号。

### 5.4 噪声分层分析

DeepSeek V4 Flash 的噪声鲁棒性：
- A 组：低噪声 87.3%、中噪声 89.8%、高噪声 89.3% — 几乎不受噪声影响
- BC 组：93.5% / 91.3% / 88.4% — 轻微退化
- E 组：51.9% / 45.9% / 55.4% — 波动大（无证据支撑的猜测）

### 5.5 耗时

| 阶段 | 耗时 |
|---|---|
| 1000 条合成（DeepSeek API, 256 并发） | 40.5s |
| 1000 条消融 DeepSeek API（8000 次调用） | 23.9s |
| 1000 条消融 Qwen3.5-0.8B CPU（8000 次推理） | ~22 分钟 |

---

## 6. 运行命令

### 合成数据

```bash
python scripts/android_ebpf/dataset_synthesizer.py \
  --api-key <key> --num-samples 1000 --concurrency 256 --seed 42 \
  --out dataset_synthetic
```

### 消融实验（DeepSeek API）

```bash
python scripts/android_ebpf/ablation_runner.py \
  --api-key <key> --dataset dataset_synthetic --concurrency 256 \
  --output docs/ablation_results.md --results-json dataset_synthetic/ablation_results.json
```

### 消融实验（本地 Qwen3.5-0.8B）

```bash
python scripts/android_ebpf/ablation_runner.py \
  --local --model llm/maple/models/Qwen_Qwen3.5-0.8B-Q4_K_M.gguf \
  --dataset dataset_synthetic --n-threads 8 \
  --output docs/ablation_results_local.md --results-json dataset_synthetic/ablation_results_local.json
```

### 增量运行指定配置

```bash
python scripts/android_ebpf/ablation_runner.py \
  --configs "BC,BD,CD,EBC,EBD,ECD,BCD" \
  --api-key <key> --dataset dataset_synthetic
```

---

## 7. MAPLE 引擎构建

```bash
# 1. 下载模型
hf download bartowski/Qwen_Qwen3.5-0.8B-GGUF Qwen_Qwen3.5-0.8B-Q4_K_M.gguf \
  --local-dir llm/maple/models

# 2. 构建 llama.cpp（CPU）
cd llm/maple/llama.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DGGML_CUDA=OFF
cmake --build build -j$(nproc)

# 3. 构建 maple_engine
cd ../maple_engine
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)
```

---

## 8. 设计决策记录

| 决策 | 选择 | 原因 |
|---|---|---|
| 合成方法 | 网格法（7维随机组合）| 同时保证覆盖和多样性 |
| 合成目标格式 | UserContext JSON | 与 MAPLE 输入完全同构，零适配 |
| DeepSeek API 的 prompt | 与本地相同（MAPLE PromptBuilder）| 两组结果可比，消除 prompt 差异混淆 |
| 模型 | Qwen3.5-0.8B Q4_K_M | 最新 Gated DeltaNet 架构，532MB，端侧可部署 |
| 推理后端 | llama.cpp CPU | 无需 CUDA，可移植 |
| 解析策略 | C++ 正则 + 反向 ID 映射 + 子串兜底 | 容忍小模型的不规范输出 |
