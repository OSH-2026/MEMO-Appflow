# 实时 Top-3 与调度动作 9 分钟实验

实验时间：2026-07-01 21:00-21:10  
设备：rooted Pixel 5，arm64-v8a  
负责人：Jingyi Guo

## 实验目的

这次实验验证 MEMO 的实时产品闭环，而不是一次性 host 脚本：

```text
手机端持续采集真实前台 app 序列 + raw eBPF/system evidence
-> 每 3 分钟形成一个实时窗口
-> 当前窗口作为 MAPLE 主输入，bounded EMA memory 只作为历史辅助上下文
-> MAPLE 预测接下来可能使用的 Top-3 app
-> Android 端映射成真实可启动应用
-> 刷新 App/桌面 Widget
-> MAPLE 输出 scheduler_bits
-> ActionExecutor 按 bitmask 执行预先编程好的调度动作
```

实验重点有三点：

- 采集和推理都发生在手机端，host 只负责触发入口和拉取结果。
- MAPLE 推理期间不停止采集；下一段 3 分钟窗口继续收 app/eBPF/system evidence。
- MAPLE 不只输出 Top-3，也输出 `scheduler_bits`，由 Android 端执行对应系统动作。

## 对应代码

| 作用 | 文件 |
| --- | --- |
| 实时模式总控 | `app/src/main/java/com/memoos/realtime/RealtimePreloadController.kt` |
| 3 分钟窗口采集 | `app/src/main/java/com/memoos/realtime/RealtimeWindowRunner.kt` |
| 有界历史 memory | `app/src/main/java/com/memoos/realtime/RealtimeMemoryStore.kt` |
| 9 分钟受控实验 | `app/src/main/java/com/memoos/perf/RealtimeTop3ShiftExperimentRunner.kt` |
| MAPLE scenario 构造 | `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` |
| MAPLE shell 后端与结构化输出解析 | `app/src/main/java/com/memoos/maple/MapleShellBackend.kt` |
| MAPLE C++ demo，包含 scheduler_bits 输出 | `llm/maple/demo/maple_demo.cpp` |
| Top-3 真实 app 映射 | `app/src/main/java/com/memoos/action/AppIdMapping.kt` |
| 调度动作执行 | `app/src/main/java/com/memoos/action/ActionExecutor.kt` |
| 桌面 Widget | `app/src/main/java/com/memoos/widget/MemoWidgetProvider.kt` |
| 状态保存，包含 scheduler_bits | `app/src/main/java/com/memoos/store/MemoStore.kt` |

## 实验设计

实验从 App 前台入口触发：

```powershell
adb shell am start -n com.memoos/.MainActivity --es memo_action com.memoos.action.REALTIME_TOP3_SHIFT_EXPERIMENT
```

本次使用 3 个完整 3 分钟窗口，总长度约 9 分钟。每个窗口自动反复打开手机上真实存在的应用，让最近 app 序列和 eBPF 证据明显偏向不同使用场景。

| 阶段 | 设计目的 | 真实运行窗口 |
| --- | --- | --- |
| 1 | 网络/浏览窗口 | 反复打开 Chrome、QQ、Magisk 等真实应用，产生网络、Binder、进程和显示证据 |
| 2 | 相机/图片窗口 | 反复打开 QQ、Chrome、Tencent Meeting 等应用，产生通信、媒体、显示、服务调用证据 |
| 3 | 媒体/通信窗口 | 反复打开 Chrome、QQ、Tencent Meeting 等应用，产生通信、媒体、网络和 Binder 证据 |

说明：当前 adaptive app scanner 在相机类选择上还不够强，第二阶段没有稳定选择到 Google Camera 作为 scripted app。但每个窗口仍然来自真实应用启动、真实前台切换、真实 eBPF 采集，不是手写 demo 数据。

## 结果文件

完整 JSON：

```text
docs/real_device_experiments/realtime_top3_shift_2026_07_01/latest_realtime_top3_shift_report.json
```

每个窗口的 MAPLE 输入、上下文和原始输出：

```text
docs/real_device_experiments/realtime_top3_shift_2026_07_01/artifacts/
```

关键文件包括：

```text
phase_1_network_scenario_prompt.json
phase_1_network_context.json
phase_1_network_maple_output.txt
phase_2_camera_scenario_prompt.json
phase_2_camera_context.json
phase_2_camera_maple_output.txt
phase_3_media_comm_scenario_prompt.json
phase_3_media_comm_context.json
phase_3_media_comm_maple_output.txt
```

## 核心结果

| 阶段 | raw eBPF 事件 | app 段数 | MAPLE 耗时 | MAPLE App id | Top-3 | scheduler_bits |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| network | 19263 | 52 | 29106 ms | 220 | Messages / Camera / Chrome | `100000000` |
| camera | 19289 | 55 | 37196 ms | 115 | QQ / Messages / Camera | `100000000` |
| media_comm | 19260 | 54 | 23839 ms | 220 | Messages / Camera / QQ | `101010100` |

Top-3 在 3 个窗口之间变化 2 次：

```text
实验开始前：Settings / Messages / Chrome
窗口 1 后：Messages / Camera / Chrome
窗口 2 后：QQ / Messages / Camera
窗口 3 后：Messages / Camera / QQ
```

