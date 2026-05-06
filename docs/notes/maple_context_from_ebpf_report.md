# maple_context_from_ebpf.py 输入输出分析报告

Date: 2026-05-06

---

## 1. 前置流程：输入数据是怎么来的

```
eBPF C程序 (memo_traceprint.bpf.c)
  → bpf_printk 写入内核 trace ring buffer
    → 输出原始文本行，形如:
      "surfaceflinger-420 [003] ... 230.66: bpf_trace_printk: MEMO_BINDER pid=962 code=5 to_proc=555"
         ↓
    traceprint_to_jsonl.py   （正则解析）
         ↓
    结构化 JSONL  ←── 这就是 maple_context_from_ebpf.py 的输入
```

## 2. 输入：JSONL 文件，每行一条事件

### 2.1 事件格式

`maple_context_from_ebpf.py` 的 `load_jsonl()`（第 94 行）逐行读取 JSON，不限制 schema 版本。兼容两种来源：

| 字段 | 含义 | 示例值 |
|---|---|---|
| `event_type` | 事件类型标识 | `MEMO_BINDER`, `MEMO_OPENAT`, `MEMO_RECLAIM_BEGIN`, `MEMO_RECLAIM_END`, `memory`, `binder` |
| `comm` / `trace_task` / `process` | 进程名（优先级 comm > trace_task > process） | `surfaceflinger`, `cameraserver`, `system_server` |
| `pid` | 进程 ID | `962` |
| `timestamp_s` / `wall_time` | 时间戳 | `230.666397` |
| `path` | 文件路径（仅 OPENAT 事件有） | `/system/lib64/libEGL.so` |
| `evidence_category` | 由 traceprint_to_jsonl.py 预分类的路径类型 | `native_library`, `procfs_process_state` |
| `path_category` / `service_category` | 由 android_ebpf_collect.py 生成的更粗粒度分类 | `dex`, `so`, `asset`, `database`, `cache`, `model` |
| `code` | Binder 事务码（仅 BINDER 事件） | `5`, `12`, `0` |
| `to_proc` | Binder 目标进程（仅 BINDER 事件） | `555` |
| `cpu` | CPU 编号 | `3` |

### 2.2 真实输入示例

```json
{"event_type":"MEMO_BINDER","trace_task":"m.android.phone","pid":962,"code":5,"to_proc":555}
{"event_type":"MEMO_OPENAT","comm":"surfaceflinger","path":"/system/lib64/libEGL.so","evidence_category":"native_library"}
{"event_type":"MEMO_RECLAIM_BEGIN","comm":"kswapd0","pid":82}
{"event_type":"MEMO_OPENAT","comm":"system_server","path":"/proc/stat","evidence_category":"procfs_process_state"}
{"event_type":"MEMO_BINDER","trace_task":"binder:555_18","pid":555,"code":0,"to_proc":962}
{"event_type":"MEMO_OPENAT","comm":"app_process","path":"/data/dalvik-cache/arm64/system@framework@boot.art","evidence_category":"other"}
```

当前真实运行规模：**17849 条记录**，其中 MEMO_BINDER 13873 条，MEMO_OPENAT 3976 条。

---

## 3. 处理管线：五层信息提取

### 3.1 第一层：事件类型归类（`event_category`，第 104 行）

对每条事件的 `event_type` 做一级分类：

```python
MEMO_BINDER / binder              → "Android Service IPC"
MEMO_RECLAIM_BEGIN / END / memory → "Memory Management"
```

对 `MEMO_OPENAT`，看 `evidence_category`、`path_category` 或 `service_category` 字段，通过 `CATEGORY_LABELS` 字典做二级分类：

```
native_library (.so /lib64/)           → "Native Runtime Loading"
java_framework_or_classpath (.jar)     → "Framework Loading"
android_property_area (/__properties__)→ "System Property Access"
apex_runtime_asset (/apex/)            → "APEX Runtime Loading"
sysfs_kernel_state (/sys/)             → "Kernel Trace Setup"
procfs_process_state (/proc/)          → "Process State Inspection"
device_or_ipc_node (/dev/)             → "Device/IPC Node Access"
database (.db /databases/)             → "Database"
dex_or_oat (.dex .vdex .oat .art)      → "Dex/OAT Loading"
cache (/cache/ /tmp/)                  → "Cache/File Cache"
config                                 → "Config File Access"
other                                  → "Other File Access"
```

### 3.2 第二层：进程名→行为推断（`process_category_counts`，第 141 行）

对于文件事件，路径分类看不清**行为语义**。于是用 `PROCESS_CATEGORY_RULES` 把进程名映射到行为类别：

