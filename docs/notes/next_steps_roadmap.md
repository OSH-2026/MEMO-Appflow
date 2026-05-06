# MEMO-Appflow 下一步详细方案

Date: 2026-05-06

## 总目标

将当前"host 驱动采集 + WSL 侧推理"的实验室原型，演进为**可在 Android 虚拟机内独立闭环运行的完整产品**——从 eBPF 采集、本地推理、到系统动作执行和用户可见的 widget 展示，全程不依赖外部服务端。

---

## 1. 端侧全闭环：Android 虚拟机内独立运行

### 1.1 现状

```
PC (Python) --adb--> Android (bpftrace/eBPF)  ← 采集在 Android，控制在 PC
WSL (MAPLE C++)                                ← 推理在 WSL，模型文件在 Windows
PC (Python)                                    ← 证据转换在 PC
```

Android 端只有一个展示静态文案的 APK。

### 1.2 目标架构

```
Android 虚拟机内部
┌─────────────────────────────────────────────┐
│  Termux 环境                                  │
│  ├── Python 3 (现有脚本直接迁移)               │
│  │   ├── android_ebpf_collect.py (adb→su本地化)│
│  │   ├── traceprint_to_jsonl.py               │
│  │   └── maple_context_from_ebpf.py           │
│  ├── MAPLE engine (交叉编译 ARM64)             │
│  │   ├── llama.cpp (GGUF 推理)                │
│  │   └── maple_engine (prompt/build/parse)    │
│  └── 本地模型文件 (Qwen3.5-0.8B-Q4_K_M.gguf)  │
│                                                │
│  MEMO APK (重写)                                │
│  ├── 系统动作执行器 (am/pm 命令封装)            │
│  ├── Widget Provider (桌面小组件)               │
│  └── 与 Termux 侧的 IPC 通道                    │
└─────────────────────────────────────────────┘
```

### 1.3 分步实施

**Step 1.1 — Termux 环境准备**

- 安装 Termux（F-Droid 版本，非 Google Play 版本）
- `pkg install python clang cmake make git`
- `pip install` 现有脚本依赖（标准库为主，无外部 C 扩展依赖，可以直接跑）
- 验证：`python android_ebpf_collect.py parse --raw-trace fixtures/sample_trace.tsv ...`

**Step 1.2 — adb 调用本地化**

在 `android_ebpf_collect.py` 中，`adb shell` 和 `adb push` 是唯一需要改的调用。有两种策略：

- **策略 A（推荐）**：写一个极薄的 `ShellProxy` 抽象层，根据环境变量 `MEMO_LOCAL=true` 切换执行路径：
  ```python
  # 原：run([adb, "shell", "su", "-c", cmd])
  # 改：run(["su", "-c", cmd])  # Termux 本地直接执行
  ```
  `adb push` 改为本地 `shutil.copy`，`package_uid_map()` 改为直接读 `/data/system/packages.list`。

- **策略 B**：保留 host 模式不变，在 Termux 侧全用 `parse` 子命令（离线解析已保存的 trace 文件），采集仍用 bpftrace 脚本直接在 shell 执行。

**Step 1.3 — MAPLE engine ARM64 交叉编译**

```bash
# 在 Termux 中
cd llm/maple/llama.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF
cmake --build build -j$(nproc)

cd ../maple_engine
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)
```

注意事项：
- llama.cpp 的 Q4_K_M 量化推理在 ARM64 CPU 上可接受（约 2-5 token/s）
- 模型文件约 600MB，需确认模拟器存储空间
- 如果性能不足，可降级为 Qwen3.5-0.5B 或更小的量化模型

**Step 1.4 — 自动启动编排**

写一个 Termux 侧的启动脚本 `memo_launcher.sh`：
```bash
#!/bin/bash
# 1. 加载 bpftrace 程序（需要 root/su）
su -c "bpftrace memo_appflow_trace.bt > /data/local/tmp/raw_trace.tsv &"
# 2. 启动 Python 处理管道（定时轮询 raw trace）
python android_ebpf_collect.py parse ...
# 3. 启动 MAPLE 推理（作为 daemon 进程）
./maple_demo --model models/Qwen3.5-0.8B-Q4_K_M.gguf --scenarios ...
```

---

