# 真实用户 App 压力 A/B 实验

日期：2026-06-01

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
| app 启动 TotalTime | +9.69% |
| app WaitTime | +9.46% |
| 综合压力分数 | +37.19% |
| CPU busy | +1.45% |
| iowait | +35.77% |
| MemAvailable 下降量 | -51.75% |
| reclaim | +68.14% |

解读：

- 当前版本在这台 Pixel 5 上平均 **降低了综合系统压力 37.19%**，6 个 workload 里 5 个综合压力改善。
- app 启动 TotalTime 平均改善 9.69%，WaitTime 平均改善 9.46%。平均值为正，但仍然要看分场景，不应该只报平均值。
- CPU busy 改善 1.45%，iowait 改善 35.77%，说明 MEMO-on 的调度准备没有在这次实验里造成更高的平均 CPU/IO 压力。
- MemAvailable 下降量平均 -51.75%，这是明显变差项，说明有些 workload 下 MEMO-on 会保留更多缓存或引入额外内存占用。
- reclaim 平均改善 68.14%，但 Tencent Meeting crowded 仍是综合压力反例，所以不能声称每个场景都改善。

## 分场景结果

| condition | workload | pressure score | 启动 TotalTime | jank rate | 结论 |
| --- | --- | --- | --- | --- | --- |
| normal | Chrome | 54.11 -> 30.87，+42.96% | 428ms -> 286ms，+33.18% | 2.55% -> 3.26%，-27.83% | 综合压力和启动改善，jank 变差 |
| normal | Magisk | 18.29 -> 17.25，+5.67% | 271ms -> 341ms，-25.83% | 1.15% -> 1.14%，+1.14% | 综合压力略改善，启动变慢 |
| normal | Tencent Meeting | 21.16 -> 20.52，+3.03% | 449ms -> 474ms，-5.57% | 24.14% -> 21.88%，+9.37% | 综合压力和 jank 略改善，启动变慢 |
| crowded | Chrome | 256.03 -> 20.84，+91.86% | 310ms -> 338ms，-9.03% | 2.14% -> 2.01%，+6.03% | crowded 状态下综合压力大幅改善，启动变慢 |
| crowded | Magisk | 230.44 -> 16.72，+92.75% | 1061ms -> 264ms，+75.12% | 1.14% -> 1.72%，-50.86% | 综合压力和启动明显改善，jank 变差 |
| crowded | Tencent Meeting | 23.43 -> 26.50，-13.12% | 464ms -> 509ms，-9.70% | 33.33% -> 23.08%，+30.77% | 这是反例：jank 改善，但综合压力和启动变差 |

## 结论

这组实验支持一个更谨慎的结论：

```text
MEMO 当前版本在这次 Pixel 5 实验中平均能降低综合系统压力，
并且启动耗时、iowait、reclaim 都有平均改善。
但效果不是所有 workload 都稳定：crowded Tencent Meeting 是明确反例，
MemAvailable 下降量平均也明显变差。
所以可以说“当前真实实验支持 MEMO 有性能收益”，
但不能说“所有场景都提升”。
```

所以当前产品方向是合理的，但下一步优化重点很明确：

- 继续减少 MAPLE 后台推理对 CPU/IO 的冲击；
- 更严格地根据 eBPF 证据和压力指标选择动作强度；
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