```python
surfaceflinger, RenderThread, hwc → Display Composition    （正在合成画面）
lmkd, kswapd0                     → Memory Management      （内存回收守护进程）
cameraserver                       → Camera Service         （相机管线活跃）
mediacodec, c2@, codec            → Media Codec            （音视频编解码中）
input, inputdispatcher             → Input Interaction      （用户正在触摸屏幕）
system_server, servicemanager     → Android System Services （系统服务协调）
zygote, app_process               → App Process Runtime     （应用正在启动/孵化）
maps                               → Navigation/Location    （地图/定位活跃）
```

两种归类方式的结果**累加到同一个 category_counts Counter**，互相补充。

### 3.3 第三层：噪声过滤（`signal_process_counts`，第 122 行）

过滤掉采集工具本身的进程：
```python
cat, grep, head, main, sh, su, tail, toybox    ← 直接丢弃
binder:555_18, binder:962_3                     ← binder 线程前缀也丢弃
```

### 3.4 第四层：统计摘要生成（`build_summary`，第 158 行）

以 `event_type`、`category`、`process_name`、`path_category` 四个维度做 Counter 统计，然后生成自然语言摘要行（最多 10 条）。同时根据 reclaim 事件计数判断内存压力：

```
reclaim_count >= 1  →  "elevated: direct reclaim ... count=N"
reclaim_count == 0  →  "normal: no direct reclaim tracepoint was observed"
```

摘要示例：
```
17849 eBPF records in the observed Android emulator window
event_type MEMO_BINDER: count=13873
event_type MEMO_OPENAT: count=3976
MAPLE evidence/resource category Android Service IPC: count=13873
MAPLE evidence/resource category Display Composition: count=3086
MAPLE evidence/resource category Native Runtime Loading: count=892
process surfaceflinger: count=2129
process app_process: count=1160
process system_server: count=845
process lmkd: count=234
```

### 3.5 第五层：MAPLE 场景组装（`build_maple_scenario`，第 195 行）

取统计结果的 Top-5 值，填进 MAPLE `UserContext`。这是输出侧的内容，详见第 5 节。

---

## 4. 该脚本**没有**消费的数据

以下几种数据不是本脚本处理的，属于旁路或下游：

| 数据 | 生产者 | 消费者 |
|---|---|---|
| `binder_service_segments.jsonl` | `android_ebpf_collect.py parse` | 未消费（仅写入磁盘） |
| `cold_launch_file_profile.jsonl` | 同上 | 未消费 |
| `memory_pressure_segments.jsonl` | 同上 | 未消费 |
| `semantic_evidence_segments.jsonl` | 同上 | 未消费 |
| `llm_context_windows.jsonl` | 同上 | 未消费 |
| MAPLE engine 二进制 | llama.cpp + maple_engine | `maple_demo` CLI / C API |

本脚本的输出（MAPLE scenario JSON）是 MAPLE C++ engine 的**唯一输入通道**。

---

## 5. 输出：MAPLE Scenario JSON

### 5.1 输出文件结构

```json
{
  "schema_version": "memo.maple_scenarios.v1",
  "generated_at": "2026-05-06T14:30:00",
  "scenarios": [
    {
      "id": "ebpf_observed_window",
      "description": "Android emulator workload summarized from eBPF tracepoints",
      "source": "android_ebpf",
      "context": { ... },        ← 核心：直接喂给 MAPLE engine 的 UserContext
      "ebpf_summary": { ... }    ← 完整统计数据（当前 MAPLE engine 不消费，预留）
    }
  ]
}
```

### 5.2 `context` 字段详解

这是 MAPLE engine `parse_user_context()` 解析的唯一入口。C++ 侧会逐字段提取后传给 `PromptBuilder`。

| 字段 | 值来源 | MAPLE Engine 中的用途 |
|---|---|---|
| `historical_app_categories` | Top-5 证据类别名 | Stage 1 prompt：告诉模型当前系统行为特征 |
| `historical_app_ids` | 对应 `CATEGORY_IDS` 的数字 | Stage 2 prompt：候选 ID 池 |
| `prediction_time` | 从首个事件的时间戳提取，失败用 `datetime.now()` | 时间上下文（MAPLE 原接口保留字段） |
| `points_of_interest` | 从 Top-5 活跃进程查 `PROCESS_HINTS` 表 | Stage 1 prompt：告诉模型哪些系统组件在活动 |
| `installed_apps` | `证据类别 → [category_id]` 映射 | Stage 2 prompt：让模型从候选 ID 中挑选一个 |
| `system_evidence` | 统计摘要的自然语言行 | Stage 1 prompt：核心证据输入，模型直接阅读 |
| `memory_pressure` | 从 reclaim 事件推导的字符串 | Stage 1 prompt：内存状态信号 |
| `scheduler_goal` | 硬编码指令 | Stage 1 prompt：给模型的顶层目标设定 |