## 2. 推理输出 → 系统动作 + Widget 展示

### 2.1 现状

MAPLE 当前输出：
```
Stage 1: Android Service IPC (90%)
Stage 2: This user will use App 110.
```

`App 110` 是一个资源类别标签，**不对应任何真实 app**。

### 2.2 需要新加的模块

#### 2.2.1 决策映射层（Decision Mapper）

新建 `decision_mapper_from_maple.py`，将 MAPLE 的两阶段输出转换为具体系统动作：

| MAPLE 输出类别 | 系统动作 |
|---|---|
| Android Service IPC (110) | `am force-stop` 非活跃进程，减少 Binder 竞争 |
| Display Composition (115) | 调整 SurfaceFlinger 相关的 cgroup 优先级 |
| Native Runtime Loading (120) | 预加载 so 到 page cache (`cat *.so > /dev/null`) |
| Memory Management (220) | 触发 `echo 3 > /proc/sys/vm/drop_caches` 或调整 lmkd 阈值 |
| Cache/File Cache (200) | 预读常用文件，降低后续冷启动延迟 |
| Media Codec (260) | 预加载 media.codec 服务 |

实现要点：
- 封装为 `execute_action(action: dict) -> bool`，通过 `su -c` 执行
- 每个动作带 **安全检查**（如 `drop_caches` 仅在 `memory_pressure == "normal"` 时允许）
- 动作执行后记录到 `action_log.jsonl`，用于后续消融实验

#### 2.2.2 App 预测反向映射

目前 `installed_apps` 存的是类别 ID，需要增加一条**反向链路**：当模型输出资源类别后，从该类别推导出用户可能打开的**真实 app**。

方案：扩展 `maple_context_from_ebpf.py`，增加一个配置表 `CATEGORY_TO_REAL_APPS`：

```python
CATEGORY_TO_REAL_APPS = {
    "Android Service IPC": ["com.android.settings", "com.android.launcher3"],
    "Display Composition": ["com.google.android.apps.maps"],
    "Media Codec": ["com.google.android.youtube", "com.android.camera2"],
    "Navigation/Location": ["com.google.android.apps.maps"],
    "Camera Service": ["com.android.camera2"],
    "App Process Runtime": ["com.android.chrome", "com.android.settings"],
}
```

当 Stage 2 输出 `App 110`（Android Service IPC）时，决策映射层从中选出真实包名，例如 `com.android.settings`，然后通过 `am start` 或 `monkey` 预热启动。

#### 2.2.3 Android Widget（桌面小组件）

修改现有 APK，新增 `AppWidgetProvider`：

**文件清单：**
- `app/src/main/java/com/memoos/NextAppWidgetProvider.kt` — Widget Provider
- `app/src/main/res/xml/next_app_widget_info.xml` — Widget 配置（最小 4x1 格子）
- `app/src/main/res/layout/next_app_widget.xml` — Widget 布局

**Widget 展示内容：**
```
┌──────────────────────────────┐
│  MEMO 预测                      │
│  ╔══════════════════╗        │
│  ║  设置             ║  ← 预测的 app 名 + 图标
│  ║  com.android...   ║
│  ╚══════════════════╝        │
│  基于 eBPF 系统证据            │
│  内存: 正常 | IPC: 活跃        │
│  更新于 14:32                 │
└──────────────────────────────┘
```

**数据流通路：**
```
Termux Python 进程
  → 生成 prediction_result.json（含预测 app + 置信度 + 系统状态摘要）
  → 写入 APK 可读的路径（/sdcard/memo/prediction.json 或通过 ContentProvider）
  → Widget onUpdate() 读取并渲染
```

如果不方便做实时 IPC，可以先实现一个简化版：Widget 每 30 秒从 `/sdcard/memo/` 读最新预测结果，显示 Top-1 预测 app。

### 2.3 分步实施

| 步骤 | 内容 | 预计新增/修改文件 |
|---|---|---|
| 2.1 | 写 `decision_mapper_from_maple.py`，实现类别→系统动作映射 | 1 个新文件 |
| 2.2 | 扩展 `maple_context_from_ebpf.py`，增加 `CATEGORY_TO_REAL_APPS` 反向映射 | 修改 1 处 |
| 2.3 | 新增 `NextAppWidgetProvider.kt` + widget 布局 XML | 3 个新文件 |
| 2.4 | 修改 `MainActivity.kt`，增加简单的预测结果展示（开发阶段不用 widget 迭代） | 修改 1 处 |
| 2.5 | 端到端验证：触发采集 → MAPLE 推理 → 系统动作执行 → Widget 更新 | 验证脚本 |

