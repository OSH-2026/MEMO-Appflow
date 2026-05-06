# Jingyi Guo 2026-05-07 工作汇报

GitHub 仓库：<https://github.com/OSH-2026/MEMO-Appflow>

核心实现提交：`ef0b709`

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

### 什么是“真实用户操作实验”

这里的“真实用户操作实验”不是说一定要人工拿着手机连续使用很久，而是说证据必须来自 Android 系统在真实 app 操作中自然产生的内核/系统行为。实验时 collector 先在 emulator/rooted device 里挂上 eBPF，然后启动真实安装的 Android app，并让系统自然产生进程启动、Binder、文件访问、display、network、camera/media 等事件。

所以这个实验和 synthetic demo 的区别是：

| 对比项 | Synthetic demo | 真实用户操作实验 |
| --- | --- | --- |
| 事件来源 | 手写 JSON、手写 trace、host Python 构造 | Android 内部真实 bpftrace/eBPF 输出 |
| app 来源 | 可以是假名字或固定序列 | 设备上真实安装、可启动的 app |
| 系统状态 | 通常是伪造字段 | 从 `/proc`、`dumpsys`、battery/network/display/service 等设备状态读取 |
| MAPLE 输入 | 人工设计的 scenario | 由真实 trace 解析、聚合出的 scenario |
| 调度动作 | 不一定执行 | Android app 内部根据 MAPLE 结果执行/规划动作 |

如果实验里用了 `adb shell input` 或 service action，它只是模拟用户点击、滑动、启动 app 这些动作；它不直接写 eBPF 记录，也不伪造证据。eBPF 行仍然来自系统运行时自然产生的 trace。

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

### 什么是 JNI，这里为什么会出现 JNI

JNI 是 Java Native Interface。简单说，它是 Android/Kotlin/Java 调用 C/C++ 代码的桥。Android app 主体是 Kotlin 写的，但 MAPLE engine 和 llama.cpp 是 C++ 代码，所以如果要让 app 进程直接调用 C++ 推理引擎，中间就需要 JNI。

在我们项目里的关系是：

```text
Kotlin Android app
-> MapleNative.kt
-> JNI wrapper: maple_jni.cpp
-> C++ MAPLE engine / llama.cpp
-> 返回 category、App <id>、reasoning 文本
```

这次真实 run 里已经验证的主路径是 `MapleShellBackend.kt` 调设备内 `/data/local/tmp/memo/maple_demo`，也就是在 Android 设备内部用 shell backend 跑 MAPLE。`maple_jni.cpp` 是后续更正式的 app-native 集成接口：目标是把 MAPLE 作为 `.so` 直接加载进 Android app，而不是长期依赖启动外部 executable。

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

### 这些系统动作是怎么实现的

实现上，`EBPFCollectorService` 在 MAPLE 返回后，把 `scenario + prediction + Top-3 recommendations + system state` 交给 `ActionExecutor`。`ActionExecutor` 不重新预测，只做动作决策：根据 MAPLE 判断出的资源类别、设备状态和 Top-3 app，决定哪些动作可以执行、哪些动作应该降低强度、哪些动作只记录为调度计划。

需要 root 的动作通过 `RootShell` 执行，命令形式是：

```text
su 0 sh -c "<command>"
```

这和未来真机迁移的假设一致：真机需要 root/Magisk/可执行 bpftrace 权限，否则不能完整跑 eBPF 和部分系统调度动作。

各个动作在系统层面的含义是：

