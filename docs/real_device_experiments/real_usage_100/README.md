# 100 次真实使用实验

日期：2026-06-01

负责人：Jingyi Guo

## 实验背景

这次实验验证的是产品闭环，而不是手写 demo。操作从 MEMO App 里的 `100 次真实使用分析` 按钮启动；Host 只负责安装 APK、点击按钮、拉取结果文件。

设备是 rooted Pixel 5。实验过程中，MEMO 在手机本地轮流打开真实可启动应用 100 次，同时运行 raw eBPF collector，随后在手机本地完成：

```text
真实 app 使用
-> raw eBPF 采集
-> Kotlin 解析和 scenario 构造
-> MAPLE shell 推理
-> Top-3 真实应用映射
-> ActionExecutor 调度动作
-> Widget / 主界面报告更新
-> 真实 eBPF 消融实验
```

## 结果文件

| 文件 | 内容 |
| --- | --- |
| `latest_usage_100_report.json` | 100 次真实使用实验完整报告 |
| `usage_summary.json` | 关键指标摘要 |
| `usage_100_raw_trace.trace` | 本次实验生成的 raw eBPF trace |
| `latest_real_usage_maple_scenario.json` | MAPLE 实际输入的 full scenario，包含 app 时间线和 eBPF 时间窗 |
| `latest_real_ablation_after_usage_100.json` | 基于同一次真实 eBPF scenario 的消融报告 |

手机端原始路径：

```text
/sdcard/MEMO/logs/usage_100_1780298489375.trace
/sdcard/MEMO/scenarios/latest_real_usage_maple_scenario.json
/sdcard/MEMO/reports/latest_usage_100_report.json
/sdcard/MEMO/ablations/latest_real_ablation.json
```

## 关键数字

| 指标 | 结果 |
| --- | ---: |
| 计划打开 app 次数 | 100 |
| 实际观测到的 app 打开 | 100 |
| 覆盖真实应用 | 12 个 |
| timestamped app sequence | 100 条 |
| timeline windows | 20 个，每 5 次 app 使用一窗 |
| eBPF parsed events | 16200 条 |
| 平均启动 TotalTime | 148.1 ms |
| P50 启动 TotalTime | 123 ms |
| 平均 WaitTime | 147.3 ms |
| P50 WaitTime | 120 ms |
| MAPLE backend | shell |
| MAPLE predicted app id | 220 |
| MAPLE stage1 | Memory Management (100%) |
| Top-3 真实应用 | Chrome, Messages, Camera |
| 完整设备端耗时 | 301.3 s |
| 前台可见处理耗时 | 14.5 s |

这里的 301.3 s 包括 100 次 app rotation、20 个分窗 eBPF 采集、MAPLE 推理和 8 组消融实验。产品 UI 不再显示“过慢实时状态”，也不会因为 MAPLE 慢就跳过；状态为 `completed`。

## MAPLE 输入里的时间线

这版不再只给 MAPLE 一个全局 eBPF 汇总。`latest_real_usage_maple_scenario.json` 里新增两类时间对齐字段：

| 字段 | 含义 |
| --- | --- |
| `observed_app_sequence` | 100 条按时间排序的真实 app 使用记录，每条包含 package、label、category、start/end timestamp、launch state、TotalTime、WaitTime |
| `timeline_windows` | 20 个时间窗，每窗包含 5 次真实 app 使用，以及同一时间窗内 raw eBPF counter totals |

第一窗示例：

```text
2026-06-01T15:21:35+08:00..2026-06-01T15:21:39+08:00
apps = Chrome -> Tencent Meeting -> Termux -> Magisk -> Photos
eBPF = MEMO_SCHED=213621, MEMO_RECVFROM=34269, MEMO_SENDTO=17114,
       MEMO_BINDER=12541, MEMO_OPENAT=11856
```

也就是说，MAPLE 现在同时看到“用户按时间用了哪些 app”和“同一时间窗里系统层发生了什么”，而不是只看 eBPF。

## 观测到的系统证据

本次 raw eBPF trace 被解析出这些主要事件：

| 事件 | 数量 |
| --- | ---: |
| `MEMO_BINDER` | 2000 |
| `MEMO_OPENAT` | 2000 |
| `MEMO_SENDTO` | 2000 |
| `MEMO_RECVFROM` | 2000 |
| `MEMO_SCHED` | 2000 |
| `MEMO_PROCESS_FORK` | 2000 |
| `MEMO_PROCESS_EXIT` | 2000 |
| `MEMO_PROCESS_EXEC` | 2000 |
| `MEMO_MEMORY` | 160 |
| `MEMO_STATUS` | 40 |

系统状态变化：

| 指标 | 变化 |
| --- | ---: |
| MemAvailable | -493228 kB |
| UDP in datagrams | +697 |
| UDP out datagrams | +711 |
| pgscan_direct | 0 |
| pgscan_kswapd | +11010 |
| 温度 | +0.3 C |

这说明实验确实覆盖了 app 切换、文件/库访问、网络收发、Binder/service、调度、内存回收等系统层信号。

## 产品做了什么

MAPLE 基于这次真实 eBPF scenario 输出后，AppIdMapping 把结果映射成真实可启动应用：

```text
Chrome   -> Network IO
Messages -> Communication
Camera   -> Camera Service
```

ActionExecutor 做了这些调度：

```text
widget_update -> 发布 3 个真实应用推荐
latency_policy -> MAPLE 异步完成；慢也继续驱动动作
memory_policy -> 当前内存正常，保留轻量预热能力
network_candidate_priority -> UDP sendto/recvfrom 证据提高网络类应用优先级
network_stats_refresh -> 刷新 netstats
camera_media_candidate -> 选择 Camera 作为 camera/media follow-up
display_ui_policy -> 限制预热强度，避免 UI jank
binder_service_policy -> Binder/service 证据进入调度上下文
binder_service_refresh -> 刷新 service manager
warm_launch_policy -> 后台模式只准备候选，不擅自切屏
```

## 消融结果

同一次真实 eBPF scenario 上跑了 8 组消融：

```text
full_real_ebpf
no_network
no_camera_media
no_display_ui
no_binder_service
no_memory
counters_only
app_sequence_baseline
```

结果摘要：

| 指标 | 结果 |
| --- | --- |
| MAPLE 可用配置 | 8/8 |
| 改变 predicted app id | `no_network`, `no_memory`, `app_sequence_baseline` |
| 改变 Top-1 应用 | `no_network` |
| 改变调度域 | `no_network`, `no_camera_media`, `no_display_ui`, `no_binder_service` |
| 平均端到端耗时 | 18.5 s |
| 最慢配置 | 58.1 s |
| 最快配置 | 8.4 s |

解读：network 证据对 Top-1 应用最敏感；binder/memory/app-sequence baseline 会改变 MAPLE 内部 app id；camera/display/binder 对调度动作域有明显影响。这说明 eBPF 的深层证据不是装饰，它会改变最终推荐或调度策略。