### 5.3 输出端到端消费流程

```
maple_context_from_ebpf.py 输出 JSON
  ↓
maple_demo.cpp 读取 scenarios JSON
  ↓ 提取 scenarios[0]["context"] 字符串
maple_engine.cpp: parse_user_context(context_json)
  ↓ 解析为 UserContext 结构体
prompt_builder.cpp: build_app_type_prompt(ctx)
  ↓ 检测到 system_evidence 非空 → evidence-driven 模式
  ↓ 把 system_evidence、memory_pressure、scheduler_goal 拼入 prompt
LlamaBackend 推理
  ↓
result_parser.cpp: parse_app_type(raw_output)
  → AppTypeResult (Stage 1: 类别预测)
  ↓
prompt_builder.cpp: build_next_app_prompt(ctx, stage1)
  → 二次推理
  ↓
result_parser.cpp: parse_next_app(raw_output)
  → NextAppResult (Stage 2: ID 预测)
```

### 5.4 Prompt 实际形态

#### Stage 1 (evidence-driven 模式):

```
You are MAPLE's adapter for MEMO Android eBPF evidence. Based on the system
evidence, predict the most likely resource-demand category for the next
scheduling decision.
Be concise. Output ONLY the category name and percentage.
Example format: Android Service IPC (70%), Display Composition (20%), Native
Runtime Loading (10%)
The percentage must be from 0% to 100%. Candidate app IDs are labels, not
confidence values.
Do not include reasoning, markdown, XML tags, or <think> blocks.

Context:
- Recent evidence categories: Android Service IPC, Display Composition,
  App Process Runtime, Native Runtime Loading, and Media Codec.
- System/user activity hints: display compositor activity,
  app process startup path, camera service activity,
  NDK MediaCodec_ process activity, and RenderThread process activity.
- Time: Monday 02:30PM.
- eBPF memory pressure: normal: no direct reclaim tracepoint was observed.
- Low-level eBPF evidence:
  * 17849 eBPF records in the observed Android emulator window
  * event_type MEMO_BINDER: count=13873
  * event_type MEMO_OPENAT: count=3976
  * MAPLE evidence/resource category Android Service IPC: count=13873
  * MAPLE evidence/resource category Display Composition: count=3086
  * process surfaceflinger: count=2129
  * process app_process: count=1160
- Scheduler goal: Bridge MEMO eBPF evidence into MAPLE. Infer near-future
  resource demand and memory scheduling; treat app sequence statistics only
  as a weak baseline.

Prediction:
Based on the global information, the next app will be a
```

#### Stage 2 (evidence-driven 模式):

```
You are MAPLE's ID selector for MEMO Android eBPF evidence. Choose exactly
one MAPLE candidate ID for the resource-demand category.
These IDs are bridge labels for evidence/resource classes, not raw Android
package names.
You MUST output exactly one sentence in this format: This user will use App <id>.
The <id> MUST be one numeric ID from the candidate list below.
Return only that sentence. Do not include reasoning, markdown, XML tags, or
<think> blocks.
Example format: This user will use App 4.

Predicted app category: Android Service IPC (90%).

Additional context:
- Recent evidence categories: Android Service IPC, Display Composition,
  App Process Runtime, Native Runtime Loading, and Media Codec.
- Candidate/recent app IDs: 110, 115, 280, 120, and 260.
- MAPLE candidate IDs by evidence/resource category:
  * Android Service IPC: 110
  * Display Composition: 115
  * App Process Runtime: 280
  * Native Runtime Loading: 120
  * Media Codec: 260
- Time: Monday 02:30PM.
- System/user activity hints: display compositor activity,
  app process startup path, camera service activity,
  NDK MediaCodec_ process activity, and RenderThread process activity.
- eBPF memory pressure: normal: no direct reclaim tracepoint was observed.
- Low-level eBPF evidence:
  * 17849 eBPF records in the observed Android emulator window
  * event_type MEMO_BINDER: count=13873
  * ...

Prediction:
```

---

## 6. 总结

`maple_context_from_ebpf.py` 不碰原始 trace，不碰模型推理。它的角色是**压缩翻译器**：

| | 输入侧 | 输出侧 |
|---|---|---|
| 数据量 | 17849 条结构化事件 (~ 2MB JSONL) | 1 个 MAPLE scenario (~ 2KB JSON) |
| 压缩比 | — | ~1000:1 |
| 信息层次 | 零散的 pid/comm/path/code | 五大证据类别排行 + 活跃进程提示 + 内存压力等级 + 自然语言摘要 |
| 语义转换 | 内核事件类型 + 文件路径字符串 | MAPLE category ID 数字标签 |
| 下游消费 | — | MAPLE C++ engine → prompt → LlamaBackend → 两阶段推理 |
