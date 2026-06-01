# 2026-06-01 真机产品闭环验证

负责人：Jingyi Guo

协作模型部分：Chengyu Fan 的 MAPLE 组件作为唯一预测入口。

## 今天做了什么

这次工作的核心不是再写 host 脚本，而是把 MEMO-Appflow 当成一个真实 Android 产品来验证：

```text
用户点击 MEMO App
-> 手机本地采集 raw eBPF
-> 手机本地整理 evidence / scenario
-> 手机本地调用 MAPLE
-> 映射成真实可启动 Top-3 应用
-> 执行 ActionExecutor 调度动作
-> App 和 Widget 展示结果
-> App 内展示真实使用报告、消融报告、性能 A/B 报告
```

## 实验环境

本页所有真实设备实验都在同一套端侧环境里完成，避免“电脑脚本跑出来、手机只是显示结果”的问题。

| 项目 | 设置 |
| --- | --- |
| 设备 | rooted Pixel 5 |
| 运行位置 | 手机本地 Android App 内触发；root 命令、raw eBPF collector、Kotlin 解析、MAPLE shell、ActionExecutor 都在手机侧执行 |
| Host 作用 | 安装 APK、查看日志、拉取结果文件；不参与产品逻辑、不生成预测输入 |
| eBPF 采集 | raw eBPF/libbpf collector 输出 MEMO TSV trace；不再依赖 bpftrace |
| 模型入口 | Chengyu Fan 的 MAPLE shell/backend，作为唯一预测入口 |
| 结果归档 | 手机侧写入 `/sdcard/MEMO/...`，再拉取到 `docs/real_device_experiments/...` |

## 关键代码对应关系

