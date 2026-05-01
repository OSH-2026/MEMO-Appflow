# MEMO-Appflow 可行性报告

---

## 一、项目概述

MEMO-Appflow 是一个面向 Android 的上下文感知智能资源管理系统。项目核心目标是通过融合应用切换时序、时间规律、系统状态、用户交互模式与场景上下文等多模态语义信息，构建对用户意图的深度理解框架，预测下一个可能被打开的应用，并据此主动向操作系统发出资源调度意图（预加载、保活、回收），最终缩短应用冷启动延迟、提升系统整体流畅度。

本报告从操作系统视角出发，评估项目当前已完成调研的技术路径、已落地的功能模块，以及向更深系统层扩展的可行性。AI 预测模型作为策略上游，其可行性在第五节简要评估。

---

## 二、已完成调研并确定可行的技术路径

经过对 Android 系统机制、相关论文（AppFlow、ATPP）及公开数据集（LSApp）的系统调研，以下技术路径已确认在当前平台约束下可行：

### 2.1 应用层采集与执行路径

- **UsageStats 采集**：Android 公开 API `UsageStatsManager` 可在无需 root 的情况下获取应用启动历史，数据粒度为包名级别，足以支撑应用切换序列建模与在线预测闭环。
- **Intent 级执行**：普通应用可通过 `startActivity` 发送启动 Intent 模拟预加载行为，并通过 `ActivityManager.getMemoryInfo()` 获取内存快照作为策略输入。

### 2.2 系统级扩展架构

项目已采用**分层隔离**的架构设计，确保当前 App 级原型与未来系统级集成可平滑过渡：

- `MemoSystemServiceFacade` 作为系统执行的唯一协调入口；
- `SystemBridge` 接口屏蔽了执行细节，当前实现为 `AppLevelSystemBridge`，未来可替换为 `BinderSystemBridge` 或 `NativeDaemonBridge`；
- `NativeSystemBridge` 与 JNI/C++ 层已预留，可作为特权代码的植入点；
- `PrewarmController`、`RetentionController` 当前记录执行意图，接口稳定后可接入真实的内核预读或 LMK 调控逻辑。

### 2.3 预测模型基线

- **Markov 转移矩阵**：实现简单、推理零延迟，已验证可在设备资源紧张时作为可靠回退基线。
- **Transformer 推理**：C++ 端推理引擎已完成，在 LSApp 上验证通过，具备接入条件。

### 2.4 资源调度策略

- **Threshold 策略**：基于分数阈值直接映射为 keep/prewarm/hint 决策，逻辑透明，已完全可用。
- **AppFlow 策略**：论文中的三模块逻辑（预加载预算、压力模式、Kill Candidate 排名）已映射为代码模型，具备在回放和演示模式下运行的条件。

---

## 三、已实现功能

截至目前，项目已在一个可构建、可运行、可演示的 Android 工程中实现了以下功能模块：

### 3.1 数据采集层

- **在线采集**：`UsageStatsCollector` 实时采集设备应用启动事件，自动过滤系统包、启动器及 MEMO 自身，存入 Room 数据库。
- **离线回放**：支持 CSV / JSON 格式公开数据集导入，内置 `DatasetRegistry`、`PublicDatasetManager`、`DatasetSplitManager`，实现训练/验证/测试集分割与 temporal replay。
- **数据去重**：对重复轮询事件进行去重，防止数据库膨胀。

### 3.2 数据层与存储

- **统一 Schema**：所有来源归一化为 `AppEvent`（包名、时间戳、星期、小时、来源类型）。
- **Room 数据库**：持久化存储应用事件、预测结果与实验记录。
- **数据集缓存**：内置示例数据集在应用启动时自动 seed 到内部存储，确保模拟器/真机均可直接运行回放模式。

### 3.3 预测层

- **MarkovPredictor**：基于转移矩阵的预测引擎，支持 top-k 输出，已接入 JNI 分数归一化。
- **FrequencyPredictor**：基于使用频次的基线模型，用于对比实验。
- **TransformerPredictor（C++）**：已完成独立推理库，支持 addEvent → predict 流式接口，具备 KV Cache 管理与滑动窗口机制。
- **JNI 桥接**：`NativeScoreBridge` 提供 `normalize` 与 `mergeThresholds` 两个原生方法，已在 Markov 预测和 Threshold 策略的实时路径上运行。

### 3.4 策略层

- **ThresholdPolicyEngine**：成熟的阈值策略，输出 `ResourceDecision`（keepAlive / prewarm / hint）。
- **AppFlowPolicyEngine**：已完整实现，包含：
  - 内存压力模式解析（NORMAL / EFFICIENCY_FIRST / REBALANCE）
  - 基于 `LaunchProfileRepository` 的预加载预算控制（默认 100 MB）
  - 近期使用窗口（30 分钟）内的保活与延迟杀死逻辑
  - Kill Candidate 按 `deltaMemory = bloatedBackground - relaunchBaseline` 排序
