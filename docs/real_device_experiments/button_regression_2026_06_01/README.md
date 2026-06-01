# 2026-06-01 收尾回归与最新结果

负责人：Jingyi Guo

本目录保存 2026-06-01 晚上最后一轮真机结果归档。目标不是再做一个短 demo，而是把今天已经跑通的端侧产品闭环、真实 eBPF 数据、MAPLE 推理、系统动作、压力 A/B 和按钮回归整理成一份可追溯的结果。

## 实验环境

| 项目 | 设置 |
| --- | --- |
| 设备 | rooted Pixel 5 |
| 运行方式 | MEMO Android App 在手机本地触发采集、解析、MAPLE、Top-3 映射和 ActionExecutor |
| Host 作用 | 安装 APK、触发 UI/intent、拉取结果文件；不参与模型输入构造或预测 |
| eBPF 路径 | raw eBPF/libbpf collector，不使用 bpftrace |
| MAPLE 路径 | 设备侧 MAPLE shell/backend，是唯一预测入口 |
| 结果时间 | 2026-06-01 晚间重跑与收尾回归 |

## 文件索引

| 文件 | 内容 |
| --- | --- |
| `latest_usage_100_report_after_button.json` | 最新 100 次真实 app 使用分析，包含 app sequence、raw eBPF、压缩信号、MAPLE、Top-3 和动作 |
| `latest_real_ablation_after_button.json` | 基于同一真实 eBPF scenario 的 8 组消融实验 |
| `latest_pressure_experiment_after_button.json` | MEMO-off / MEMO-on 手机压力 A/B 实验 |
| `latest_real_user_experiment_after_button.txt` | 最新真实滚动/显示场景采集元数据 |
| `latest_full_local_evaluation_after_button.txt` | 最新一键完整本地评估元数据 |
| `product_buttons_results.json` | 主产品按钮的功能触发记录 |

早前有一份通过错误 broadcast 方式触发的 `experiment_buttons_results.json`，它不是有效产品入口验证，已从最终归档中删除。当前报告只引用上表文件。

## 1. 产品链路是否完整

当前手机侧实际链路是：

```text
真实 app 使用 / 当前窗口 / 自由体验
-> raw eBPF/libbpf collector
-> Kotlin EBPFTraceParser
-> app sequence + eBPF timeline window 对齐
-> 重复 eBPF signal 压缩
-> MAPLE shell/backend 推理
-> AppIdMapping 映射成真实可启动 Top-3
-> ActionExecutor 生成调度动作
-> 主界面、Widget、报告页展示
```

对应代码：