| 模块 | 文件 |
| --- | --- |
| 主界面、Top-3、报告入口、耗时展示 | `app/src/main/java/com/memoos/MainActivity.kt` |
| eBPF 详情页，展示真实 raw trace 前 N 条 | `app/src/main/java/com/memoos/EvidenceActivity.kt` |
| 可读报告页 | `app/src/main/java/com/memoos/ReportActivity.kt` |
| 后台 service 入口 | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` |
| 100 次真实 app 使用实验 | `app/src/main/java/com/memoos/perf/RealUsageSessionRunner.kt` |
| 自由体验真实 app 使用实验 | `app/src/main/java/com/memoos/perf/FreeUsageSessionRunner.kt` |
| raw eBPF 解析 | `app/src/main/java/com/memoos/ebpf/EBPFTraceParser.kt` |
| MAPLE scenario 构造 | `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` |
| MAPLE 输入时间窗压缩 | `app/src/main/java/com/memoos/perf/RealUsageSessionRunner.kt`、`app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` |
| MAPLE shell 调用 | `app/src/main/java/com/memoos/maple/MapleShellBackend.kt` |
| 真实应用 Top-3 映射 | `app/src/main/java/com/memoos/action/AppIdMapping.kt` |
| 系统调度动作 | `app/src/main/java/com/memoos/action/ActionExecutor.kt` |
| 压力 A/B 实验 | `app/src/main/java/com/memoos/perf/PressureExperimentRunner.kt` |
| 压力指标采集 | `app/src/main/java/com/memoos/perf/DevicePressureMetrics.kt` |
| 真实 eBPF 消融 | `app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt` |
| 本地状态持久化 | `app/src/main/java/com/memoos/store/MemoStore.kt` |

## 收尾回归与最新结果

本节记录 2026-06-01 晚间收尾回归。完整归档见：

```text
docs/real_device_experiments/button_regression_2026_06_01/
```

### 结果文件

| 文件 | 内容 |
| --- | --- |
| `latest_usage_100_report_after_button.json` | 最新 100 次真实使用分析 |
| `latest_real_ablation_after_button.json` | 最新真实 eBPF 消融 |
| `latest_pressure_experiment_after_button.json` | 最新手机压力 A/B |
| `product_buttons_results.json` | 主产品按钮触发记录 |
| `latest_real_user_experiment_after_button.txt` | 最新真实滚动/显示场景采集元数据 |
| `latest_full_local_evaluation_after_button.txt` | 最新一键完整本地评估元数据 |

### 最新 100 次真实使用

| 指标 | 数值 |
| --- | ---: |
| 请求 app 打开 | 100 |
| 实际观察 app 打开 | 100 |
| 覆盖真实 app | 12 个 |
| raw eBPF parsed events | 16295 |
| MAPLE compressed eBPF signals | 180 |
| eBPF signal 行数压缩 | 98.90% |
| 平均启动 TotalTime | 171.45 ms |
| P50 TotalTime | 131 ms |
| 平均 WaitTime | 168.70 ms |
| P50 WaitTime | 128 ms |
| 完整设备端耗时 | 264.09 s |
| 前台可见处理耗时 | 10.54 s |
| Top-3 | Chrome, Messages, Camera |

说明：raw trace 仍完整保留，MAPLE 只接收按时间窗口压缩后的 `event_type/detail/count/rate_per_sec`。这不是只看 eBPF，也不是只看 app sequence，而是把 app 时间线和同窗口 eBPF/system evidence 一起交给 MAPLE。

### 最新消融

| 指标 | 结果 |
| --- | --- |
| 配置数 | 8 |
| MAPLE 可用 | 8/8 |
| predicted app id 改变 | `no_memory`, `app_sequence_baseline` |
| Top-1 app 改变 | `no_network` |
| 调度域改变 | `no_network`, `no_camera_media`, `no_display_ui`, `no_binder_service` |
| 平均端到端 | 14.46 s |
| 范围 | 7.18 s 到 45.90 s |

解释：`no_network` 改变 Top-1，`no_memory` 和 `app_sequence_baseline` 改变 predicted app id，camera/media、display/UI、binder/service 会改变调度域。也就是说深层系统证据确实影响预测和动作。

### 最新压力 A/B

| 指标 | MEMO-on 相对 baseline |
| --- | ---: |
| A/B workload | 6 组 |
| 综合压力分数 | +37.19% |
| 启动 TotalTime | +9.69% |
| WaitTime | +9.46% |
| CPU busy | +1.45% |
| iowait | +35.77% |
| MemAvailable 下降量 | -51.75% |
| reclaim | +68.14% |

分场景看，normal Chrome、crowded Magisk 改善明显；normal Magisk、normal Tencent Meeting、crowded Chrome 的启动时间变差但综合压力改善；crowded Tencent Meeting 是明确反例，综合压力 -13.12%。因此结论必须谨慎：当前结果支持 MEMO 在平均意义上降低系统压力，但不能宣称所有场景都提升。

### 按钮回归

主产品入口已经验证：检查设备授权、智能优化刷新推荐、记录当前窗口、立即预热第 1 个推荐、开始自由体验、结束体验并分析、停止后台任务。最后一次手动触发 `停止后台任务` 后，23:28 CST 设备上没有 `memo_libbpf_collector` 或 `maple_demo` 残留进程；状态中写入了带 `timestamp_ms` 的 `pipeline_stop`。

`product_buttons_results.json` 里“停止后台任务”的 `reached_expected_action=false` 是历史判定脚本误判：同一条记录的 `selected_actions` 已包含 `pipeline_stop`。代码已修正为任何 stop 都追加可见的 timestamped action。

## 产品行为修正

- 移除了产品 UI 里的“过慢就不做”口径。
- 新逻辑不再因为 MAPLE 慢就跳过预测或动作；状态只有完成、失败或 MAPLE watchdog。
- `realtime_budget_ms` 不再写入新的 latency JSON。
- Evidence 页不再写“演示数据”口径，而是展示“采集文件、采集开始时间、真实收到的前 N 条事件”；相同事件会合并成组，显示可读时间、重复次数和代表原始记录。
- MAPLE 输入不再重复塞入大量 raw eBPF 行，而是把同一时间窗内的重复事件压缩成 `event_type/detail/count/rate_per_sec`；原始 trace 仍保留给审计。
- `100 次真实使用分析` 和 `开始自由体验 -> 结束体验并分析` 使用一致的数据处理链：app sequence capture -> raw eBPF trace -> timeline alignment -> windowed eBPF compression -> MAPLE -> Top-3 app mapping -> ActionExecutor -> report/widget。
- 主界面把“观察到了什么”和“最近自由体验/真实使用分析”合并为“本次观察与分析”，避免重复展示；同一面板里同时解释实验设计、指标含义、系统证据摘要和报告入口。
- Top-3 推荐区新增“最近更新”时间；每次智能优化或自由体验结束都会重新跑 MAPLE 并刷新时间，即使三个 app 因场景相似没有变化，也能看到结果确实是刚更新的。
- 主界面新增“手机性能 A/B 实验”面板，把性能结果格式化给用户看，不直接扔 JSON。
- ReportActivity 新增性能 A/B 报告区。

## 自由体验实验结果

从 App UI 点击 `开始自由体验`，离开 MEMO 打开若干真实 app，再回到 MEMO 点击 `结束体验并分析`。

### 实验设计

这个实验验证动态产品入口：不固定 100 次，也不要求按预设顺序打开 app，而是让 MEMO 在后台记录实际前台 app 序列，同时采集 raw eBPF。结束时，手机本地完成与 100 次实验一致的结构化、压缩、MAPLE 推理和调度输出。

| 设计点 | 内容 |
| --- | --- |
| app 序列来源 | 设备侧 foreground app sampler，每秒记录一次当前前台 app；回到 MEMO 后过滤掉 MEMO 自己，只保留刚才实际使用的 app 片段 |
| eBPF 来源 | raw libbpf collector，输出完整 trace 供审计 |
| 时间对齐 | 按实际 app 停留片段生成 `observed_app_sequence`；counter 型 eBPF 在 collector 停止时统一 dump，因此压缩时按采集顺序映射回 app 使用区间 |
| 压缩方式 | 与 100 次实验相同，把重复 eBPF 聚合成 `event_type/detail/count/rate_per_sec` |
| MAPLE 输入 | `app_catalog`、`observed_app_sequence`、`timeline_windows[].compressed_ebpf`、`compression`、系统状态 |
| 输出 | MAPLE prediction、Top-3 真实 app、ActionExecutor 调度动作、可读报告 |

结果文件：

```text
docs/real_device_experiments/free_usage_session/latest_free_usage_report.json
docs/real_device_experiments/free_usage_session/latest_free_usage_maple_scenario.json
docs/real_device_experiments/free_usage_session/free_usage_raw_trace.trace
docs/real_device_experiments/free_usage_session/free_usage_app_samples.tsv
docs/real_device_experiments/free_usage_session/README.md
docs/real_device_experiments/free_usage_session/latest_main_ui*.png
```

本次短流程结果：

| 指标 | 数字 |
| --- | ---: |
| 实际 app 片段 | 4 |
| 覆盖真实应用 | 4 个 |
| app sampler 记录 | 13 条 |
| raw eBPF parsed events | 14113 |
| MAPLE compressed eBPF signals | 9 |
| timeline windows | 1 |
| Top-3 | Chrome, Messages, Camera |
| 完整设备端耗时 | 25.8 s |
| 前台可见处理耗时 | 9.2 s |

实际 app 序列：

```text
Chrome -> Messages -> Camera -> Settings
```

解释：自由体验和 100 次实验的处理过程是一致的。区别只是 app sequence 的来源，一个是自动 workload，一个是实际自由使用。两者都不会把 raw eBPF 全量直接塞给 MAPLE，而是保留 raw trace 给审计，同时把 MAPLE 输入压缩成时间窗统计信号。

## 100 次真实使用实验结果

从 App UI 点击 `100 次真实使用分析` 启动。

### 实验设计

这个实验验证“真实用户操作 -> 自然产生 eBPF -> MAPLE 推理 -> Top-3 应用 -> 调度动作”这一整条产品链路。

| 设计点 | 内容 |
| --- | --- |
| 用户行为模拟 | App 在手机本地轮流打开 100 次真实可启动 launcher 应用，模拟用户不断切换浏览器、会议、终端、系统设置、相册、地图、电话、视频、相机等实际使用场景 |
| 采集方式 | 每 5 次 app 打开作为一个时间窗，同时启动 raw eBPF collector，收集 Binder、文件访问、UDP send/recv、调度、进程 fork/exec/exit、内存状态等事件 |
| 时间对齐 | 每条 app 使用记录保存 start/end wall time；同一窗口内的 eBPF 事件聚合到对应 `timeline_windows`，使 MAPLE 同时看到“这段时间用户用了什么 app”和“系统层发生了什么” |
| 输入压缩 | raw trace 完整保留用于审计；MAPLE 输入只保留 app 序列、app catalog、每窗压缩 eBPF signal，减少重复 token |
| 预测与动作 | MAPLE 输出 predicted app/category 后，AppIdMapping 映射成真实可启动 Top-3 应用，再交给 ActionExecutor 生成 widget 更新、网络优先级、memory/display/camera/media/service 等调度动作 |
| 记录指标 | app 打开次数、unique apps、launch TotalTime/WaitTime、raw eBPF 行数、压缩后 signal 数、scenario 大小、MAPLE 输出、Top-3、端到端耗时、前台可见耗时 |

这个实验没有人工标注的 next-app ground truth。原因是它不是离线分类数据集，而是产品闭环验证：重点是证明手机真实使用能自然产生系统证据，并且这些证据能进入 MAPLE 和调度层。预测效果主要通过后面的消融实验看“删掉哪类证据会改变预测/动作”，性能效果主要通过 A/B 实验看 MEMO-on 对手机压力的影响。

结果文件：

```text
docs/real_device_experiments/real_usage_100/latest_usage_100_report.json
docs/real_device_experiments/real_usage_100/usage_summary.json
docs/real_device_experiments/real_usage_100/usage_100_raw_trace.trace
docs/real_device_experiments/real_usage_100/latest_real_usage_maple_scenario.json
docs/real_device_experiments/real_usage_100/latest_real_ablation_after_usage_100.json
```

核心结果：

| 指标 | 数字 |
| --- | ---: |
| 请求 app 打开 | 100 |
| 实际观测 app 打开 | 100 |
| 覆盖真实应用 | 12 个 |
| timestamped app sequence | 100 条 |
| timeline windows | 20 个 |
| raw eBPF parsed events | 16295 |
| MAPLE compressed eBPF signals | 180 |
| eBPF signal 行数压缩 | 98.90% |
| 平均启动 TotalTime | 171.45 ms |
| P50 启动 TotalTime | 131 ms |
| MAPLE backend | shell |
| Top-3 | Chrome, Messages, Camera |
| 完整设备端耗时 | 264.09 s |
| 前台可见处理耗时 | 10.54 s |

解释：264.09 s 包含 100 次真实 app rotation、20 个分窗 eBPF 采集、MAPLE 推理、ActionExecutor、以及 8 组消融。前台可见处理耗时为 10.54 s，主界面不会再显示“过慢实时状态”。MAPLE 输入已经包含 `app_catalog`、`observed_app_sequence`、`timeline_windows` 和 `compression`，即真实 app 序列、同时间窗 eBPF/system evidence、以及 raw rows 到压缩 signals 的对应关系。

压缩的意义不是丢掉 eBPF，而是把重复系统事件变成更适合端侧小模型的统计证据。比如第一窗里 5 次真实 app 打开对应的压缩信号是：

```text
MEMO_SCHED=241725, rate=48345/s
MEMO_RECVFROM=45334, rate=9066.8/s
MEMO_SENDTO=21059, rate=4211.8/s
MEMO_BINDER=14234, rate=2846.8/s
MEMO_OPENAT=12605, rate=2521/s
```

原始 16295 行仍在 raw trace 中用于审计，MAPLE 实际使用 20 个窗口里的 180 条压缩 eBPF signal。按行数看，eBPF 输入从 16295 条审计事件压缩到 180 条模型信号，减少 98.90%。

## 性能 A/B 实验结果

从 App UI 点击 `手机压力 A/B 实验` 启动。

### 实验设计

这个实验回答的问题是：MEMO 打开以后，用户继续使用其他真实 app 时，手机整体压力是否变好。

| 设计点 | 内容 |
| --- | --- |
| 对照方式 | 每个 workload 都跑 baseline/MEMO-off 和 MEMO-on 两种状态，比较同类 app 使用压力差异 |
| workload | 6 组真实 app 压力场景，覆盖 cached apps 较多、通信/会议、浏览、媒体、系统应用切换等情况 |
| MEMO-on 做什么 | 先运行真实 eBPF + MAPLE + ActionExecutor，让 MEMO 产生推荐和调度动作；随后执行同类用户 app workload |
| 记录窗口 | 每组 workload 前后分别采集系统状态，记录启动耗时、CPU、iowait、内存、reclaim、网络、温度等指标 |
| 综合压力分数 | 将 CPU busy、iowait、MemAvailable 下降、reclaim、启动 TotalTime/WaitTime 等归一化合成一个 lower-is-better 的压力分数 |
| 成功判据 | 不是要求每个 app 都变好，而是看平均压力是否下降，同时保留反例说明系统调度仍有开销和场景差异 |

这个实验更接近用户真正关心的问题：用户不在乎 MEMO 自己推理用了几秒，而是在乎 MEMO 后台运行以后，继续打开和切换其他 app 时手机是否更顺。

结果文件：

```text
docs/real_device_experiments/user_app_pressure/latest_pressure_experiment.json
docs/real_device_experiments/user_app_pressure/pressure_summary.json
docs/real_device_experiments/user_app_pressure/README.md
```

总体结果：

| 指标 | MEMO-on 相对 baseline |
| --- | ---: |
| A/B workload | 6 组 |
| 综合压力分数 | +37.19% |
| 启动 TotalTime | +9.69% |
| WaitTime | +9.46% |
| CPU busy | +1.45% |
| iowait | +35.77% |
| MemAvailable 下降量 | -51.75% |
| reclaim | +68.14% |

解读要诚实：这次平均结果支持 MEMO-on 有性能收益，尤其是综合压力、iowait 和 reclaim。但不是所有场景都变好，`crowded_cached_apps / Tencent Meeting` 的综合压力分数是 -13.12%，MemAvailable 下降量平均也明显变差。

## 消融实验结果

消融基于同一次 100-use 真实 eBPF scenario。

### 实验设计

这个实验验证不同证据模块对 MAPLE 预测和系统动作的重要性。它不是重新采集 8 次，而是固定同一次真实 100-use scenario，然后逐项删除或降级输入证据，避免采集噪声干扰对比。

| 配置 | 删除或保留什么 | 想验证什么 |
| --- | --- | --- |
| `full_real_ebpf` | 保留完整 app sequence + eBPF/system evidence | 作为完整产品输入基线 |
| `no_network` | 删除 UDP sendto/recvfrom 和网络类证据 | 网络证据是否影响应用推荐和调度域 |
| `no_camera_media` | 删除 camera/media 相关证据 | 相机、相册、视频类 follow-up 是否依赖这类信号 |
| `no_display_ui` | 删除 SurfaceFlinger/RenderThread/UI 类证据 | UI/display 压力是否影响预热强度和调度策略 |
| `no_binder_service` | 删除 Binder/service 相关证据 | 系统服务调用是否影响 MAPLE 对场景的判断 |
| `no_memory` | 删除内存/reclaim/pressure 相关证据 | 内存压力是否影响预测和动作降级 |
| `counters_only` | 只保留计数摘要，弱化语义 evidence | 只看统计强度是否足够 |
| `app_sequence_baseline` | 只保留 app 序列，去掉深层 eBPF | 浅层 app sequence 与深层 eBPF 的差异 |

记录的 metrics 分两类：预测侧看 MAPLE 是否可用、predicted app id 是否改变、Top-1/Top-3 是否改变；调度侧看 ActionExecutor 的 action domains、resource alignment score、scheduler intensity、是否会发布 widget、是否会 warm launch 或做压力抑制。

| 指标 | 结果 |
| --- | --- |
| 配置数 | 8 |
| MAPLE 可用 | 8/8 |
| predicted app id 改变 | `no_network`, `no_memory`, `app_sequence_baseline` |
| Top-1 改变 | `no_network` |
| 调度域改变 | `no_network`, `no_camera_media`, `no_display_ui`, `no_binder_service` |
| 平均端到端 | 14.46 s |
| 范围 | 7.18 s 到 45.90 s |

解读：network 证据影响 Top-1，也会影响 MAPLE app id；memory、app-sequence baseline 也会影响 MAPLE app id；camera/display/binder 影响 ActionExecutor 的调度域。说明 eBPF 深层证据确实参与了推荐和调度，不只是统计装饰。

## 按钮审计

结果文件：

```text
docs/real_device_experiments/button_audit/latest_button_audit_results.txt
```

### 实验设计

这个实验验证 App 里用户能点到的按钮是不是都真的触发了对应功能，避免 UI 上出现“看起来能点，实际没做事”的入口。

| 设计点 | 内容 |
| --- | --- |
| 执行方式 | 使用 UIAutomator 在真实设备上按文本逐个点击主界面按钮 |
| 验证目标 | 每个按钮至少能打开对应页面、触发后台任务、更新状态，或者给出明确失败原因 |
| 覆盖范围 | 授权检查、停止任务、打开推荐 app、预热推荐、eBPF 详情、使用报告、消融报告、100 次真实使用、压力 A/B、性能报告、高级诊断开关 |
| 判定标准 | 不能是空按钮；不能展示伪造结果；失败时必须让用户知道缺少 root、collector、模型或其他能力 |

已通过 UIAutomator 点击验证：

```text
检查设备授权
停止后台任务
打开
立即预热第 1 个推荐
查看 eBPF 证据详情
查看完整使用报告
查看完整消融报告
100 次真实使用分析
手机压力 A/B 实验
查看完整性能报告
隐藏高级诊断
显示高级诊断
```

## 当前结论

可以对 teammate 这样汇报：

```text
Jingyi 这边已经把 MEMO-Appflow 从 host 脚本原型推进成真机端侧产品闭环。
在 rooted Pixel 5 上，App 能从按钮启动真实 eBPF 采集、MAPLE 推理、Top-3 推荐、系统调度、Widget/报告展示。
收尾回归中的 100 次真实 app 使用实验跑通，采到 16295 条 eBPF 兼容事件行，构造出 100 条 timestamped app sequence 和 20 个 app/eBPF 对齐时间窗，并把 MAPLE 输入压缩成 180 条 eBPF signal，产出 Chrome/Messages/Camera Top-3。
压力 A/B 实验显示平均综合压力改善 37.19%，启动 TotalTime 改善 9.69%，WaitTime 改善 9.46%，但 MemAvailable 下降量变差 51.75%，crowded Tencent Meeting 是反例，不夸大。
```