- **BudgetAwarePolicyEngine / EnergyAwarePolicyEngine**：占位实现，提供策略扩展接口。

### 3.5 系统执行层

- **MemoSystemServiceFacade**：协调系统执行的唯一入口，聚合 App 级桥接与 Native 桥接的执行报告。
- **AppLevelSystemBridge**：通过 Intent 启动应用，记录执行状态到 `SystemStatusRepository`。
- **NativeSystemBridge**：JNI/C++ seam，为未来特权执行预留接口。
- **PrewarmController / RetentionController**：当前记录系统意图并输出结构化日志，接口已 ready，待接入真实系统钩子。
- **MemoryMonitor / BatteryMonitor**：实时采集内存余量、低内存标志与电池电流，为策略提供运行时上下文。

### 3.6 评估与导出层

- **实验记录**：`ExperimentRecorder` 自动记录每次预测-策略-执行循环的结果。
- **指标计算**：支持 Hit@1、Hit@3、MRR、keepAliveCount、prewarmCount、hintCount 等预测与决策指标。
- **多格式导出**：支持 CSV、JSON、Markdown 三种格式的实验报告导出。
- **Replay 评估**：完整支持端到端数据集回放，从选择数据集 → 归一化 → 时间窗口切片 → 预测 → 策略 → 评估 → 导出全自动运行。

### 3.7 UI 与基础设施

- **MainActivity**：在线模式控制台，展示实时预测、系统计划、内存遥测与最近活动 timeline。
- **DashboardActivity**：Replay Lab，支持模式切换、算法切换、数据集准备、回放触发与结果导出。
- **Widget**：桌面小部件展示 top-3 预测与系统执行摘要。
- **SettingsActivity**：展示当前完整配置摘要。
- **WorkManager 定时任务**：CollectWorker / PredictWorker / PolicyWorker / WidgetRefreshWorker，支持后台周期性流水线。
- **Gradle + CMake 构建**：已验证 `gradlew build` 通过，debug APK 可直接安装到 Android Emulator（AVD `MEMO_OS_API34`）。

---

## 四、操作系统层面可行性分析

### 4.1 当前沙箱内的可行性

在普通 Android 应用权限下，MEMO-Appflow 已经能够完成以下闭环：

```
UsageStats 采集 → Room 存储 → 预测 → 策略决策 → Intent 启动 → 内存/电量快照 → 实验记录 → UI/Widget 展示
```

这意味着：
- **算法逻辑可验证**：预测模型和调度策略的正确性可以在真机/模拟器上直接验证。
- **用户感知可量化**：通过记录启动延迟、决策命中率和资源开销，可以给出可复现的实验数据。
- **演示与教学可用**：无需 root 或刷机即可向评审展示完整的端到端流程。

### 4.2 JNI/C++ 层的系统级 Seam

项目并非纯 Kotlin 应用，而是在关键路径上保留了 JNI/C++ 介入点：

- `native_score_utils.cpp`：分数归一化与阈值合并，已在实时路径运行；
- `native_policy_utils.cpp`：策略侧原生计算；
- `memo_native_bridge.cpp`：JNI 注册与桥接；
- `NativeSystemBridge.kt`：Kotlin 侧对 native seam 的封装。

这些原生代码当前执行的是轻量级数值计算，但其存在使得未来可以在同一接口下植入特权逻辑（如通过 `/proc` 读取内存页状态、通过 `ioctl` 与内核驱动通信），而无需重写上层策略代码。

### 4.3 向特权层扩展的路径

基于当前架构，项目向更深系统层扩展的路径已规划清晰：

| 当前阶段 | 已具备条件 | 下一阶段目标 | 所需权限/环境 |
|---------|-----------|------------|-------------|
| App 级原型 | Intent 启动、UsageStats、内存快照 | 模拟系统调度逻辑，验证策略有效性 | 普通 APK |
| JNI/Daemon 扩展 | NativeSystemBridge、C++ 计算库 | 接入 native daemon，读取更细粒度的系统状态 | root / ADB |
| Framework 集成 | 稳定的 `Predictor` / `PolicyEngine` / `ResourceDecision` 接口 | 通过 Binder 与自定义系统服务通信，影响 LMK 决策 | 自定义 ROM / AOSP 编译 |
| Kernel 级优化 | 已验证的 AppFlow 调度逻辑 | 实现真实的文件页预读、内存页保护、回收策略替换 | 内核源码修改 / KModule |

**与 AppFlow 论文的三模块映射**：

