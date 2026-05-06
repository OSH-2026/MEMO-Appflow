# Jingyi Guo 2026-05-07 工作汇报

GitHub 仓库：<https://github.com/OSH-2026/MEMO-Appflow>

对应提交：`ef0b709`

## 一句话总结

Jingyi Guo 今天做的核心工作是：把 MEMO-Appflow 从依赖电脑端 host 脚本的原型，推进成 Android emulator 内部可运行的端侧产品闭环，并用真实 eBPF 数据跑了实验验证。

整体链路是：

```text
真实 Android app 使用
-> 设备内 eBPF 证据采集
-> 设备内结构化 scenario
-> 设备本地 MAPLE 推理
-> Top-3 真实应用推荐
-> 系统调度动作
-> 真实 eBPF 消融实验结果
```

## 1. 端侧化整套产品 Pipeline

### 做了什么

产品逻辑不再依赖电脑端 Python 脚本。采集、解析、MAPLE scenario 构造、MAPLE 调用、Top-3 app 映射、动作执行、状态保存、Widget 展示都放进 Android app 里。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| 主 pipeline 后台服务 | [`app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt) |
| Pipeline 耗时统计 | [`app/src/main/java/com/memoos/perf/PipelineLatency.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/perf/PipelineLatency.kt) |
| 保存最后一次证据、MAPLE 结果、推荐和动作 | [`app/src/main/java/com/memoos/store/MemoStore.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/store/MemoStore.kt) |
| Android 主界面/控制台 | [`app/src/main/java/com/memoos/MainActivity.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/MainActivity.kt) |
| 今天端侧 pipeline 工作日志 | [`docs/2026-05-07_device_pipeline_worklog.md`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/2026-05-07_device_pipeline_worklog.md) |

### 关键代码片段

真实实验跑完后，服务先把 scenario 写到 Android app 自己的外部目录，然后才进入 MAPLE：

```kotlin
File(getExternalFilesDir(null), "latest_maple_scenario.json").writeText(scenario.scenarioJson)
publishEvidenceThenRunMaple(timer, scenario, state, collected.events.size)
```

在 MAPLE 没跑完之前，不发布 Top-3，也不执行调度动作：

```kotlin
MemoStore(this).savePending(
    scenario = scenario,
    message = "MAPLE inference is running in the background. No Top-3 prediction, widget recommendation, or scheduling action is published until MAPLE returns.",
    latency = pendingLatency,
)
```

这说明当前产品路径里没有“快速预测兜底”。MAPLE 是唯一预测来源。

## 2. 真实 eBPF 采集

### 做了什么

App 在 Android emulator/rooted device 内部启动 bpftrace，并在真实 Android app 交互发生时记录 eBPF 证据。这替代了手写 demo trace、host Python 造数据、纯 synthetic event rows。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| eBPF 能力探测 | [`app/src/main/java/com/memoos/device/EBPFCapabilityProbe.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/device/EBPFCapabilityProbe.kt) |
| bpftrace 程序生成 | [`app/src/main/java/com/memoos/ebpf/BpftraceProgramBuilder.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ebpf/BpftraceProgramBuilder.kt) |
| 设备内 collector/script 部署 | [`app/src/main/java/com/memoos/ebpf/DeviceCollectorDeployer.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ebpf/DeviceCollectorDeployer.kt) |
| eBPF event 数据结构 | [`app/src/main/java/com/memoos/ebpf/EBPFEvent.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ebpf/EBPFEvent.kt) |
| trace 解析器 | [`app/src/main/java/com/memoos/ebpf/EBPFTraceParser.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ebpf/EBPFTraceParser.kt) |
| 真实用户实验规划 | [`app/src/main/java/com/memoos/ebpf/RealUserExperimentPlanner.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ebpf/RealUserExperimentPlanner.kt) |

### 采集到的证据类型

- memory：可用内存、swap、reclaim、PSI、LMKD；
- battery：电量、充电状态、电压、温度；
- network：UDP `sendto` / `recvfrom`，以及 `/proc/net` 状态；
- camera/media：`cameraserver`、MediaCodec、audio/media 状态；
- display/UI：SurfaceFlinger、RenderThread、input/render 活动；
- process/service：前台 app、关键系统服务 PID、Binder 活动。