| 作用 | 文件 |
| --- | --- |
| 主界面、按钮、Top-3、报告入口 | `app/src/main/java/com/memoos/MainActivity.kt` |
| 后台服务入口 | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` |
| eBPF 解析 | `app/src/main/java/com/memoos/ebpf/EBPFTraceParser.kt` |
| MAPLE scenario 构造 | `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` |
| MAPLE 调用 | `app/src/main/java/com/memoos/maple/MapleShellBackend.kt` |
| Top-3 真实应用映射 | `app/src/main/java/com/memoos/action/AppIdMapping.kt` |
| 系统动作 | `app/src/main/java/com/memoos/action/ActionExecutor.kt` |
| 100 次真实使用 | `app/src/main/java/com/memoos/perf/RealUsageSessionRunner.kt` |
| 自由体验 | `app/src/main/java/com/memoos/perf/FreeUsageSessionRunner.kt` |
| 压力 A/B | `app/src/main/java/com/memoos/perf/PressureExperimentRunner.kt` |
| 真实 eBPF 消融 | `app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt` |

## 2. 100 次真实使用实验

### 实验设计

这个实验模拟一段真实手机使用：手机本地轮流打开 100 次真实可启动 launcher app。每个窗口包含 5 次 app 使用，同时采集 raw eBPF。实验结束后，App 在手机本地把 app 使用序列和 eBPF 信号按时间窗口合并，再压缩成 MAPLE 输入。

它不是手写 demo，也不是 host Python 造数据。raw trace 保留在设备和归档文件里，MAPLE 使用的是同一批真实 app 使用自然产生的系统证据。

### 最新结果

来自 `latest_usage_100_report_after_button.json`：

| 指标 | 数值 |
| --- | ---: |
| 请求 app 打开 | 100 |
| 实际观察 app 打开 | 100 |
| 覆盖真实 app | 12 个 |
| timeline windows | 20 个 |
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

解释：

- `raw eBPF parsed events=16295` 是审计用原始事件行数。
- `compressed eBPF signals=180` 是 MAPLE 真正收到的 eBPF 统计信号。
- 压缩不是丢掉 eBPF，而是把同一时间窗口里的重复事件合并成 `event_type/detail/count/rate_per_sec`，减少小模型重复 token。
- MAPLE 现在同时看到 app 时间线和 eBPF/system evidence，不是只看 eBPF，也不是只看 app sequence。

## 3. 真实 eBPF 消融实验

### 实验设计

消融实验固定同一份 100 次真实使用 scenario，然后逐项删掉或降级证据。这样可以避免“重新采集时系统状态不一样”影响对比。

| 配置 | 含义 |
| --- | --- |
| `full_real_ebpf` | app sequence + 全量 eBPF/system evidence |
| `no_network` | 删除 UDP sendto/recvfrom 与网络证据 |
| `no_camera_media` | 删除 camera/media 证据 |
| `no_display_ui` | 删除 SurfaceFlinger/RenderThread/display/UI 证据 |
| `no_binder_service` | 删除 Binder/service 证据 |
| `no_memory` | 删除 memory/reclaim/pressure 证据 |
| `counters_only` | 只保留计数摘要，弱化语义证据 |
| `app_sequence_baseline` | 只保留 app 序列，去掉深层 eBPF |

### 最新结果

来自 `latest_real_ablation_after_button.json`：

| 指标 | 数值 |
| --- | ---: |
| 配置数 | 8 |
| MAPLE 可用 | 8/8 |
| predicted app id 改变 | `no_memory`, `app_sequence_baseline` |
| Top-1 app 改变 | `no_network` |
| 调度域改变 | `no_network`, `no_camera_media`, `no_display_ui`, `no_binder_service` |
| 平均端到端耗时 | 14.46 s |
| 最快 / 最慢 | 7.18 s / 45.90 s |

解读：

- `no_network` 会让 Top-1 从 Chrome 变成 Messages，说明网络 eBPF 证据直接影响推荐排序。
- `no_memory` 和 `app_sequence_baseline` 会改变 MAPLE predicted app id，说明只看浅层 app sequence 会改变模型判断。
- `no_camera_media`、`no_display_ui`、`no_binder_service` 会改变 ActionExecutor 的调度域，说明这些系统证据不是装饰字段，而是真的进入了调度策略。

## 4. 手机压力 A/B 实验

### 实验设计

这个实验回答“MEMO 对手机整体使用压力有没有帮助”，而不是只看 MEMO 自己推理用了多久。

每个 workload 跑两次：

```text
MEMO-off baseline:
  同样的前置真实 app 动作
  不做 eBPF / MAPLE / ActionExecutor
  测后续目标 app 启动和系统压力

MEMO-on:
  同样的前置真实 app 动作
  采集 eBPF -> MAPLE -> ActionExecutor
  再测后续目标 app 启动和系统压力