这说明 Top-3 不是固定写死列表，而是会随最近 3 分钟真实 app 序列、eBPF/system evidence 和 MAPLE 输出刷新。

## 调度动作记录

`scheduler_bits` 是 MAPLE 给 ActionExecutor 的 0/1 动作开关。当前预定义 9 个动作，按位解释如下：

| bit 位置 | action_id | 含义 |
| ---: | --- | --- |
| 0 | `warm_launch_top1` | 预热 Top-1 推荐应用，然后回到 HOME |
| 1 | `warm_launch_top2_if_idle` | 空闲时预热 Top-2 |
| 2 | `trim_memory_low_priority_apps` | 对低优先级候选做内存 trim |
| 3 | `kill_selected_background_package` | 杀掉被选中的低优先级后台包 |
| 4 | `drop_cache_if_critical_memory` | 仅严重内存压力时 drop cache |
| 5 | `refresh_network_stats` | 刷新网络统计 |
| 6 | `refresh_service_manager` | 刷新/查询 service manager |
| 7 | `reduce_prewarm_when_display_busy` | 显示/UI 忙时降低预热强度 |
| 8 | `skip_camera_prewarm_when_thermal_high` | 温度高时跳过 camera/media 预热 |

### 阶段 1：network

MAPLE 输出：

```text
scheduler_bits=100000000
```

实际执行：

```text
widget_update: ok, 39 ms
latency_policy: ok, MAPLE 异步完成，耗时 29.1s
maple_scheduler_plan: ok, 9 个预定义动作中选择 1 个执行
maple_warm_launch_top1: ok, 1715 ms, 预热 Messages 并返回 HOME
```

### 阶段 2：camera

MAPLE 输出：

```text
scheduler_bits=100000000
```

实际执行：

```text
widget_update: ok, 35 ms
latency_policy: ok, MAPLE 异步完成，耗时 37.2s
maple_scheduler_plan: ok, 9 个预定义动作中选择 1 个执行
maple_warm_launch_top1: ok, 1312 ms, 预热 QQ 并返回 HOME
```

### 阶段 3：media_comm

MAPLE 输出：

```text
scheduler_bits=101010100
```

实际执行：

```text
widget_update: ok, 49 ms
latency_policy: ok, MAPLE 异步完成，耗时 23.8s
maple_scheduler_plan: ok, 9 个预定义动作中选择 4 个执行
maple_warm_launch_top1: ok, 1310 ms, 预热 Messages 并返回 HOME
maple_trim_memory: ok, 414 ms, 对 Camera/QQ 等低优先级候选做 memory trim
maple_drop_cache: skipped, 当前内存不是 critical，所以安全策略阻止 drop cache
maple_service_manager_refresh: ok, 304 ms, 刷新 service manager
```

这次实验可以证明：MAPLE 的 bitmask 已经进入 Android 端，并且真实触发了系统调度动作；不是只在报告里写建议。

## MAPLE 输入包含什么

每个 `phase_*_scenario_prompt.json` 里都包含：

- 带时间戳的真实前台 app 序列。
- 按时间窗口对齐并压缩后的 eBPF 信号。
- memory、battery、network、camera/media、display/UI、process/service 等系统状态。
- 最近 3 分钟窗口摘要。
- 有界 EMA memory，作为历史上下文。
- `available_scheduler_actions`，告诉 MAPLE 哪些动作可以用 0/1 bit 控制。

因此 MAPLE 输入不是“只有 eBPF”，而是 app 序列 + eBPF + 系统状态 + 有界历史 memory 的组合。

## 实时性说明

实验运行中观察到：

```text
phase_1 采集结束后，MAPLE 在后台推理，同时 phase_2 collector 继续运行。
phase_2 采集结束后，MAPLE 在后台推理，同时 phase_3 collector 继续运行。
```

也就是说，产品不是“收完 -> 停住 -> 推理 -> 再收”，而是：

```text
窗口 N 采集结束
-> 窗口 N 压缩并提交给单 MAPLE worker
-> 窗口 N+1 继续采集
```

为了避免线程爆炸，实时模式只保留一个采集流、一个 MAPLE worker 和一个 latest pending slot。长期运行时，如果 MAPLE 推理慢于窗口节奏，只保留最新待推理窗口，不无限排队。

## 结论

本次 9 分钟实验已经验证：

- 手机端可以连续产生真实 eBPF 和 app sequence 数据。
- 每 3 分钟窗口能形成结构化 MAPLE 输入。
- MAPLE 能在手机本地完成推理。
- Top-3 推荐会随真实使用窗口变化。
- MAPLE 输出的 `scheduler_bits` 已经进入 Android 产品路径。
- ActionExecutor 真实执行了 Widget 更新、Top-1 warm launch、memory trim、service manager refresh 等动作。

仍需改进：

- adaptive app scanner 对 Camera 类应用的选择需要更稳定。
- 当前小模型的 scheduler bit 输出还比较粗，前两段都只选择 bit0；后续需要继续优化 prompt 和动作语义，让它更细粒度地区分网络、相机、显示和内存压力。
- 后续应补充更长时间自然使用实验，记录功耗、温度和 jank 变化。