### 真实 trace 例子

原始 trace 在：

[`docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace)

最后几行类似：

```text
MEMO ... file ... pkill ... 315/cmdline
MEMO ... file ... pkill ... 316/stat
MEMO 0 status 0 0 0 memo 0 0 0 0 collector_stopped
```

完整 trace 文件里有大量真实 Android 运行时产生的 `MEMO_*` 记录。为了让结果更容易读，下面的 scenario 和 ablation report 会把它们汇总。

## 3. 设备内结构化和 MAPLE Scenario 构造

### 做了什么

Raw eBPF rows 在 Android 内部被解析、统计、总结，然后转成 MAPLE 兼容的 scenario JSON。模型收到的不是浅层 app sequence，而是结构化系统证据。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| 系统状态采集 | [`app/src/main/java/com/memoos/state/SystemStateCollector.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/state/SystemStateCollector.kt) |
| 系统状态 schema | [`app/src/main/java/com/memoos/state/SystemStateSnapshot.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/state/SystemStateSnapshot.kt) |
| MAPLE scenario 构造 | [`app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt) |
| 真实 scenario 输出 | [`docs/real_device_experiments/real_ebpf_ablation/latest_maple_scenario.json`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/latest_maple_scenario.json) |

### 结果片段

真实 camera/photo run 生成的 scenario 关键部分：

```json
{
  "id": "real_user_camera_photo_usage",
  "description": "Launch a real installed camera/photo-capable app and record camera/media/display/process evidence from the device.",
  "context": {
    "historical_app_categories": [
      "Network IO",
      "Camera Service",
      "Display Composition",
      "Android Service IPC",
      "Android System Services",
      "APEX Runtime Loading"
    ],
    "memory_pressure": "elevated: memory pressure or reclaim activity observed; reclaim_events=1"
  }
}
```

这说明 MAPLE 输入里已经包含 network、camera、display、Binder/service、memory pressure 等系统证据。

## 4. MAPLE 接入与优化

### 做了什么