---

## 3.（选做）LLM 合成数据集 + 消融实验

### 3.1 合成数据集

当前只有真实 emulator 采集的 17849 条记录（单次运行）。消融实验需要**可控的、有标签的**数据集。

**方案：**

1. **写一个 dataset_synthesizer.py**，用 MAPLE 自身的模型（Qwen 0.8B）反向生成多样化的 eBPF trace：
   - 给出场景描述 prompt（"用户正在用相机拍照，同时后台播放音乐"）
   - 模型输出该场景下应有的系统事件分布（Binder 比例、文件访问类别分布、内存压力等级）
   - 根据分布参数化生成合成 JSONL 事件流

2. **合成数据集应覆盖的场景矩阵：**

| 场景 | 预期 Binder 占比 | 预期文件访问类别 | 预期内存压力 |
|---|---|---|---|
| 纯桌面滑动 | 低 (~20%) | dex/so 占比高 | normal |
| 相机拍照 | 高 (~60%) | camera 相关 | normal |
| 地图导航 | 中 (~40%) | maps/location | normal |
| 大型游戏下载 | 低 (~10%) | cache、database | elevated |
| 多任务切换 | 高 (~70%) | 混合 | elevated 到 critical |

每种场景生成 10 个变体，每个变体 5000-20000 条事件，总量约 50-100 个标注样本。

### 3.2 消融实验设计

对比以下配置的**准确率**（预测类别是否匹配真实资源需求）和**性能**（推理延迟、内存占用）：

| 实验编号 | 配置 | 说明 |
|---|---|---|
| A（全量） | eBPF 全证据 + MAPLE 两阶段 | 当前完整方案 |
| B | 仅 Binder 证据 | 去掉文件访问、内存等 |
| C | 仅文件访问证据 | 去掉 Binder、内存等 |
| D | 仅内存压力证据 | 去掉所有 IPC/文件证据 |
| E | 无 eBPF，仅 app 序列 | 传统 app-sequence baseline |
| F | 随机 baseline | 从候选类别随机选 |

**评估指标：**
- Stage 1 类别预测准确率（Top-1 / Top-3）
- Stage 2 ID 预测准确率（只对消融实验的合成数据有 ground truth）
- 推理延迟（端到端，从 eBPF 采集截止到结果输出）
- 模型内存占用（RSS）

**消融实验脚本：** `ablation_runner.py`，自动遍历配置 A-F，运行 5 次取平均，输出 Markdown 表格。

### 3.3 分步实施

| 步骤 | 内容 |
|---|---|
| 3.1 | 写 `dataset_synthesizer.py`，用 Qwen 反向生成标注场景 |
| 3.2 | 生成 50-100 个标注样本 |
| 3.3 | 写 `ablation_runner.py`，批量跑 MAPLE 并收集结果 |
| 3.4 | 输出实验结果表格和分析报告 |

---

## 4. 优化 maple_context_from_ebpf.py

### 4.1 当前问题

| 问题 | 位置 | 影响 |
|---|---|---|
| 硬编码字典散落在模块顶部，扩展场景需要改代码 | 第 19-91 行 | 可维护性差 |
| `build_summary` 和 `build_maple_scenario` 重复遍历 `events` 列表 | 第 158、195 行 | 2x 遍历开销 |
| `event_category()` 每次查 dict，字符串拼接多 | 第 104 行 | 对 1.7 万条事件的开销可忽略，但万条以上需注意 |
| `NOISE_PROCESSES` 是 set of str，但 `process_name()` 每次都 `str()` 转换 | 第 82、114 行 | 微小开销 |
| `prediction_time()` 遍历所有 events 找第一个有时间戳的，失败 fallback 到 `datetime.now()` | 第 150 行 | 无时间戳时无意义 |
| `installed_apps` 的 key 是 MAPLE category name（含空格），JSON 序列化/反序列化不够紧凑 | 第 207 行 | 不影响功能，但不够干净 |
| 仅支持单 window 模式，不支持窗口化场景 | 整个文件 | 无法按窗口拆分多个 scenario |

