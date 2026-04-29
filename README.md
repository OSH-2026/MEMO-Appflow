# MEMO-Appflow

## 作者

- 陈可为（javaherobrine）
- 樊澄宇（fancyovo）
- 郭璟仪（place-bot）
- 黄漠沙（AndyHuang-hub）

## 项目简介

MEMO-Appflow 是 OSH 2026 课程项目，目标是探索 Android 场景下基于系统证据和大模型推理的资源/内存调度。仓库当前保留四部分：

- 轻量 Android 展示 app：用于安装和演示项目入口，不承担主要算法逻辑。
- Android eBPF 证据采集：在 emulator/userdebug 环境中观察 Binder、文件访问、调度、内存压力等系统行为。
- MAPLE 本地推理模块：使用组员提供的 MAPLE engine，把结构化系统证据接到大模型推理流程。
- 文档与版本报告：记录从早期 AppFlow/app-sequence 原型到当前系统证据与推理调度方向的演进。

频率统计、Markov、应用序列等内容只适合作为 baseline。当前重点不是简单预测用户下一个打开哪个 app，而是把更底层的系统行为整理成可解释证据，再交给推理模块辅助判断资源需求和内存调度方向。

## 目录结构

```text
app/                    轻量 Android 展示 app
scripts/android_ebpf/   Android eBPF 采集、解析、MAPLE 场景转换脚本
llm/maple/              组员提供的 MAPLE engine、demo 和本地模型目录
docs/                   当前报告与说明文档
docs/report_versions/   不同阶段的项目报告版本
dataset_cache/          本地实验缓存目录，默认不随轻量提交上传
```

## 当前流水线

```text
Android emulator 用户动作
-> eBPF/tracepoint 系统事件
-> JSONL 结构化证据
-> MAPLE scenario
-> MAPLE 本地推理
-> 面向资源/内存调度的解释和预测结果
```

最新本地端到端验证使用修复后的 Android 14 custom kernel，在同一个 emulator 中同时满足 UI 展示、SurfaceFlinger 正常启动、`CONFIG_FTRACE_SYSCALLS=y` 和 eBPF 采集。

## 快速验证

Python 单元测试：

```powershell
python -m unittest `
  scripts.android_ebpf.tests.test_android_ebpf_collect `
  scripts.android_ebpf.tests.test_maple_context_from_ebpf
```

Android 展示 app：

```powershell
.\gradlew.bat :app:assembleDebug
```

MAPLE engine 构建：

```powershell
wsl.exe bash -lc "cd /mnt/c/Users/gjy20/Desktop/26sp/osh/appflow/MEMO-Appflow/llm/maple/llama.cpp && cmake -B build && cmake --build build -j$(nproc)"
wsl.exe bash -lc "cd /mnt/c/Users/gjy20/Desktop/26sp/osh/appflow/MEMO-Appflow/llm/maple/maple_engine && cmake -B build && cmake --build build -j$(nproc)"
```

## 文档

- `docs/ebpf_report.md`：当前 eBPF 证据采集、MAPLE 接入和真实 emulator 数据示例。
- `docs/report_versions/`：按时间保存的阶段报告，记录项目方向变化。
- `scripts/android_ebpf/README.md`：采集脚本、MAPLE 转换脚本和验证命令。

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
| 9 | 2026-04-29 | 线上讨论：继续调整项目重点，尝试加入更深层的 Android eBPF 系统证据采集，并把采集结果对接到组员提供的 MAPLE 大模型推理模块。同步修复 Android 14 emulator custom kernel 的 SurfaceFlinger 图形通道，使 UI 展示、`CONFIG_FTRACE_SYSCALLS` 和 eBPF 采集可以在同一虚拟机上演示。 |

## 当前注意事项

- 模型侧以 MAPLE engine 为准。
- Android app 保持轻量，项目核心逻辑主要在 host 侧脚本、custom kernel/eBPF 采集和 MAPLE 推理模块中。
