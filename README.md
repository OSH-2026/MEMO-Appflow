# MEMO-Appflow

## 作者

- 陈可为（javaherobrine）
- 樊澄宇（fancyovo）
- 郭璟仪（place-bot）
- 黄漠沙（AndyHuang-hub）

## 项目简介

MEMO-Appflow 是 OSH 2026 课程项目，目标是在 Android 上探索基于系统证据和 MAPLE 大模型推理的资源/内存调度。当前重点不是只预测“下一个进程”或做简单频率统计，而是让系统从更底层的运行状态中形成证据，再把证据交给 MAPLE 推理，最后转成真实应用推荐和系统调度动作。

当前产品链路全部放在 Android emulator / rooted Android 设备内部：

```text
Android emulator / rooted phone
-> App 内启动 eBPF 与系统状态采集
-> App 内解析证据并构造 MAPLE scenario
-> 设备本地 MAPLE 推理
-> 自适应扫描已安装应用并生成 Top-3
-> Widget 展示真实应用
-> 执行预热、降级、内存压力响应等调度动作
```

电脑端 Python 采集/转换脚本已经从产品路径中删除。电脑只负责安装 APK、推送模型/工具文件、查看日志，不承担采集、整理、推理或执行动作的产品逻辑。

## 目录结构

```text
app/                    Android 端产品实现
  EBPFCollectorService   设备内采集与流水线后台服务
  EBPFTraceParser        设备内 trace 解析
  MapleScenarioBuilder   设备内 MAPLE scenario 构造
  MapleNative            MAPLE JNI 桥
  AppIdMapping           自适应真实应用映射
  ActionExecutor         调度动作执行
  MemoWidgetProvider     Top-3 应用 Widget

llm/maple/              组员交接的 MAPLE C++ engine 与说明
docs/                   当前报告与版本演进记录
dataset_cache/          本地实验缓存、内核、模型权重和工具文件，按当前设置不提交
```

## 当前能力

Android App 内部会采集和整理这些证据：

- memory：可用内存、swap、reclaim、PSI、LMKD 快照；
- battery：电量、充电状态、电压、温度；
- network：可用时采集 UDP `sendto` / `recvfrom`，并读取 `/proc/net` 状态；
- camera/media：`cameraserver`、MediaCodec、音频服务、media session；
- display/UI：SurfaceFlinger、RenderThread、输入与调度活动；
- process/service：前台应用、关键系统服务 PID、Binder 活动。

这些 eBPF 和系统细节不会展示给普通用户。它们只作为 MAPLE 推理和调度策略的输入。用户看到的是 Top-3 真实可启动应用，以及系统实际做出的预热或降级动作。

## 应用推荐与动作

Top-3 推荐不是硬编码 “App 110 = 微信”。App 会扫描设备上真实安装的 launcher 应用，并结合默认浏览器/电话/短信角色、Intent 能力、权限、`ApplicationInfo.category` 和弱文本特征做自适应分类。最后展示给用户的一定是实际存在、可启动的应用，而不是进程、Binder 线程或 SurfaceFlinger 这类系统对象。

MAPLE 输出会进一步进入 `ActionExecutor`：

- 正常内存和温度下，对候选应用做 warm launch，然后回到 HOME；
- 内存压力升高时，减少预热数量，必要时触发 ActivityManager idle maintenance；
- 内存压力严重时，跳过预热，对低优先级候选发送 trim-memory，请求 root-only cache drop；
- 网络活跃时，提高网络类应用优先级，并刷新网络状态；
- camera/media 场景下，选择相机、相册、媒体类候选，但在压力严重时自动降级；
- SurfaceFlinger/RenderThread/input 活跃时，降低预热预算，避免 UI 卡顿；
- Binder/service 活跃时，保留服务证据并刷新服务状态。

## 构建与部署

构建 APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

安装到 Android emulator 或 rooted phone：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

设备内模型和工具布局：

```text
/data/local/tmp/memo/models/Qwen3.5-0.8B-Q4_K_M.gguf
/data/local/tmp/memo/bpftrace
/data/local/tmp/memo/bpftool
/data/local/tmp/memo/libmaple_engine.so
```