- **选择性文件预加载**：当前通过 `LaunchProfileRepository` 维护每个应用的冷启动耗时、热点文件大小和预算，策略层已能按预算输出 `protectedPackages`；未来可通过 `posix_fadvise` 或自定义内核模块实现真实页预读。
- **自适应内存回收**：当前通过 `MemoryMonitor` 获取 `availableMb` / `thresholdMb`，策略层输出 `reclaimMode`（NORMAL / EFFICIENCY_FIRST / REBALANCE）；未来可接入内核 `vmpressure` 或替换 LMK 评分函数。
- **上下文感知进程杀死**：当前已计算 `KillCandidate` 并按 `reclaimBenefitMb` 排序，输出 `deferredKillPackages`；未来可在 root/AOSP 环境下执行真实的 `ActivityManager.killBackgroundProcesses` 或调整 `oom_adj`。

### 4.4 架构稳定性的保障

为确保系统层升级时上层研究代码不被迫重写，以下契约已被设计为稳定接口：

- `Predictor`：预测器只接收 `List<AppEvent>`，输出 `PredictionBatch`；
- `PolicyEngine`：策略引擎只接收 `PredictionBatch` 与 `MemoConfig`，输出 `ResourceDecision`；
- `SystemBridge`：执行层只接收 `ResourceDecision`，输出执行报告；
- `MemoSystemServiceFacade`：协调层固定为策略到执行的转换枢纽。

这种分层隔离意味着：即使未来将 `AppLevelSystemBridge` 替换为 `BinderSystemBridge`，预测器和策略引擎的代码无需任何修改。

---

## 五、eBPF 与系统级可观测性钩子

### 5.1 已实现的功能

项目在 `scripts/tracing_the_processes/` 目录下已完成一套跨平台系统追踪脚本，作为操作系统级信号采集的原型验证：

- **`trace_exec.bt`（bpftrace / Linux）**：挂载 `tracepoint:syscalls:sys_enter_execve` 与 `sys_enter_execveat`，实时打印进程执行事件的时间戳、进程名与可执行文件路径。
- **`trace_exec.d`（DTrace / Unix-like）**：利用 `proc:::exec-success` 探针追踪进程镜像替换事件，输出执行时刻与进程参数。
- **`macos_process_lifecycle.d`（DTrace / macOS）**：完整的进程生命周期追踪，覆盖 `proc:::create`（fork）、`proc:::exec-success`、`profile:::profile-997`（997 Hz CPU 采样）和 `proc:::exit`，输出 PID、PPID、事件类型、进程名与生命周期时长（毫秒级）。
- **`macos_fork_exit_only.d`、`macos_process2.d`、`user_execs.d`**：针对 fork/exit、进程枚举、用户级执行等特定场景的精简探针脚本。

上述脚本已在 Linux（bpftrace）和 macOS（DTrace）上验证运行，证明了通过内核探针采集高分辨率进程事件的可行性。

### 5.2 理论可实现的功能

Android 内核自 Android 10 起逐步支持 eBPF，Android 12+ 已支持 BTF-less eBPF 程序加载。在 root 或自定义内核环境下，eBPF 可为 MEMO-Appflow 提供以下系统级能力：

- **进程生命周期细粒度追踪**：通过 `tracepoint/sched/sched_process_fork` 与 `tracepoint/sched/sched_process_exit` 获取比 `UsageStats` 更精确的启动/终止时间戳，甚至可捕获应用内部多进程架构（如浏览器渲染进程、WebView 进程）的创建与销毁。
- **文件系统访问监控**：通过 `tracepoint/syscalls/sys_enter_openat` / `sys_enter_read` 采集应用冷启动时的真实 I/O 模式，为 `LaunchProfileRepository` 提供数据驱动的热点文件画像，替代当前的手动 profile。
- **内存分配追踪**：通过 `tracepoint/kmem/mm_page_alloc` / `mm_page_free` 或 `kprobe:do_mmap` 获取应用内存 footprint 的时序变化，为 `KillCandidate` 的 `deltaMemory` 计算提供真实输入，而非依赖启发式估计。
- **Binder 调用监控**：通过 `tracepoint/binder/binder_transaction` 追踪跨进程通信模式，识别应用间的隐式调用链（如分享、跳转、服务绑定），补充应用层无法观测的交互语义。
- **vmpressure / LMK 事件捕获**：通过 `tracepoint/vmscan/mm_vmscan_direct_reclaim_begin` 或自定义 kprobe 监控内核回收行为，使策略引擎从"被动读取内存快照"升级为"主动监听回收事件"，实现更及时的压力模式切换。
- **策略下沉与闭环控制**：在特权环境下，eBPF 程序可通过 BPF Maps 与用户态 Native Daemon 通信，甚至在内核态直接对特定进程的 `oom_score_adj` 或内存水线进行微调，将策略输出真正下沉到操作系统执行层。

