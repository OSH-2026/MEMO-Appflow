# 3x10 真实记录预测实验与推理-only 重跑

## 实验目的

本实验用于回答两个问题：

1. 在有真实 app 序列、真实 eBPF/OS 证据和隐藏 ground truth 的情况下，MAPLE 能不能预测接下来 Top-3 应用。
2. eBPF/OS 证据进入 MAPLE 后，是否应该直接参与 app 类别预测，还是应该作为系统调度上下文使用。

## 数据来源

第一轮完整实验在 rooted Pixel 5 上真实执行了 30 条短记录：

- 3 个角色：`media_browsing`、`productivity_learning`、`social_sharing`
- 每个角色 10 条记录，共 30 条
- 每条记录都真实启动一段输入 app 序列
- 手机内部采集 raw libbpf/eBPF trace、前台 app samples、系统状态
- MAPLE 只看到输入序列和 eBPF/OS context，不看到隐藏的 next Top-3 ground truth

原始完整实验结果：

```text
docs/real_device_experiments/synthetic_user_30_2026_07_01/latest_synthetic_user_30_report.json
docs/real_device_experiments/synthetic_user_30_2026_07_01/device_artifacts/
```

## 为什么做推理-only 重跑

第一轮结果显示，Full MEMO 的预测反而低于 app-only baseline。检查 MAPLE 输出后发现原因不是 eBPF 采集失败，而是 prompt/数据结构把 `Memory Management`、`Android Service IPC` 等 OS-only 类别放进了 app prediction candidates。小模型会把这些系统资源类别当成“下一个应用类别”，导致 Top-3 映射被拉偏。

因此这次不重新模拟使用、不重新采集 eBPF，只复用刚才 30 条真实记录，做一次推理-only 重跑：

- 保留原始真实 app sequence
- 保留原始真实 eBPF/system context
- 保留原始隐藏 ground truth
- 只修正 MAPLE 输入里的预测候选：`historical_app_categories` 只放用户可见 app 类别
- `Memory Management`、Binder、service、process runtime 等 OS 证据继续保留，但只作为 scheduler/system context

推理-only 输入与输出：

```text
docs/real_device_experiments/synthetic_user_30_2026_07_01/rerun_fixed_scenarios/
docs/real_device_experiments/synthetic_user_30_2026_07_01/rerun_maple_outputs/
docs/real_device_experiments/synthetic_user_30_2026_07_01/rerun_inference_only_report.json
```

## 代码修改

本次修正涉及：

- `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt`
  - 把 `historical_app_categories` 限制为用户可见 app 类别。
  - 新增 `scheduler_evidence_categories` 保存 OS/eBPF 类别，供调度使用。
  - 明确提示 MAPLE：Top-3 不能输出 Memory/Binder/process/system-service。

- `llm/maple/maple_engine/src/prompt_builder.cpp`
  - 把 Stage 1 prompt 从 “resource-demand category” 改为 “user-facing Android app category”。
  - 明确 Memory 是 scheduler context，不是 app category。

- `llm/maple/maple_engine/src/result_parser.cpp`
  - 修复小模型输出 `<Category>Communication (<10%))` 这种无空格/多括号格式无法解析的问题。
  - 将 App ID 110 在当前 app prediction 语境下映射为 `Communication`。

## 指标定义

- `top1_accuracy`：预测第 1 个应用是否等于 ground truth 第 1 个应用，越高越好。
- `hit_top3_rate`：ground truth 第 1 个应用是否出现在预测 Top-3 中，越高越好。
- `precision_at_3`：预测 Top-3 中有多少比例在 ground truth Top-3 中，越高越好。
- `recall_at_3`：ground truth Top-3 中有多少比例被预测 Top-3 覆盖，越高越好。
- `f1_at_3`：Precision@3 和 Recall@3 的调和平均，越高越好。
- `mrr`：ground truth 第 1 个应用在预测列表中的倒数排名，越高越好。
- `exact_order_top3_rate`：预测 Top-3 顺序是否完全等于 ground truth Top-3，越高越好。

## 结果

| 配置 | Top-1 Acc | Top-3 Hit | Precision@3 | Recall@3 | F1@3 | MRR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 旧 Full MEMO | 3.33% | 26.67% | 27.78% | 27.78% | 27.78% | 14.44% |
| App-only baseline | 20.00% | 46.67% | 47.78% | 47.78% | 47.78% | 31.11% |
| 修正后推理-only Full MEMO | 23.33% | 50.00% | 52.22% | 52.22% | 52.22% | 34.44% |

提升：

- 相比旧 Full MEMO：
  - Top-3 Hit：26.67% -> 50.00%，提升 23.33 个百分点，相对提升 87.50%。
  - F1@3：27.78% -> 52.22%，提升 24.44 个百分点，相对提升 88.00%。
  - Top-1 Acc：3.33% -> 23.33%，提升 20.00 个百分点。

- 相比 App-only baseline：
  - Top-3 Hit：46.67% -> 50.00%，提升 3.33 个百分点，相对提升 7.14%。
  - F1@3：47.78% -> 52.22%，提升 4.44 个百分点，相对提升 9.30%。
  - Top-1 Acc：20.00% -> 23.33%，提升 3.33 个百分点。

## 解读

这次结果说明两点：

1. eBPF/OS 信息不能粗暴地和 app 类别混在同一个候选集合里。否则小模型容易预测 `Memory Management` 这种系统资源类别，而不是用户接下来要点开的 app。
2. 把 app prediction 和 OS scheduling 分开后，eBPF/OS 证据仍然有用。它不直接抢占 app 类别，而是帮助解释当前系统状态、提供调度上下文，并通过 scheduler_bits 决定 warm launch、network refresh、service refresh、memory trim 等动作。

因此当前更严谨的说法是：

MEMO-Appflow 使用 app 序列预测用户接下来可能使用的真实应用，同时使用 eBPF/OS 证据辅助修正场景理解和系统调度。修正后的 30 条真实记录推理-only 实验中，Full MEMO 在 Top-3 Hit、F1@3 和 MRR 上都超过 app-only baseline，但提升幅度仍然有限，需要后续继续优化 prompt、app mapping 和角色 ground truth 设计。

## 注意事项

这次重跑没有重新采集 eBPF，也没有重新执行 scheduler A/B。它验证的是同一批真实 context 下的 MAPLE 推理正确性。系统调度性能仍应参考：

```text
docs/real_device_experiments/user_app_pressure/latest_pressure_experiment.json
docs/real_device_experiments/user_app_pressure/README.md
```