如果设备缺少 root、tracefs、bpftrace、bpftool 或 MAPLE native binary，App 会在控制台里报告 capability unavailable，不会伪造成 eBPF 或 MAPLE 已经成功。

## 文档

- `docs/ebpf_report.md`：当前 Android 端 eBPF、系统状态、MAPLE、Top-3 与动作闭环说明。
- `docs/next_steps_roadmap_2026-05-06.md`：本轮端侧迁移后的下一步工程路线。
- `docs/theoretical_ablation_experiment/`：Chengyu Fan 的理论/合成消融实验。
- `docs/real_device_experiments/real_ebpf_ablation/`：Jingyi Guo 的 Android 真实 eBPF 消融实验与结果解读。
- `docs/report_versions/`：历史阶段报告，用来保留项目方向的演进过程。

## 会议记录

组会纪要集中在此，便于新成员对齐背景；后续会议将按时间顺序续写。

| 次数 | 日期 | 主题与结论摘要 |
| --- | --- | --- |
| 1 | 2026-03-04 | 会前邀请学过 OSH 的学长学姐参与讨论。会上成员互相认识，详细沟通了项目目标：在 Android 上实现类似 MEMO 的预测与预加载系统，基本形态为推荐 Top-3 应用并预加载第一个。 |
| 2 | 2026-03-11 | 李思源离开，陈可为加入。讨论并对比了 RNN、CNN 与 Transformer，决定使用 Transformer 作为神经网络。明确分工：樊澄宇负责 AI 训练，黄漠沙负责调研并辅助 AI，郭璟仪用 Codex 构建软件，陈可为审查 AI 代码并提供技术支持。 |
| 3 | 2026-03-18 | 郭璟仪展示了 MEMO-OS 的首个 demo。黄漠沙与樊澄宇展示了一组论文，确定以进程序列作为问题切入点。陈可为探索了 Android Emulator 方案，包括全平台的 Android Studio、Linux 的 Waydroid 与 Redroid、以及 Windows 的 WSA Builds。 |
| 4 | 2026-03-25 | 黄漠沙找到多个可用数据集。樊澄宇用网络数据训练 AI 并展示结果。郭璟仪调研预热内存管理，明确小文件关注延迟、大文件关注吞吐。陈可为审查代码后提出 Critical Native 优化，并帮黄漠沙和樊澄宇安装 Android Studio。 |
| 5 | 2026-03-29 | 讨论移动设备资源受限下精度与开销的平衡。好的 LLM 开销大，差的 LLM 精度低。陈可为提出 Circuit Breaker：设备空闲时用大模型，资源紧张时回退轻量模型。明确移动设备功耗敏感，预测任务必须设置最大开销上限。 |
| 6 | 2026-04-01 | 黄漠沙调研指出必须追踪进程的启动与终止及其时间戳。陈可为总结 eBPF 可用于追踪进程和其他系统调用。樊澄宇展示了一张数据表，汇报在公开数据集上的 AI 训练结果。 |
| 7 | 2026-04-08 | 陈可为展示了基于 bpftrace 的 eBPF 程序。为获取更多信息并适配 macOS，他将程序移植为 dtrace 版本。黄漠沙同步推进数据集与特征工程，等待樊澄宇的模型结果进行后续集成。 |
| 8 | 2026-04-15 | 邢老师指出仅收集进程事件（fork/exec/exit）的精度方向存在偏差。黄漠沙起初未能完全理解，陈可为解释后全组达成共识：必须综合考虑用户行为与系统状态。会后决定重新调整调研方向。 |
| 9 | 2026-04-29 | 线上讨论：尝试加入 Android eBPF 系统证据采集，并把采集结果接到 MAPLE 大模型推理模块。同步修复 Android 14 emulator custom kernel 的 SurfaceFlinger 图形通道，使 UI 展示、`CONFIG_FTRACE_SYSCALLS` 和 eBPF 采集可以在同一虚拟机上演示。 |
| 10 | 2026-05-06 | 讨论下一阶段方针：把产品逻辑全部迁到 Android emulator 内部，按未来 rooted phone 部署方式开发；补齐设备内采集、设备内结构化、MAPLE 调用、Top-3 真实应用推荐、动作执行和 Widget 展示。 |
