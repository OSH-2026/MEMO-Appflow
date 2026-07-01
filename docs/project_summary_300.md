# MEMO-Appflow 项目摘要

MEMO-Appflow 是面向 Android 的端侧、基于本地大模型推理的应用预测与系统调度软件。它从 app 与操作系统两侧获取信息：在手机内持续采集真实 app 序列、eBPF 内核证据和系统状态，每 3 分钟压缩成 MAPLE 场景输入。大模型据此预测接下来可能使用的 Top-3 应用，并用 bitmask 选择调度动作，形成“从 app + OS 到 app + OS”的闭环：既更新桌面 Widget、优化 app 推荐体验，也执行预热、内存整理和服务刷新，改善 OS 状态。9 分钟真机实验显示，Top-3 会随窗口刷新，`scheduler_bits` 已触发 warm launch、memory trim 和 service manager 调度。
