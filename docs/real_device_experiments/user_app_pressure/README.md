# 真实用户 App 压力 A/B 实验

日期：2026-05-28

负责人：Jingyi Guo

## 实验目的

这组实验修正了之前只看 “MEMO pipeline / MAPLE 推理耗时” 的口径。pipeline latency 只能说明我们自己的系统跑得快不快，不能直接说明手机体验是否变好。

这里测的是更接近产品目标的指标：

```text
用户正常使用其他真实 Android app
-> MEMO off baseline
vs
用户正常使用其他真实 Android app
-> MEMO on：真实 eBPF 采集 -> MAPLE -> Top-3 -> ActionExecutor
-> 再测后续 app 使用时的系统压力
```

也就是说，这个实验关注的是 MEMO 开启后，对用户使用 Chrome、Magisk、Tencent Meeting 这类真实 app 时，手机整体压力、启动时间、卡顿、内存回收等指标有没有改善。

## 代码入口

| 作用 | 文件 |
| --- | --- |
| App UI 按钮 `Run App Pressure A/B Test` | `app/src/main/java/com/memoos/MainActivity.kt` |
| service action 入口 | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` |
| A/B 实验 runner | `app/src/main/java/com/memoos/perf/PressureExperimentRunner.kt` |
| 压力指标采集与解析 | `app/src/main/java/com/memoos/perf/DevicePressureMetrics.kt` |
| 真实 app 选择/映射 | `app/src/main/java/com/memoos/action/AppIdMapping.kt` |
| 真实用户动作计划 | `app/src/main/java/com/memoos/ebpf/RealUserExperimentPlanner.kt` |
| MAPLE 后的系统调度动作 | `app/src/main/java/com/memoos/action/ActionExecutor.kt` |

## 实验环境

| 项 | 值 |
| --- | --- |
| 设备 | rooted Pixel 5 / redfin |
| Android | 14 |
| Kernel | Linux 4.19.278 aarch64 |
| eBPF 路径 | raw eBPF collector，不使用 bpftrace |
| MAPLE 路径 | `/data/local/tmp/memo/maple_demo` + 本地 GGUF 模型 |
| 结果文件 | `docs/real_device_experiments/user_app_pressure/latest_pressure_experiment.json` |

## 实验条件

本次实验跑了两类设备状态：

| condition | 含义 |
| --- | --- |
| `normal_recent_usage` | 不额外制造压力，只回到 HOME 后执行真实 app workload |
| `crowded_cached_apps` | 先打开多个真实已安装 app 再回 HOME，模拟手机后台/缓存里塞了较多 app 的状态 |

每个 condition 下都跑同一套 A/B：

1. `memo_off_baseline`：执行同一个前置真实用户动作，但不做 eBPF、MAPLE、ActionExecutor。
2. `memo_on_after_real_ebpf_maple_actions`：在同一个前置真实用户动作期间采集 raw eBPF，构建 scenario，调用 MAPLE，再由 ActionExecutor 执行非侵入式调度动作。
3. 再执行同一个目标 app workload，采集启动、CPU、IO、内存、reclaim、PSI、gfx/jank 等指标。

这样做是为了避免 MEMO-on 因为“刚刚启动过目标 app”而不公平地获得缓存优势。baseline 和 MEMO-on 都有同样的前置真实用户动作；区别只在于 MEMO-on 会基于这段真实行为做 eBPF->MAPLE->动作。

## 本次真实 workload

这台手机上最终跑到的真实 workload 是：

| workload | package | 说明 |
| --- | --- | --- |
| Chrome | `com.android.chrome` | 滚动/显示/网络类真实 app 使用 |
| Magisk | `com.topjohnwu.magisk` | generic real launchable app workload |
| Tencent Meeting | `com.tencent.wemeet.app` | generic real launchable app workload |

注意：实验过程中曾发现设备元数据会把 Termux 匹配到 camera/media 相关能力。这个标注不够干净，因此代码已收紧：camera 场景必须真的匹配 `Camera Service`，否则不会冒充 camera 场景，而是改成 generic workload。

## Metrics 解释

| metric | 含义 | 越小越好 |
| --- | --- | --- |
| `launch_total_time_ms` | `am start -W` 的 TotalTime，目标 app 启动耗时 | 是 |
| `wait_time_ms` | `am start -W` 的 WaitTime | 是 |
| `cpu_busy_pct` | workload 窗口内 `/proc/stat` 计算出的 CPU busy 占比 | 是 |
| `iowait_pct` | workload 窗口内 CPU iowait 占比 | 是 |
| `mem_available_drop_kb` | workload 前后 MemAvailable 下降量 | 是 |
| `reclaim_delta` | `/proc/vmstat` 中 `pgscan_direct + pgscan_kswapd` 增量 | 是 |
| `psi_some_avg10_delta` | memory PSI some avg10 的变化 | 是 |
| `jank_rate_pct` | `dumpsys gfxinfo` 里的 janky frames / total frames | 是 |
| `pressure_score_lower_is_better` | 综合压力分数：内存下降、reclaim、PSI、CPU、iowait、温度、UDP error 的加权和 | 是 |

百分比指标的方向：

```text
positive improvement = MEMO-on 比 baseline 更好
negative improvement = MEMO-on 比 baseline 更差
```

## 总体结果

来自 `latest_pressure_experiment.json` 的 summary：

| 指标 | MEMO-on 相对 baseline |
| --- | ---: |
| workload 数 | 6 个 A/B 对照 |
| app 启动 TotalTime | +0.15% |
| app WaitTime | +0.25% |
| 综合压力分数 | +35.41% |
| CPU busy | -0.53% |
| iowait | -2.79% |
| MemAvailable 下降量 | +62.10% |
| reclaim | +56.08% |

解读：

- 当前版本在这台 Pixel 5 上 **没有显著改善 app 启动耗时**，平均只改善 0.15%，基本可以视为持平。
- 当前版本对 **内存相关压力有明显收益**：MemAvailable 下降量改善 62.10%，reclaim 改善 56.08%。
- 当前版本的 **CPU busy 和 iowait 略差**，说明后台 eBPF/MAPLE/调度仍然有成本，不能声称全面提升性能。
- 综合压力分数平均改善 35.41%，主要来自内存下降和 reclaim 减少，而不是来自启动变快。

## 分场景结果

| condition | workload | pressure score | 启动 TotalTime | jank rate | 结论 |
| --- | --- | --- | --- | --- | --- |
| normal | Chrome | 31.44 -> 33.85，-7.65% | 262ms -> 269ms，-2.67% | 3.28% -> 3.24%，+1.08% | 普通状态下 Chrome 综合压力略差，但 jank 略好 |
| normal | Magisk | 11.73 -> 15.35，-30.84% | 1003ms -> 890ms，+11.27% | 1.14% -> 1.71%，-50.00% | 启动更快，但综合压力和 jank 更差 |
| normal | Tencent Meeting | 616.46 -> 16.03，+97.40% | 391ms -> 409ms，-4.60% | 15.63% -> 15.63%，0% | 综合压力大幅改善，但启动略慢 |
| crowded | Chrome | 51.02 -> 14.10，+72.37% | 266ms -> 275ms，-3.38% | 2.60% -> 2.20%，+15.42% | crowded 状态下 Chrome 综合压力和 jank 明显改善 |
| crowded | Magisk | 10.69 -> 9.17，+14.24% | 1007ms -> 983ms，+2.38% | 2.30% -> 1.14%，+50.29% | crowded 状态下三项都改善 |
| crowded | Tencent Meeting | 56.25 -> 18.59，+66.95% | 427ms -> 436ms，-2.11% | 37.04% -> 27.59%，+25.52% | crowded 状态下综合压力和 jank 明显改善，启动略慢 |

## 结论

这组实验支持一个更谨慎的结论：

```text
MEMO 当前版本在手机比较“空”的时候收益不稳定；
在 crowded_cached_apps 场景下，MEMO 更容易降低综合系统压力和 jank，
尤其是内存下降量与 reclaim 明显减少。
但 MAPLE/eBPF/调度本身仍有 CPU 和 iowait 成本，
不能说它已经全面提升手机性能。
```

所以当前产品方向是合理的，但下一步优化重点很明确：

- 减少 MAPLE 后台推理对 CPU/IO 的冲击；
- 更严格地在低压力时少做动作，在高压力/crowded 时再做动作；
- 继续增加真实 app workload，尤其是相机、支付、视频这类真机核心功能；
- 把 pressure A/B 作为后续性能主指标，而不是只汇报 pipeline latency。

## 复现实验

安装 APK 后，可在手机 UI 点击：

```text
Run App Pressure A/B Test
```

也可以用 ADB 只负责触发：

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -S -n com.memoos/.MainActivity --es memo_action com.memoos.action.USER_APP_PRESSURE_EXPERIMENT
```

实验逻辑仍然全部在手机内部执行。Host 只是触发和拉文件：

```powershell
& $adb pull /sdcard/MEMO/pressure/latest_pressure_experiment.json docs/real_device_experiments/user_app_pressure/latest_pressure_experiment.json
```