```

这样设计是为了避免 MEMO-on 因为“刚启动过目标 app”得到不公平缓存优势。

### 指标解释

| 指标 | 含义 | 方向 |
| --- | --- | --- |
| `launch_total_time_ms` | `am start -W` TotalTime | 越小越好 |
| `wait_time_ms` | `am start -W` WaitTime | 越小越好 |
| `cpu_busy_pct` | workload 窗口 CPU busy 占比 | 越小越好 |
| `iowait_pct` | CPU 等待 IO 的比例 | 越小越好 |
| `mem_available_drop_kb` | workload 前后 MemAvailable 下降量 | 越小越好 |
| `reclaim_delta` | `pgscan_direct + pgscan_kswapd` 增量 | 越小越好 |
| `pressure_score_lower_is_better` | 组合压力分数 | 越小越好 |

百分比统一按：

```text
positive = MEMO-on 比 baseline 更好
negative = MEMO-on 比 baseline 更差
```

### 最新平均结果

来自 `latest_pressure_experiment_after_button.json`：

| 指标 | MEMO-on 相对 baseline |
| --- | ---: |
| workload 数 | 6 |
| 综合压力分数 | +37.19% |
| 启动 TotalTime | +9.69% |
| WaitTime | +9.46% |
| CPU busy | +1.45% |
| iowait | +35.77% |
| MemAvailable 下降量 | -51.75% |
| reclaim | +68.14% |

### 分场景结果

| condition | workload | TotalTime | WaitTime | pressure score | 说明 |
| --- | --- | ---: | ---: | ---: | --- |
| normal | Chrome | +33.18% | +32.87% | +42.96% | 启动和压力都改善 |
| normal | Magisk | -25.83% | -25.82% | +5.67% | 压力略改善，但启动变慢 |
| normal | Tencent Meeting | -5.57% | -5.52% | +3.03% | 压力略改善，启动变慢 |
| crowded | Chrome | -9.03% | -9.90% | +91.86% | crowded 下压力大幅改善，启动变慢 |
| crowded | Magisk | +75.12% | +75.00% | +92.75% | 启动和压力都明显改善 |
| crowded | Tencent Meeting | -9.70% | -9.85% | -13.12% | 明确反例，不能夸大 |

结论要诚实：这轮结果支持 MEMO-on 平均降低系统压力，并改善部分启动体验；但不是所有 workload 都变好。尤其 MemAvailable 下降量平均变差，说明当前策略有时会保留更多缓存或引入额外内存占用，后续需要继续调动作强度。

## 5. 产品按钮回归

### 实验设计

按钮回归不是测模型效果，而是确认 App UI 上的入口不是空按钮。每个入口触发后，都必须在设备侧状态里留下可观察的 action、报告文件或推荐更新时间。

### 已验证主入口

来自 `product_buttons_results.json` 和最后一次设备状态：

| 入口 | 期望动作 | 结果 |
| --- | --- | --- |
| 检查设备授权 | root/collector/MAPLE capability | 通过 |
| 智能优化：刷新当前推荐 | MAPLE + Top-3 + ActionExecutor | 通过 |
| 记录当前窗口 | 当前窗口 eBPF 采集 + MAPLE | 通过 |
| 立即预热第 1 个推荐 | warm launch | 通过 |
| 开始自由体验 | 后台采集会话开始 | 通过 |
| 结束体验并分析 | 自由体验报告 | 通过 |
| 停止后台任务 | `pipeline_stop` action | 通过，最后一次触发时间 2026-06-01 23:28 CST |

`product_buttons_results.json` 里“停止后台任务”的 `reached_expected_action=false` 是历史判定脚本误判：同一个文件的 `selected_actions` 已包含 `pipeline_stop`。最新代码已经把停止逻辑改为任何 stop 都写入带 `timestamp_ms` 的 `pipeline_stop`，并且 23:28 手动触发后确认没有 `memo_libbpf_collector` 或 `maple_demo` 残留进程。

## 6. 当前结论

可以对组里这样汇报：

```text
Jingyi Guo 这边完成了 MEMO-Appflow rooted Pixel 5 端侧产品闭环收尾。
手机本地可以完成 raw eBPF 采集、Kotlin 解析、app/eBPF 时间窗压缩、MAPLE 推理、Top-3 真实应用推荐、ActionExecutor 调度和报告展示。
最新 100 次真实使用实验采到 16295 条 eBPF 事件，压缩成 180 条 MAPLE eBPF signal，Top-3 为 Chrome / Messages / Camera。
消融显示 network、memory、camera/media、display/UI、binder/service 等证据会改变预测或调度域。
压力 A/B 平均综合压力改善 37.19%，启动 TotalTime 改善 9.69%，WaitTime 改善 9.46%；但 MemAvailable 下降量变差 51.75%，crowded Tencent Meeting 是反例。
所以当前结果支持“MEMO 有平均性能收益”，但不能声称所有场景都提升。
```
