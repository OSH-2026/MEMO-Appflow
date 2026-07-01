# 系统调度性能实验

本目录保存 2026-07-02 在 rooted Pixel 5 上完成的系统调度性能实验。实验目标是验证 MAPLE Stage 3 输出的 `scheduler_bits` 进入 Android 端 `ActionExecutor` 后，是否能对同一个真实 app 的启动和系统压力指标产生可量化影响。

## 实验环境

| 项目 | 内容 |
| --- | --- |
| 设备 | Pixel 5 |
| Android | 14 |
| Kernel | Linux 4.19.278 aarch64 |
| 采集方式 | Android 端 raw eBPF/libbpf collector |
| 推理方式 | Android 端 MAPLE / GGUF 本地模型 |
| 入口 | `MainActivity` 通过 `memo_action=com.memoos.action.USER_APP_PRESSURE_EXPERIMENT` 启动 foreground service |

## 实验设计

这次不再使用旧的“MEMO 开/关压力 A/B”口径，而是直接验证 Stage 3 调度动作本身。

1. MEMO 在手机内启动 raw eBPF collector，真实打开一个上下文 app 并收集系统证据。
2. Kotlin 端把 eBPF 事件、系统状态和 app 信息构造成 MAPLE scenario。
3. MAPLE 在手机本地推理，输出 Top-1 真实 app 和 `scheduler_bits`。
4. 实验固定同一个 MAPLE Top-1 app 作为被测目标。本次目标是 `QQ / com.tencent.mobileqq`。
5. Baseline 分支：先 `force-stop QQ`，不执行 Stage 3 调度，然后启动 QQ 并跑同一段动作序列。
6. Stage 3 分支：先 `force-stop QQ`，执行 MAPLE 的 `scheduler_bits=101010100`，再启动同一个 QQ 并跑同一段动作序列。

这样做的原因是：系统调度实验必须保证两个分支测的是同一个 app。之前如果预热的是 QQ、测量的是 Chrome，结果就不能说明调度动作是否有效。当前 v2 实验把目标 app 对齐，差别集中在“有没有执行 Stage 3 scheduler bits”。

## 结果文件

| 文件 | 说明 |
| --- | --- |
| `latest_pressure_experiment.json` | 完整结构化实验结果，包括设计、workloads、comparisons、summary 和动作日志 |
| `system_scheduler_progress.tsv` | 设备侧运行进度，记录 preflight、MAPLE 推理、target 选择、Stage 3 执行和报告落盘 |

设备端原始路径：

```text
/sdcard/MEMO/pressure/latest_pressure_experiment.json
/sdcard/MEMO/pressure/system_scheduler_progress.tsv
```

## 关键运行过程

来自 `system_scheduler_progress.tsv`：

```text
start -> preflight_ok
maple_target_selection
maple_inference scenario_events=1392
target_selected target=QQ/com.tencent.mobileqq scheduler_bits=101010100
baseline_measure
stage3_prepare
stage3_execute scheduler_bits=101010100 top1=com.tencent.mobileqq
scheduler_done actions=7
done report=/sdcard/MEMO/pressure/latest_pressure_experiment.json
```

这说明实验不是离线改 JSON，而是在手机里完成了 eBPF 采集、MAPLE 推理、Stage 3 调度动作和结果落盘。

## 指标结果

正数表示 Stage 3 相比 no-scheduler 降低了 lower-is-better 指标。

| 指标 | No scheduler | Stage 3 scheduler | 改善 |
| --- | ---: | ---: | ---: |
| Launch TotalTime | 433 ms | 168 ms | 61.20% |
| WaitTime | 440 ms | 173 ms | 60.68% |
| 综合压力分数 | 422.56 | 9.71 | 97.70% |
| CPU busy | 26.70% | 20.79% | 22.13% |
| iowait | 0.094% | 0.023% | 75.42% |
| MemAvailable drop | 164892 KB | -84448 KB | 151.21% |
| reclaim | 10046 | 0 | 100.00% |
| jank rate | 1.240% | 0.886% | 28.56% |

## Stage 3 执行动作

MAPLE 输出：

```text
scheduler_bits=101010100
```

对应动作日志：

| 动作 | 状态 | 含义 |
| --- | --- | --- |
| `widget_update` | ok | 更新桌面 Widget 的 Top-3 推荐 |
| `maple_scheduler_plan` | ok | 从 9 个预定义动作中选择 4 个执行 |
| `maple_warm_launch_top1` | ok | 预热 Top-1 QQ，并回到 HOME |
| `maple_trim_memory` | ok | 清理低优先级候选 app |
| `maple_drop_cache` | skipped | MAPLE 请求 drop cache，但当前内存不是 critical，所以安全跳过 |
| `maple_service_manager_refresh` | ok | 刷新 service manager 信息 |

## 结论

本次实验支持：在同一个 MAPLE Top-1 app 上，Stage 3 调度动作能够明显降低启动延迟和系统压力指标。最关键的改进是实验设计变得更严谨：先用 eBPF+MAPLE 选定真实目标 app，再用相同目标 app 比较“无调度”和“执行 Stage 3 调度”。这证明 `scheduler_bits -> ActionExecutor -> 系统动作 -> 可测指标` 这条链路已经真实生效。

需要保留边界：这是单次 rooted Pixel 5 实验，不代表所有 app、所有设备、所有压力场景都会提升。后续应继续扩大场景和重复次数。