| 动作 | 系统级别做了什么 | 为什么有用 |
| --- | --- | --- |
| `widget_update` | 用 `MemoWidgetProvider` / `AppWidgetManager` 发布 Top-3 真实 app。 | 用户看到的是可点击 app 推荐，而不是 eBPF debug JSON。 |
| `latency_policy` | MAPLE 在后台线程异步跑，前台用户继续使用设备。 | 推理慢也不阻塞用户操作；结果回来后再更新推荐和动作。 |
| `memory_policy` | 根据 memory pressure 调整 warm launch 激进程度；必要时触发 `cmd activity idle-maintenance`、`am send-trim-memory`，critical 时可做 page-cache 级 `drop_caches`。 | 内存紧张时不要为了预热把系统压垮；优先保留主推荐，压低次要预热。 |
| `thermal_policy` | 根据 battery/thermal 状态降低预热强度，root 可尝试关闭 fixed performance mode。 | 避免设备发热、电量状态差时继续做重调度。 |
| `network_candidate_priority` | 当 eBPF 看到 UDP `sendto/recvfrom` 或 network evidence 时，把 network-capable app 放进更高推荐/调度优先级；root 下可刷新 `dumpsys netstats`。 | 这不是 Linux QoS 改包优先级，而是产品调度层面的候选 app 优先级。 |
| `camera_media_candidate` | 当 camera/media evidence 明显时，挑选 Camera/Media 类 app 作为后续 warm-launch 候选。 | 例如用户在通信/照片流程后，很可能进入拍摄、预览、分享。 |
| `display_ui_policy` | 当 SurfaceFlinger/RenderThread/input 活跃时，减少预热数量。 | 用户正在滚动/刷新 UI 时，过度 warm launch 可能引入 jank。 |
| `binder_service_policy` | Binder/system-service evidence 保留为 MAPLE 和 action context；root 下可查询 `service list`。 | Binder 活跃说明系统服务参与度高，适合影响调度而不是直接展示给用户。 |
| `warm_launch` | 如果允许可见预热，会执行 `am start -W -n <component>; sleep 1; HOME`，让 app 进入缓存；本次真实 run 是 non-intrusive mode，所以跳过。 | 预热能降低后续启动冷启动成本，但真实用户实验里默认不打断用户界面。 |

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

### 实验背景：这次模拟了什么

这次 ablation 不是泛泛地测一个离线模型，而是围绕一个具体产品场景：用户在 Android 设备上打开真实 camera/photo-capable app，系统自然产生 camera/media、display/UI、network、Binder/service、memory 等证据，然后 MEMO-Appflow 根据这些证据判断下一步更应该为哪些真实 app 和资源做准备。

本次最新结果的 source scenario 是 `real_user_camera_photo_usage`。从结果 JSON 里的描述看，目标 app 是设备上真实安装的 Camera：

```text
Target app: Camera (com.android.camera2)
Raw trace: /sdcard/MEMO/logs/real_user_1778095657670.trace
```

也就是说，它模拟的是一个常见 camera/photo workflow：

```text
用户打开 Camera / photo-capable app
-> Android 启动真实 app 和相关系统服务
-> eBPF 捕捉进程、Binder、文件、display、network、camera/media 证据
-> Android 端构造 MAPLE scenario
-> MAPLE 判断下一步资源/app 倾向
-> AppIdMapping 转成 Top-3 真实 app
-> ActionExecutor 规划 widget、预热、内存、网络、display、service 动作
```

这个实验想验证的不是“能不能识别用户刚打开了 Camera”，而是：加入深层 eBPF 系统证据后，MAPLE 是否能给出更适合资源调度的预测；删掉某类证据后，预测和动作会不会改变。

### 实验环境

本次结果按 Android emulator 端侧产品部署方式运行，环境假设和未来 rooted Android 真机一致：

| 项 | 本次实验环境 |
| --- | --- |
| 运行设备 | Android emulator，按 rooted Android 真机路径部署 |
| 产品 app | `com.memoos` Android app |
| 权限模型 | `su 0 sh -c ...` 执行 eBPF、MAPLE executable 和部分系统动作 |
| eBPF 工具路径 | `/data/local/tmp/memo/bpftrace`、`/data/local/tmp/memo/bpftool` |
| trace 脚本路径 | `/data/local/tmp/memo/memo_appflow_generated.bt` |
| 原始 trace 输出 | `/sdcard/MEMO/logs/real_user_*.trace` |
| MAPLE executable | `/data/local/tmp/memo/maple_demo` |
| MAPLE model | `/data/local/tmp/memo/models/Qwen3.5-0.8B-Q4_K_M.gguf` |
| scenario 输出 | `/sdcard/MEMO/scenarios/latest_maple_scenario.json` 和 app external files |
| host 的作用 | 安装 APK、触发 service、拉取结果文件；不负责产品逻辑 |