MAPLE 是 Android 产品路径里唯一的预测入口。旧的 quick/fallback 预测路径不作为产品预测来源。为了让小模型在设备侧能实际跑出结果，今天做了 prompt 裁剪、parser 修复、Android 端调用修复。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| Android MAPLE 调用总入口 | [`app/src/main/java/com/memoos/maple/MapleInferenceOrchestrator.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/maple/MapleInferenceOrchestrator.kt) |
| 设备本地 MAPLE shell backend | [`app/src/main/java/com/memoos/maple/MapleShellBackend.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/maple/MapleShellBackend.kt) |
| MAPLE prediction 数据结构 | [`app/src/main/java/com/memoos/maple/MaplePrediction.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/maple/MaplePrediction.kt) |
| JNI wrapper | [`app/src/main/cpp/maple_jni.cpp`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/cpp/maple_jni.cpp) |
| MAPLE prompt | [`llm/maple/maple_engine/src/prompt_builder.cpp`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/llm/maple/maple_engine/src/prompt_builder.cpp) |
| MAPLE parser | [`llm/maple/maple_engine/src/result_parser.cpp`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/llm/maple/maple_engine/src/result_parser.cpp) |
| llama backend | [`llm/maple/maple_engine/src/llama_backend.cpp`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/llm/maple/maple_engine/src/llama_backend.cpp) |
| demo executable | [`llm/maple/demo/maple_demo.cpp`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/llm/maple/demo/maple_demo.cpp) |

### 关键代码片段

eBPF prompt 被裁剪成更适合端侧小模型的形式：

```cpp
oss << "Pick the next Android resource-demand category from real MEMO eBPF evidence.\n";
oss << "Answer exactly: <Category> (<percent>%).\n";
oss << "Use only one category name from Candidates.\n";
oss << "Candidates: " << join_categories(ctx.historical_app_categories) << ".\n";
oss << "Counts: ";
```

Stage 2 只接受明确的 `App <id>`，防止把 trace count、Binder code、confidence 数字误判成 app id：

```cpp
std::regex re_app(R"([Aa]pp\s+(\d+))");
if (std::regex_search(result.reasoning, match, re_app)) {
    result.predicted_app_id = std::atoi(match[1].str().c_str());
}
```

## 5. Top-3 真实 App 推荐

### 做了什么

用户看到的是设备上真实可启动的 Android app，不是进程名、Binder 线程、SurfaceFlinger、MediaCodec 这种系统对象。App 会扫描已安装 launcher app，并把 MAPLE 的 category/app-id 输出映射成真实 package。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| 自适应 app scanner / mapper | [`app/src/main/java/com/memoos/action/AppIdMapping.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/action/AppIdMapping.kt) |
| 推荐结果保存 | [`app/src/main/java/com/memoos/store/MemoStore.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/store/MemoStore.kt) |
| 真实输出状态 | [`docs/real_device_experiments/real_ebpf_ablation/memo_pipeline.xml`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/memo_pipeline.xml) |

### 真实运行结果

这次真实 run 的 Top-3 是：

```text
Photos, Chrome, Camera
```

对应 package：

```json
["com.google.android.apps.photos", "com.android.chrome", "com.android.camera2"]
```

## 6. 系统动作执行层

### 做了什么

MAPLE 输出不会只停留在文本建议，而是进入 `ActionExecutor` 转成调度动作。动作包括 Widget 更新、内存压力响应、网络优先级、camera/media 策略、display/UI 策略、Binder/service 策略、非侵入式 warm-launch 决策。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| 系统动作执行 | [`app/src/main/java/com/memoos/action/ActionExecutor.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/action/ActionExecutor.kt) |
| root 命令执行 | [`app/src/main/java/com/memoos/device/RootShell.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/device/RootShell.kt) |
| root bridge client | [`app/src/main/java/com/memoos/device/RootBridgeClient.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/device/RootBridgeClient.kt) |
| 设备路径定义 | [`app/src/main/java/com/memoos/device/DevicePaths.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/device/DevicePaths.kt) |

### 真实 run 里的动作例子

```text
widget_update -> published 3 real app recommendations
latency_policy -> MAPLE prediction ran asynchronously
memory_policy -> memory=elevated; reducing warm launch aggressiveness
network_candidate_priority -> UDP sendto/recvfrom evidence prioritizes network-capable apps
camera_media_candidate -> selected Camera for camera/media follow-up warm launch
display_ui_policy -> keep prewarm count low to avoid jank
binder_service_policy -> Binder/system-service evidence kept as MAPLE scheduling context
warm_launch -> skipped in non-intrusive background mode
```

这里的重点是：MAPLE 结果已经能影响系统调度策略，而不只是输出一句预测。

## 7. Widget 和产品展示

### 做了什么

普通用户看到的是 Top-3 推荐应用。eBPF 原始证据、trace、debug JSON 不直接展示给用户，而是放在诊断文件和实验报告里。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| Widget provider | [`app/src/main/java/com/memoos/widget/MemoWidgetProvider.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/widget/MemoWidgetProvider.kt) |
| Widget layout | [`app/src/main/res/layout/widget_memo_recommend.xml`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/res/layout/widget_memo_recommend.xml) |
| Widget metadata | [`app/src/main/res/xml/memo_widget_info.xml`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/res/xml/memo_widget_info.xml) |
| Widget background | [`app/src/main/res/drawable/widget_panel_bg.xml`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/res/drawable/widget_panel_bg.xml) |

## 8. 真实 eBPF 消融实验

### 做了什么

实验从一个真实 Android-side eBPF camera/photo scenario 出发，分别删掉不同证据族，再重新跑 MAPLE：

