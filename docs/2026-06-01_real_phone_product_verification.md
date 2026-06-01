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

## 关键代码对应关系

| 模块 | 文件 |
| --- | --- |
| 主界面、Top-3、报告入口、耗时展示 | `app/src/main/java/com/memoos/MainActivity.kt` |
| eBPF 详情页，展示真实 raw trace 前 N 条 | `app/src/main/java/com/memoos/EvidenceActivity.kt` |
| 可读报告页 | `app/src/main/java/com/memoos/ReportActivity.kt` |
| 后台 service 入口 | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` |
| 100 次真实 app 使用实验 | `app/src/main/java/com/memoos/perf/RealUsageSessionRunner.kt` |
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

## 产品行为修正

- 移除了产品 UI 里的“过慢就不做”口径。
- 新逻辑不再因为 MAPLE 慢就跳过预测或动作；状态只有完成、失败或 MAPLE watchdog。
- `realtime_budget_ms` 不再写入新的 latency JSON。
- Evidence 页不再写“演示数据”口径，而是展示“采集文件、采集开始时间、真实收到的前 N 条事件、原始记录”。
- MAPLE 输入不再重复塞入大量 raw eBPF 行，而是把同一时间窗内的重复事件压缩成 `event_type/detail/count/rate_per_sec`；原始 trace 仍保留给审计。
- 主界面新增“手机性能 A/B 实验”面板，把性能结果格式化给用户看，不直接扔 JSON。
- ReportActivity 新增性能 A/B 报告区。

## 100 次真实使用实验结果

从 App UI 点击 `100 次真实使用分析` 启动。

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
| raw eBPF parsed events | 16257 |
| MAPLE compressed eBPF signals | 180 |
| MAPLE scenario 大小 | 109055 bytes |
| scenario 相比未压缩结构减少 | 37.24% |
| 平均启动 TotalTime | 148.0 ms |
| P50 启动 TotalTime | 126 ms |
| MAPLE backend | shell |
| Top-3 | Chrome, Messages, Camera |
| 完整设备端耗时 | 301.3 s |
| 前台可见处理耗时 | 14.5 s |

解释：301.3 s 包含 100 次真实 app rotation、20 个分窗 eBPF 采集、MAPLE 推理、ActionExecutor、以及 8 组消融。用户看到的是 `已完成`，不会再看到“过慢实时状态”。MAPLE 输入已经包含 `app_catalog`、`observed_app_sequence`、`timeline_windows` 和 `compression`，即真实 app 序列、同时间窗 eBPF/system evidence、以及 raw rows 到压缩 signals 的对应关系。

压缩的意义不是丢掉 eBPF，而是把重复系统事件变成更适合端侧小模型的统计证据。比如第一窗里 5 次真实 app 打开对应的压缩信号是：

```text
MEMO_SCHED=241725, rate=48345/s
MEMO_RECVFROM=45334, rate=9066.8/s
MEMO_SENDTO=21059, rate=4211.8/s
MEMO_BINDER=14234, rate=2846.8/s
MEMO_OPENAT=12605, rate=2521/s
```

原始 16257 行仍在 `usage_100_raw_trace.trace`，MAPLE 实际使用 20 个窗口里的 180 条压缩 eBPF signal，scenario 从约 173759 bytes 降到 109055 bytes，减少 37.24%。

## 性能 A/B 实验结果

从 App UI 点击 `手机压力 A/B 实验` 启动。

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
| 综合压力分数 | +29.70% |
| 启动 TotalTime | +12.28% |
| WaitTime | +13.74% |
| CPU busy | +9.84% |
| iowait | +33.40% |
| MemAvailable 下降量 | -14.13% |
| reclaim | +13.07% |

解读要诚实：这次平均结果支持 MEMO-on 有性能收益，尤其是综合压力、启动时间、CPU/iowait、reclaim。但不是所有场景都变好，`crowded_cached_apps / Tencent Meeting` 的综合压力分数是 -36.71%，MemAvailable 下降量平均也变差。

## 消融实验结果

消融基于同一次 100-use 真实 eBPF scenario。

| 指标 | 结果 |
| --- | --- |
| 配置数 | 8 |
| MAPLE 可用 | 8/8 |
| predicted app id 改变 | `no_network`, `no_memory`, `app_sequence_baseline` |
| Top-1 改变 | `no_network` |
| 调度域改变 | `no_network`, `no_camera_media`, `no_display_ui`, `no_binder_service` |
| 平均端到端 | 18.5 s |
| 范围 | 8.9 s 到 59.3 s |

解读：network 证据影响 Top-1，也会影响 MAPLE app id；memory、app-sequence baseline 也会影响 MAPLE app id；camera/display/binder 影响 ActionExecutor 的调度域。说明 eBPF 深层证据确实参与了推荐和调度，不只是统计装饰。

## 按钮审计

结果文件：

```text
docs/real_device_experiments/button_audit/latest_button_audit_results.txt
```

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
100 次真实 app 使用实验跑通，采到 16257 条 eBPF 兼容事件行，构造出 100 条 timestamped app sequence 和 20 个 app/eBPF 对齐时间窗，并把 MAPLE 输入压缩成 180 条 eBPF signal，scenario 大小减少 37.24%，产出 Chrome/Messages/Camera Top-3。
压力 A/B 实验显示平均综合压力改善 29.70%，启动 TotalTime 改善 12.28%，但也记录了反例，不夸大。
```