所以这次实验不是“电脑上 Python 处理完再给 Android 看结果”。采集、解析、scenario、MAPLE、Top-3、ActionExecutor 都在 Android 侧路径里完成。

### 对应 GitHub 文件

| 作用 | 文件 |
| --- | --- |
| 消融实验实现 | [`app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/app/src/main/java/com/memoos/ablation/RealEbpfAblationRunner.kt) |
| 真实实验文件夹 | [`docs/real_device_experiments/real_ebpf_ablation/`](https://github.com/OSH-2026/MEMO-Appflow/tree/main/docs/real_device_experiments/real_ebpf_ablation) |
| 消融结果 JSON | [`docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json) |
| 结果解读文档 | [`docs/real_device_experiments/real_ebpf_ablation/real_ebpf_ablation_result_interpretation.md`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/real_ebpf_ablation_result_interpretation.md) |
| 原始 eBPF trace | [`docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace`](https://github.com/OSH-2026/MEMO-Appflow/blob/main/docs/real_device_experiments/real_ebpf_ablation/real_user_1778095657670.trace) |

### 消融实验 metrics 解释

这次 metrics 不是只看传统“预测准确率”，而是围绕产品闭环设计：MAPLE 预测是否变化、推荐给用户的真实 app 是否变化、系统动作是否变化、耗时是否可接受。

| Metric | 含义 | 为什么要看 |
| --- | --- | --- |
| `Predicted id` | MAPLE Stage 2 输出的 `App <id>`。 | 看删掉某类 eBPF 证据后，模型最终预测对象是否改变。 |
| `Stage 1` | MAPLE Stage 1 输出的资源/系统需求类别，例如 `Display Composition`、`Camera Service`。 | 我们关注的是资源感知调度，不只是 app 序列，所以 Stage 1 很关键。 |
| `Top-1 app` | `AppIdMapping` 把 MAPLE 输出映射到设备真实 app 后的第一推荐。 | 用户实际看到和能点击的是 app，不是 MAPLE 内部 id。 |
| `Predicted domains` | 把 Stage 1 / Top-3 category 归一化成 `display_ui`、`network`、`camera_media` 等调度 domain。 | 用来判断系统动作该偏向显示、网络、相机、内存还是服务侧。 |
| `MAPLE latency` | 设备侧 MAPLE 推理耗时。 | 用户使用时推理可以异步，但耗时仍影响推荐更新速度。 |
| `End-to-end latency` | MAPLE 推理 + app mapping + action planning 的总耗时。 | 这是从证据变成可用产品结果的完整延迟。 |
| `Stage1 overlap vs full` | 当前 ablation 的 Stage 1 和 full eBPF Stage 1 的重合度。 | 衡量删掉证据后是否偏离完整 eBPF 判断。 |
| `Top-3 overlap vs full` | 当前 ablation 的 Top-3 真实 app 和 full eBPF Top-3 的 Jaccard overlap。 | 衡量用户最终看到的推荐集合是否稳定。 |
| `Task-domain hit rate` | 对 camera/photo 任务，预测 domain 是否覆盖 expected domains：`camera_media`、`display_ui`、`network`。 | 没有人工 next-app label 时，用它衡量预测是否覆盖该任务需要的关键资源。 |
| `scheduler/action metrics` | action 数量、状态、耗时、action domain 和 full eBPF 的 overlap。 | 调度动作虽然难用一个“准确率”衡量，但可以看动作是否执行、是否跳过、是否和预测 domain 对齐。 |

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

为什么没有 ground truth：

1. 真实用户操作窗口只记录“这段时间系统发生了什么”，并没有要求用户在窗口结束后必须打开某一个指定 app。
2. camera/photo workflow 的合理后续可能不止一个：继续拍照、打开相册、分享、浏览网络内容都可能成立。
3. 如果我们事后硬指定一个唯一正确答案，会把真实系统实验变成主观标注实验，反而削弱 eBPF 证据的意义。

所以这里的结论不能写成“Full eBPF 的 next-app accuracy 是多少”。更严谨的写法是：在没有人工 next-app label 的真实用户操作实验中，我们比较 full eBPF 和各个 ablation 在资源 domain、Top-3 推荐、Stage 1 判断、动作 domain 和延迟上的差异。

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