### 5.3 与主项目的关联

当前 `UsageStats` 提供的是**应用层语义**（哪个包被打开了），而 eBPF 提供的是**系统层语义**（何时、如何、消耗多少资源）。两者融合后可构建"应用切换意图 + 系统资源状态"的双模态特征：

- `AppFlowPolicyEngine` 的压力模式判定可从单一的 `MemoryInfo` 快照，升级为融合分配速率、页面回收事件、Binder 频次的综合判定；
- `LaunchProfileRepository` 的预加载预算可从静态估算，演变为基于真实 I/O trace 的动态热点文件集；
- `KillCandidate` 的排名可从启发式 `deltaMemory`，转化为基于实际内存分配-释放曲线的精确回收收益计算。

### 5.4 实施路径与限制

| 阶段 | 目标 | 条件 |
|:---:|:---|:---|
| 已完成 | 桌面端（Linux / macOS）探针脚本验证 | 普通开发机 + sudo |
| 短期 | 在 root Android 设备上移植核心探针（fork/exit/vmpressure） | root + `CONFIG_BPF_SYSCALL` |
| 中期 | 通过 `NativeDaemonBridge` 将 eBPF 数据注入 `PolicyContext` | root / ADB + 自定义 native service |
| 长期 | 内核态策略微调（oom_adj、预读提示） | AOSP 编译 / 内核模块签名 |

当前限制：Android 厂商内核配置差异大，部分设备未开启 BTF，需手动定义内核结构体；eBPF 程序需要与具体内核版本匹配，跨设备移植成本高于纯应用层代码。

---

## 六、AI 预测模型可行性

AI 层作为策略上游，其可行性已得到验证：

- **Markov 基线**：实现零依赖、零延迟，已在在线模式和回放模式稳定运行，作为资源受限场景的可靠回退。
- **Transformer 模型**：C++ 推理引擎在 LSApp 数据集上达到 Hit@1 = 66.92%、Hit@3 = 84.56%、Hit@5 = 90.83%，单步推理延迟 11–45 ms，内存占用约 200 MB。该性能在设备空闲时完全可接受，结合 Circuit Breaker 机制可在电量/内存紧张时自动回退至 Markov 基线。
- **SequencePredictor**：已预留接口，可作为后续序列模型（LSTM、轻量 Transformer）的植入点。

综上，AI 层提供了从极简基线到深度模型的连续谱系，能够满足项目在不同资源条件下的预测需求。

---

## 七、风险与应对

| 风险 | 影响 | 应对措施 |
|-----|------|---------|
| Android 沙箱限制无法执行真实系统操作 | 当前阶段仅能模拟调度逻辑 | 先以 App 级原型验证算法与策略正确性，再逐步通过 root / AOSP 接入真实钩子；代码架构已为该路径预留接口 |
| Transformer 模型在低端设备上内存占用过高 | 可能导致 OOM 或杀后台 | 实施 Circuit Breaker 动态降级：内存紧张时自动切换至 Markov 基线；同时支持模型量化与线程数限制 |
| 公开数据集（LSApp）与在线真机数据分布不一致 | 离线评测指标可能无法完全映射到真机体验 | 双轨评估：离线以 LSApp 为标准基准，在线以 UsageStats 自采集数据进行个性化验证 |
| WorkManager 后台任务受 Doze 模式限制 | 后台采集与预测可能中断 | 保留前台 UI 刷新入口，用户可主动触发；未来可升级为前台服务（Foreground Service） |
| 用户隐私与权限获取 | UsageStats 权限需要用户手动授予 | 在应用首次启动时引导用户授权，并在 UI 中实时显示权限状态 |

---

## 八、结论

MEMO-Appflow 在当前 Android 应用沙箱内已经实现了一套**完整可运行**的预测引导资源管理原型，覆盖采集、存储、预测、策略、执行、评估、展示全链路。其架构设计并非面向纯应用层的 Demo，而是围绕操作系统边界组织，通过 `MemoSystemServiceFacade`、`SystemBridge`、`NativeSystemBridge` 和 JNI/C++ seam 明确预留了向 Binder 服务、Native Daemon 及 AOSP 集成的扩展通道。

操作系统层面的核心可行性已得到确认：当前阶段可以验证算法逻辑和调度策略，下一阶段可通过 root/AOSP 将同样的策略输出转化为真实的预加载、保活与回收操作。AI 预测层提供了从 Markov 基线到 Transformer 深度模型的连续能力谱系，能够在不同设备负载下自适应选择预测引擎。

同时，项目已明确将多模态用户行为语义（时间规律、系统状态、交互模式、场景上下文）作为提升预测精度的重要方向，具备继续向系统深处演进的技术基础与代码条件。
