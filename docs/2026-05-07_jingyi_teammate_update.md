# Jingyi Guo 工作汇报：端侧 raw eBPF + MAPLE 产品闭环

GitHub 仓库：<https://github.com/OSH-2026/MEMO-Appflow>

最新验证日期：2026-05-28

## 一句话总结

Jingyi Guo 负责把 MEMO-Appflow 从 host 脚本原型推进成 Android 设备内部可运行的产品闭环，并在 rooted Pixel 5 上用真实用户操作产生的 raw eBPF 数据跑通了 MAPLE 推理、Top-3 真实应用推荐、系统动作执行和真实 eBPF 消融实验。

当前链路是：

```text
真实 Android app 使用
-> 设备内 raw eBPF 证据采集
-> 设备内 Kotlin 结构化
-> 设备本地 MAPLE 推理
-> 自适应 Top-3 真实 app 推荐
-> ActionExecutor 系统调度动作
-> Widget / App UI 展示
-> 真实 eBPF 消融实验
```

电脑端只负责安装 APK、推模型/native artifact、拉日志和结果文件，不承担产品逻辑。

## 1. 产品 pipeline 端侧化

### 做了什么

采集、解析、scenario 构造、MAPLE 调用、Top-3 app 映射、动作执行、状态保存、Widget 展示都在 Android app 内部完成。

### 对应文件

