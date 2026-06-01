# 自由体验实验

本目录保存“开始自由体验 -> 打开若干真实应用 -> 回到 MEMO 点结束体验并分析”的一次真机结果。

## 实验设计

这个实验和 `100 次真实使用分析` 使用同一条产品 pipeline，区别只在 app 序列来源：

| 环节 | 100 次真实使用分析 | 自由体验 |
| --- | --- | --- |
| app 序列来源 | MEMO 自动轮流打开 100 次真实 launcher app | 前台 app sampler 记录实际切换过的 app |
| eBPF 来源 | raw libbpf collector | raw libbpf collector |
| 时间对齐 | 每 5 次自动打开 app 形成一个时间窗 | 按实际 app 停留片段形成时间窗 |
| 压缩方式 | `event_type/detail/count/rate_per_sec` | `event_type/detail/count/rate_per_sec` |
| MAPLE 输入 | `app_catalog` + `observed_app_sequence` + `timeline_windows[].compressed_ebpf` | 同左 |
| 输出 | MAPLE prediction、Top-3 真实 app、ActionExecutor 调度动作、报告 | 同左 |

## 本次结果

| 指标 | 数字 |
| --- | ---: |
| 真实 app 片段 | 4 |
| 覆盖 app | 4 个 |
| app sampler 记录 | 13 条 |
| raw eBPF 事件 | 14113 条 |
| MAPLE 压缩信号 | 9 条 |
| 时间窗 | 1 个 |
| MAPLE 总耗时 | 25.8 s |
| 前台可见处理耗时 | 9.2 s |

本次实际打开的 app：

```text
Chrome -> Messages -> Camera -> Settings
```

MAPLE 输入不是 raw trace 原文，而是压缩后的时间窗信号。对应字段：

```text
latest_free_usage_maple_scenario.json
  context.observed_app_sequence
  context.timeline_windows[].compressed_ebpf
  context.compression
```

报告文件：

```text
latest_free_usage_report.json
free_usage_raw_trace.trace
free_usage_app_samples.tsv
latest_free_usage_maple_scenario.json
latest_main_ui.png
latest_main_ui_scrolled.png
latest_main_ui_analysis.png
```

其中三张 `latest_main_ui*.png` 是安装新 APK 后从真机拉取的界面截图，用来验证 Top-3 区域已经显示“最近更新”，且“本次观察与分析”把系统证据摘要和自由体验分析合并在同一个板块里。

关键压缩结果：

```text
raw_ebpf_rows_for_audit: 14113
compressed_ebpf_signal_rows: 9
```

这说明自由体验和 100 次实验一样，都是先把真实 app 时间线和 eBPF 对齐，再把重复 eBPF 事件压缩成小模型更适合读取的统计信号，然后才进入 MAPLE。