- `full_real_ebpf`
- `no_network`
- `no_camera_media`
- `no_display_ui`
- `no_binder_service`
- `no_memory`
- `counters_only`
- `app_sequence_baseline`

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| 消融实验实现 | [`app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt) |
| 真实实验文件夹 | [`docs/real_device_experiments/real_ebpf_ablation/`](https://github.com/OSH-2026/MEMO-Appflow/tree/main/docs/real_device_experiments/real_ebpf_ablation) |
| 消融结果 JSON | [`docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json) |
| 结果解读文档 | [`docs/real_device_experiments/real_ebpf_ablation/real_ebpf_ablation_result_interpretation.md`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/real_ebpf_ablation_result_interpretation.md) |
| 原始 eBPF trace | [`docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace) |

## 9. 实验结果与结果分析

### 9.1 Full eBPF 对比 App-Sequence Baseline

| Metric | App-sequence baseline | Full real eBPF | Change |
| --- | ---: | ---: | ---: |
| MAPLE latency | 69.104s | 36.468s | -32.636s, 47.2% faster |
| End-to-end latency | 74.343s | 41.991s | -32.352s, 43.5% faster |
| Stage 1 category | Camera Service | Display Composition | changed |
| Predicted app id | 25 | 115 | changed |
| Top-1 app | `com.android.camera2` | `com.google.android.apps.photos` | changed |
| Task-domain hit rate | 100.0% | 100.0% | tied |

### 9.2 每个配置的结果

以下表格从 [`latest_real_ablation.json`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json) 提取：

| Config | Predicted id | Stage 1 | Top-1 app | Predicted domains | MAPLE latency | End-to-end | Stage1 overlap vs full | Top-3 overlap vs full |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: |
| `full_real_ebpf` | 115 | Display Composition | `com.google.android.apps.photos` | display_ui, network, camera_media | 36.468s | 41.991s | 1.00 | 1.00 |
| `no_network` | 115 | Display Composition | `com.google.android.apps.photos` | display_ui, camera_media, binder_service | 41.698s | 45.174s | 1.00 | 0.50 |
| `no_camera_media` | 115 | Display Composition | `com.google.android.apps.photos` | display_ui, network, binder_service | 27.747s | 30.135s | 1.00 | 0.50 |
| `no_display_ui` | 25 | Camera Service | `com.android.camera2` | camera_media, network, binder_service | 67.407s | 72.949s | 0.00 | 0.50 |
| `no_binder_service` | 25 | Camera Service | `com.android.camera2` | camera_media, network, display_ui | 55.533s | 59.676s | 0.00 | 1.00 |
| `no_memory` | 115 | Display Composition | `com.google.android.apps.photos` | display_ui, network, camera_media | 30.234s | 32.530s | 1.00 | 1.00 |
| `counters_only` | 115 | Display Composition | `com.google.android.apps.photos` | display_ui, network, camera_media | 36.416s | 41.324s | 1.00 | 1.00 |
| `app_sequence_baseline` | 25 | Camera Service | `com.android.camera2` | camera_media, network, display_ui | 69.104s | 74.343s | 0.00 | 1.00 |

### 9.3 结果怎么读

**第一，Full eBPF 相比 app sequence baseline 更符合我们的产品目标。**

App-sequence baseline 预测的是 `Camera Service / com.android.camera2`。如果只做“下一个 app 分类”，这个结果看起来也合理，因为用户刚刚用了 camera/photo 相关 app。

但我们的目标不是简单复读当前 app，而是做资源感知预测和调度。Full eBPF 预测到 `Display Composition`，Top-1 映射到 `Photos`，Top-3 是 `Photos / Chrome / Camera`。这更像真实 camera/photo workflow 的后续需求：看照片、分享/浏览、继续相机相关操作。

**第二，Full eBPF 相比 app sequence baseline 更快。**

```text
MAPLE latency: 69.104s -> 36.468s = 47.2% faster
End-to-end:    74.343s -> 41.991s = 43.5% faster
```

这句话要严谨：Full eBPF 不是所有 ablation 里最快的，因为 `no_camera_media`、`no_memory` 这类配置删掉了信息，prompt 更短，所以会更快。公平对比对象是 app-sequence baseline。

**第三，Display/UI 和 Binder/service 是这次最关键的证据族。**

删掉 `display/UI` 后：

```text
Stage 1: Display Composition -> Camera Service
Top-1: Photos -> Camera
End-to-end: 41.991s -> 72.949s
```

删掉 `Binder/service` 后：

```text
Stage 1: Display Composition -> Camera Service
Top-1: Photos -> Camera
End-to-end: 41.991s -> 59.676s
```

这说明在这个 camera/photo run 里，display/UI 和 Binder/service 证据确实影响了 MAPLE 的判断。

**第四，Network 和 camera/media 也有作用，但不一定改变 Top-1。**

删掉 network 或 camera/media 后，Top-1 还是 Photos，但 Top-3 overlap 从 `1.00` 降到 `0.50`。这说明它们影响推荐集合和调度上下文。

**第五，Memory 主要影响调度安全，而不是这次的预测类别。**

删掉 memory 后预测没有变。这不说明 memory 没用，而是说明在这次实验里 memory 更像调度约束：决定 warm launch 要不要激进、要不要 trim-memory、要不要 idle maintenance。

### 9.4 关于预测准确率

这次是真实用户操作实验，没有人工标注的“下一步 app ground truth”，所以不能伪造传统 supervised accuracy。

我们用了两个更适合这个实验的指标：

1. **Task-domain hit rate**
   对 camera/photo 场景，预测是否覆盖 expected resource domains：`camera_media`、`display_ui`、`network`。

2. **Full-eBPF stability**
   去掉某类证据后，预测是否还能保持 full eBPF 的 Stage1、Top-1、Top-3 和 action-domain 结果。

Task-domain hit rate 最好的配置是：

```text
full_real_ebpf
no_binder_service
no_memory
counters_only
app_sequence_baseline
```

它们都是 `100.0%`。

但如果看“是否保持 full eBPF 的产品预测”，最稳的是：

```text
full_real_ebpf
no_memory
counters_only
```

`no_display_ui`、`no_binder_service`、`app_sequence_baseline` 都会把 Stage1 / Top-1 带回 Camera，所以它们对我们的资源调度目标来说更弱。

## 10. 文档和实验归档

### 做了什么

没有覆盖 Chengyu Fan 的理论消融实验，而是把两条实验线分开：

| Track | Owner | File |
| --- | --- | --- |
| 理论/合成消融实验 | Chengyu Fan | [`docs/theoretical_ablation_experiment/`](https://github.com/OSH-2026/MEMO-Appflow/tree/main/docs/theoretical_ablation_experiment) |
| 真实设备/eBPF 消融实验 | Jingyi Guo | [`docs/real_device_experiments/real_ebpf_ablation/`](https://github.com/OSH-2026/MEMO-Appflow/tree/main/docs/real_device_experiments/real_ebpf_ablation) |
| 设备端 pipeline 工作日志 | Jingyi Guo | [`docs/2026-05-07_device_pipeline_worklog.md`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/2026-05-07_device_pipeline_worklog.md) |
| 真实 eBPF 实验设计 | Jingyi Guo | [`docs/real_user_ebpf_experiments.md`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_user_ebpf_experiments.md) |
| 设备端 eBPF/MAPLE 报告 | Jingyi Guo | [`docs/ebpf_report.md`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/ebpf_report.md) |

## 11. 验证

### 通过的 build 命令

Android APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

MAPLE Android x86_64 engine/demo：

```powershell
cmake --build .\llm\maple\maple_engine\build-android-x86_64 --target maple_demo maple_engine -j 4
```

### 真实设备侧运行命令

运行真实 camera/photo eBPF 实验：

```powershell
adb shell am start-foreground-service -n com.memoos/.ebpf.EBPFCollectorService -a com.memoos.action.REAL_EXPERIMENT_CAMERA
```

基于最新 Android-generated scenario 跑真实 eBPF 消融：

```powershell
adb shell am start-foreground-service -n com.memoos/.ebpf.EBPFCollectorService -a com.memoos.action.REAL_EBPF_ABLATION_LATEST
```

## 最后总结

Jingyi Guo 的贡献可以概括为工程产品化 + 真实系统实验：

```text
eBPF evidence
-> MAPLE reasoning
-> Top-3 real app recommendation
-> Android scheduling actions
```

这条链路现在已经作为 Android emulator 内部产品路径跑通，并用真实 eBPF 消融实验验证：在当前 camera/photo 场景中，真实 eBPF 证据比浅层 app-sequence baseline 更能支撑资源感知预测与调度。