| 作用 | 文件 |
| --- | --- |
| 主 pipeline 后台服务 | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` |
| Android 主界面/控制台 | `app/src/main/java/com/memoos/MainActivity.kt` |
| 状态保存 | `app/src/main/java/com/memoos/store/MemoStore.kt` |
| Widget 展示 | `app/src/main/java/com/memoos/widget/MemoWidgetProvider.kt` |
| 耗时统计 | `app/src/main/java/com/memoos/perf/PipelineLatency.kt` |

关键点：MAPLE 没返回前不会发布 Top-3，也不会执行预测驱动的调度动作。当前产品路径没有 quick/fallback 预测。

## 2. raw eBPF 采集

### 做了什么

项目已经去掉产品路径里的 bpftrace，改成 raw eBPF C 程序：

| 作用 | 文件 |
| --- | --- |
| eBPF C tracepoint 程序 | `src/bpf/memo_appflow.bpf.c` |
| eBPF 事件/类型头 | `src/bpf/memo_appflow.h` |
| Android raw eBPF loader | `src/native/memo_libbpf_collector.c` |
| 构建脚本 | `scripts/build_memo_libbpf.sh` |
| APK 内置 collector | `app/src/main/assets/memo_libbpf_collector` |
| APK 内置 BPF object | `app/src/main/assets/memo_appflow.bpf.o` |
| 设备部署器 | `app/src/main/java/com/memoos/ebpf/DeviceCollectorDeployer.kt` |
| 能力探测 | `app/src/main/java/com/memoos/device/EBPFCapabilityProbe.kt` |

设备侧布局：

```text
/data/local/tmp/memo/memo_libbpf_collector
/data/local/tmp/memo/memo_appflow.bpf.o
/data/local/tmp/memo/bpftool
/data/local/tmp/memo/maple_demo
/data/local/tmp/memo/libmaple_engine.so
/data/local/tmp/memo/models/Qwen3.5-0.8B-Q4_K_M.gguf
```

### 设备能力验证

Pixel 5 上 feature-check 结果：

```text
uname_machine=aarch64
uname_release=4.19.278
btf_vmlinux=no
tracefs=/sys/kernel/tracing
sched_process_exec=yes
counter_map=yes
collector=raw_ebpf_counter_map
bpftrace=no
```

这说明当前真机路径不是 bpftrace，而是 raw eBPF object + loader。由于这台手机没有 `/sys/kernel/btf/vmlinux` 和 ring-buffer map type，当前实现采用稳定 tracepoint + counter map + `perf_event_open` attach 的 Android 兼容路径。

## 3. 真实用户操作实验

### 什么叫真实用户操作实验

这里的“真实”不是手写 JSON，也不是 host Python 造 trace。实验流程是：collector 先在 Android 设备内 attach tracepoint，然后启动真实安装的 Android app，并用 Android input 产生真实点击/滑动。eBPF 记录来自内核运行时自然产生的 Binder、openat、sendto、recvfrom、sched、process 等事件。

### 本轮实验环境

| 项 | 值 |
| --- | --- |
| 设备 | rooted Pixel 5 / redfin |
| Android | Android 14 |
| Kernel | Linux 4.19.278 |
| 目标 app | Chrome (`com.android.chrome`) |
| 用户动作 | 启动 Chrome 并执行真实 Android swipe |
| 原始 trace | `/sdcard/MEMO/logs/real_user_1779966570132.trace` |
| 仓库 trace | `docs/real_device_experiments/real_ebpf_ablation/real_user_1779966570132.trace` |

### 真实输出摘要

`latest_real_user_experiment.txt`：

```text
id=scroll_display_usage
title=Real scrolling and display usage
target_package=com.android.chrome
target_label=Chrome
desired_categories=Display Composition, Network IO, Media Codec, App Process Runtime
raw_trace_path=/sdcard/MEMO/logs/real_user_1779966570132.trace
parsed_events=2474
```

`latest_pipeline_latency.json`：

```text
total_ms=44480
foreground_ms=26279
realtime_budget_ms=60000
parsed_events=2474
realtime_status=ok
maple_inference=18201ms
maple_app_mapping=200ms
maple_action_update=912ms
```

## 4. 结构化和 MAPLE

### 做了什么

Android app 内部把 raw eBPF TSV 解析成事件，再聚合成 MAPLE scenario。

| 作用 | 文件 |
| --- | --- |
| raw trace 解析 | `app/src/main/java/com/memoos/ebpf/EBPFTraceParser.kt` |
| 系统状态采集 | `app/src/main/java/com/memoos/state/SystemStateCollector.kt` |
| MAPLE scenario 构造 | `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` |
| MAPLE shell backend | `app/src/main/java/com/memoos/maple/MapleShellBackend.kt` |
| MAPLE 总入口 | `app/src/main/java/com/memoos/maple/MapleInferenceOrchestrator.kt` |
| MAPLE parser 修复 | `llm/maple/maple_engine/src/maple_engine.cpp` |
| demo token 上限修复 | `llm/maple/demo/maple_demo.cpp` |

本轮 scenario 的关键 evidence：

```text
2474 device-side eBPF records
event_type MEMO_BINDER: count=400
event_type MEMO_OPENAT: count=400
event_type MEMO_SENDTO: count=400
event_type MEMO_RECVFROM: count=400
event_type MEMO_SCHED: count=400
event_type MEMO_PROCESS_FORK: count=229
event_type MEMO_PROCESS_EXIT: count=202
event_type MEMO_PROCESS_EXEC: count=112
MAPLE evidence/resource category Network IO: count=720
MAPLE evidence/resource category Android Service IPC: count=360
MAPLE evidence/resource category App Process Runtime: count=180
MAPLE evidence/resource category Display Composition: count=180
```

MAPLE 输出：

```text
backend=shell
predicted_app_id=280
stage1=App Process Runtime (100%)
```

## 5. Top-3 真实 App 推荐

### 做了什么

Top-3 不是进程名、Binder 线程或 SurfaceFlinger。`AppIdMapping` 会扫描设备上真实可启动的 launcher app，再根据 roles、Intent、权限、Android category 和弱文本特征做自适应映射。

对应文件：

```text
app/src/main/java/com/memoos/action/AppIdMapping.kt
```

本轮真实输出：

```text
1. Settings  -> com.android.settings       -> App Process Runtime
2. Chrome    -> com.android.chrome         -> Network IO
3. Messages  -> com.google.android.apps.messaging -> Communication
```

## 6. 系统动作执行层

### 做了什么

MAPLE 输出进入 `ActionExecutor` 后会转成系统动作，而不是只停留在文本预测。

对应文件：

```text
app/src/main/java/com/memoos/action/ActionExecutor.kt
app/src/main/java/com/memoos/device/RootShell.kt
app/src/main/java/com/memoos/device/RootBridgeClient.kt
```

本轮真实 action：

```text
widget_update -> published 3 real app recommendations
latency_policy -> MAPLE prediction ran asynchronously
memory_policy -> normal memory; warm launch budget allowed
thermal_policy -> normal battery thermal state
network_candidate_priority -> UDP sendto/recvfrom evidence prioritizes network-capable apps
network_stats_refresh -> refreshed network stats before recommendation
display_ui_policy -> keep prewarm count low to avoid jank
binder_service_policy -> Binder/system-service evidence kept as MAPLE scheduling context
binder_service_refresh -> queried service manager after high Binder activity
warm_launch -> skipped in non-intrusive background mode
maple_background -> completed
latency_summary -> foreground=25.9s, MAPLE=18.4s, total=44.3s, budget=60.0s
```

这里的“skipped”不是失败，而是产品策略：用户还在用手机时不强行切换可见 app，避免打断用户。

## 7. 真实 raw eBPF 消融实验

### 结果文件

```text
docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json
docs/real_device_experiments/real_ebpf_ablation/real_ebpf_ablation_result_interpretation.md
docs/real_device_experiments/real_ebpf_ablation/latest_maple_scenario.json
docs/real_device_experiments/real_ebpf_ablation/latest_pipeline_latency.json
docs/real_device_experiments/real_ebpf_ablation/memo_pipeline.xml
```

### Metrics 解释

| Metric | 含义 |
| --- | --- |
| Predicted id | MAPLE Stage 2 的 `App <id>` |
| Stage 1 | MAPLE 的资源/系统需求类别 |
| Top-1 app | 映射到真实 Android app 后的第一推荐 |
| Predicted domains | 从 Stage 1 和 Top-3 类别归一化出的调度 domain |
| Top-3 overlap | 当前配置和 full eBPF 的 Top-3 Jaccard overlap |
| Action-domain overlap | 当前配置和 full eBPF 的动作 domain overlap |
| Scheduler alignment | 预测资源 domain 和动作 domain 的匹配度 |
| MAPLE latency | 设备侧 MAPLE 推理耗时 |
| End-to-end latency | MAPLE + app mapping + action planning 总耗时 |

没有 supervised accuracy，因为真实用户操作窗口没有人工标注“唯一正确下一个 app”。强行写 next-app accuracy 会是伪指标。这里更合理的是比较 full eBPF 和各类 ablation 在预测、Top-3、动作和耗时上的差异。

### Full eBPF vs app-sequence baseline

| Metric | App-sequence baseline | Full real raw eBPF | Change |
| --- | ---: | ---: | ---: |
| MAPLE latency | 57.089s | 18.753s | -38.336s, 67.2% faster |
| End-to-end latency | 58.229s | 19.981s | -38.248s, 65.7% faster |
| Stage 1 category | Network IO | App Process Runtime | changed |
| Predicted app id | 245 | 280 | changed |
| Top-1 real app | `com.android.chrome` | `com.android.settings` | changed |

### 每个配置的结果

| Config | Predicted id | Stage 1 | Top-1 app | Predicted domains | MAPLE latency | End-to-end | Stage1 overlap | Top-3 overlap | Action-domain overlap |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `full_real_ebpf` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 18.753s | 19.981s | 1.00 | 1.00 | 1.00 |
| `no_network` | 230 | Android Service IPC | `com.google.android.apps.messaging` | binder_service, display_ui | 17.085s | 17.959s | 0.00 | 0.50 | 0.88 |
| `no_camera_media` | 245 | Network IO | `com.android.chrome` | network, binder_service | 19.854s | 20.920s | 0.00 | 1.00 | 1.00 |
| `no_display_ui` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 18.210s | 19.341s | 1.00 | 1.00 | 0.88 |
| `no_binder_service` | 280 | App Process Runtime | `com.android.settings` | network, display_ui | 18.621s | 19.319s | 1.00 | 0.50 | 0.88 |
| `no_memory` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 18.534s | 19.674s | 1.00 | 1.00 | 1.00 |
| `counters_only` | 280 | App Process Runtime | `com.android.settings` | network, binder_service | 23.110s | 24.255s | 1.00 | 1.00 | 1.00 |
| `app_sequence_baseline` | 245 | Network IO | `com.android.chrome` | network, binder_service | 57.089s | 58.229s | 0.00 | 1.00 | 1.00 |

### 结果解读

Network 和 camera/media 证据在这次 scroll/display run 里对最终 app prediction 最关键。删掉它们会改变 predicted app id 和 Top-1 app。

Display/UI 和 Binder/service 不一定改变 Top-1，但会改变 action-domain 或 Top-3 稳定性。因为 MEMO-Appflow 是资源调度产品，不只是 next-app 分类器，所以这些 evidence 仍然重要。

Memory 在本轮更多是安全约束：决定 warm launch 是否激进、是否需要 trim/idle/cache 动作。本轮 memory pressure 正常，所以删掉 memory 没有改变预测。

结论要严谨：不能说 eBPF 永远让所有指标更好。可以说在这次真实 Android scroll/display 场景中，raw eBPF 比浅层 app-sequence baseline 更能支撑资源感知预测和调度，而且 measured MAPLE/end-to-end latency 也更低。

## 8. 验证

APK 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

真实用户实验触发：

```powershell
adb shell am start -S -n com.memoos/.MainActivity --es memo_action com.memoos.action.REAL_EXPERIMENT_SCROLL
```

真实 eBPF 消融触发：

```powershell
adb shell am start -S -n com.memoos/.MainActivity --es memo_action com.memoos.action.REAL_EBPF_ABLATION_LATEST
```

本轮 build 成功，真机 raw eBPF 实验成功，MAPLE 推理成功，Top-3 推荐成功，ActionExecutor 成功，消融 JSON 成功生成并已拉回仓库。

## 9. 真实用户 App 压力 A/B 实验

前面的 `latest_pipeline_latency.json` 只能说明 MEMO 自己的采集、解析、MAPLE 和 action pipeline 跑了多久。这个指标重要，但不能直接证明手机性能变好。真正的产品问题是：用户使用其他 app 时，MEMO 开启后手机整体压力有没有下降。

所以新增了一个单独实验：

```text
用户使用真实 app
-> MEMO off baseline
vs
用户使用真实 app
-> raw eBPF -> MAPLE -> ActionExecutor
-> MEMO on
```

对应代码：

```text
app/src/main/java/com/memoos/perf/PressureExperimentRunner.kt
app/src/main/java/com/memoos/perf/DevicePressureMetrics.kt
app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt
app/src/main/java/com/memoos/MainActivity.kt
```

App 里新增按钮：

```text
Run App Pressure A/B Test
```

本次 rooted Pixel 5 真实结果文件：

```text
docs/real_device_experiments/user_app_pressure/latest_pressure_experiment.json
docs/real_device_experiments/user_app_pressure/README.md
```

实验环境：

| 项 | 值 |
| --- | --- |
| 设备 | rooted Pixel 5 / redfin |
| Android | 14 |
| Kernel | Linux 4.19.278 aarch64 |
| workloads | Chrome, Magisk, Tencent Meeting |
| conditions | normal_recent_usage, crowded_cached_apps |
| A/B 对照数 | 6 |

指标解释：

| 指标 | 含义 |
| --- | --- |
| launch TotalTime | `am start -W` 的目标 app 启动耗时 |
| wait time | Android 等待 app 启动完成的时间 |
| CPU busy | workload 窗口内 CPU busy 占比 |
| iowait | workload 窗口内 IO wait 占比 |
| MemAvailable drop | workload 后可用内存下降量 |
| reclaim | `pgscan_direct + pgscan_kswapd` 的增量 |
| jank rate | `dumpsys gfxinfo` 的 janky frame rate |
| pressure score | 内存下降、reclaim、PSI、CPU、iowait、温度、UDP error 的综合压力分数 |

总体结果：

| 指标 | MEMO-on 相对 baseline |
| --- | ---: |
| app 启动 TotalTime | +0.15% |
| app WaitTime | +0.25% |
| 综合压力分数 | +35.41% |
| CPU busy | -0.53% |
| iowait | -2.79% |
| MemAvailable 下降量 | +62.10% |
| reclaim | +56.08% |

解读：

当前版本不能说“全面提升手机性能”。更严谨的说法是：

```text
MEMO 在这台 Pixel 5 上对启动耗时几乎持平；
对内存下降和 reclaim 有明显改善；
在 crowded_cached_apps 场景下更容易降低综合压力和 jank；
但后台 eBPF + MAPLE + action 仍带来 CPU/iowait 成本。
```

这也说明后续优化重点不是再证明 pipeline 能跑，而是降低后台推理/调度开销，并让系统只在真的有压力时做更激进的动作。