### 4.2 优化方案

**4.2.1 配置外部化**

将 `CATEGORY_LABELS`、`CATEGORY_IDS`、`PROCESS_HINTS`、`PROCESS_CATEGORY_RULES`、`NOISE_PROCESSES` 提取为独立的 JSON 配置文件 `scripts/android_ebpf/configs/maple_bridge_config.json`：

```json
{
  "category_labels": { ... },
  "category_ids": { ... },
  "process_hints": { ... },
  "process_category_rules": { ... },
  "noise_processes": [ ... ],
  "category_to_real_apps": { ... }
}
```

Python 侧改为 `load_config(path)` 一次性加载，方便不同场景切换不同配置。

**4.2.2 窗口化支持**

新增 `--mode windowed`，输入 `llm_context_windows.jsonl`（由 `android_ebpf_collect.py parse` 生成），按窗口分别生成 MAPLE scenario，输出为 `scenarios` 数组而非单个 scenario。

这样 MAPLE 可以逐窗口推理用户行为变化，而非看一个巨大的全局摘要。

**4.2.3 单次遍历优化**

将 `build_summary` 和 `build_maple_scenario` 合并为一个 `build_maple_scenario_from_events()`，一次遍历同时完成统计和构建：

```python
def build_maple_scenario_from_events(
    events: list[dict[str, Any]],
    *,
    scenario_id: str,
    description: str,
    config: dict[str, Any],
) -> dict[str, Any]:
    # 单次遍历同时完成 event_counts, category_counts, process_counts, path_category_counts
    ...
```

**4.2.4 增加反向映射**

如 2.2.2 节所述，增加 `CATEGORY_TO_REAL_APPS` 配置，让 MAPLE scenario 的 `context` 中多一个字段 `predicted_real_apps`，直接从类别 ID 推导真实包名候选。

**4.2.5 加单测**

扩展现有 `test_maple_context_from_ebpf.py`：
- 测试窗口化模式下多个 scenario 的输出
- 测试空输入、单事件、极端分布（100% Binder 等）
- 测试配置文件缺失时的 fallback 行为

### 4.3 分步实施

| 步骤 | 内容 | 修改文件 |
|---|---|---|
| 4.1 | 创建 `configs/maple_bridge_config.json`，迁移硬编码字典 | 1 个新文件 |
| 4.2 | 修改 `maple_context_from_ebpf.py`，从配置文件加载 | 修改 ~30 行 |
| 4.3 | 合并遍历逻辑，单次统计 | 修改 `build_summary` + `build_maple_scenario` |
| 4.4 | 新增 `--mode windowed` 支持 | 修改 `main()` + 新增 `build_windowed_scenarios()` |
| 4.5 | 新增 `--config` 参数和反向 app 映射 | 修改 `build_parser()` + context 构建 |
| 4.6 | 扩展单元测试 | 修改 `tests/test_maple_context_from_ebpf.py` |

---

## 5. 整体时间线建议

```
Week 1-2:  1.1-1.4  Termux 环境 + MAPLE ARM64 编译 + adb 本地化
Week 3:    4.1-4.6  maple_context_from_ebpf.py 优化
Week 4-5:  2.1-2.5  决策映射层 + Widget + 端到端闭环
Week 6-7:  3.1-3.4  （选做）合成数据集 + 消融实验
Week 8:    整体联调 + 文档 + 演示录制
```

## 6. 风险和缓解

| 风险 | 缓解 |
|---|---|
| Termux 中 bpftrace 无法附着 tracepoint | 用编译好的 eBPF C 程序（`memo_traceprint.bpf.c`）+ `bpftool` 作为 fallback |
| ARM64 推理太慢（< 1 token/s） | 换更小模型（Qwen3.5-0.5B Q2_K），或降低上下文窗口 |
| 模拟器存储空间不够放模型 | 使用 mmap 模式（llama.cpp 默认），无需完整加载到 RAM |
| 系统动作需要 root 权限，模拟器可能无 root | 确保使用 userdebug/eng build 的 AVD，或限制动作范围为无 root 操作（只做 `am start`、`pm` 等） |
