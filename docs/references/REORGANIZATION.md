# 文档归拢记录

> **日期**: 2026-04-20  
> **操作**: 将项目内所有 Git 追踪的报告/说明类文件归拢至 `docs/references/` 与 `docs/references/archive/`  
> **目的**: 统一文档入口，消除散落在根目录、代码子目录及独立文件夹中的说明文件，确保项目结构清晰可追溯。

---

## 分类规则

本次归拢按以下四条规则执行：

1. **`docs/references/project-design/`**  
   存放当前仍然有效、描述项目整体设计与运行方式的核心说明文档（含原根目录 `README.md` 的项目总览）。

2. **`docs/references/archive/`**  
   存放历史阶段性报告、可行性研究产物、LaTeX 编译中间文件等不再频繁更新的文档。

3. **`docs/references/<主题>/`**  
   存放外部参考资料（论文、数据集、组件说明、脚本文档等），按主题划分子目录。

4. **局部说明文档（子目录 README）仅复制、不移动**  
   若某 README 是其所在代码/资源子目录的局部入口，且内容未过时，则复制一份到 `docs/references/` 的对应主题目录，原文件继续保留，方便浏览子目录时直接阅读。

---

## 完整映射表

### A. 移动到 `docs/references/project-design/`（核心项目说明）

| 来源路径 | 目标路径 | 操作 | 备注 |
|---------|---------|------|------|
| `README.md` | `docs/references/project-design/README.md` | 移动 | 原根目录项目总览 |
| `docs/architecture.md` | `docs/references/project-design/architecture.md` | 移动 | 架构文档 |
| `docs/dataset.md` | `docs/references/project-design/dataset.md` | 移动 | 数据集设计文档 |
| `docs/experiment_protocol.md` | `docs/references/project-design/experiment-protocol.md` | 移动 | 实验协议；命名去下划线改为连字符 |
| `docs/metrics.md` | `docs/references/project-design/metrics.md` | 移动 | 评估指标说明 |
| `docs/system_integration_plan.md` | `docs/references/project-design/system-integration-plan.md` | 移动 | 系统集成计划；命名去下划线改为连字符 |
| `docs/appflow_reproduction_plan.md` | `docs/references/project-design/appflow-reproduction-plan.md` | 移动 | AppFlow 复现计划；命名去下划线改为连字符 |

### B. 移动到 `docs/references/archive/`（历史归档）

| 来源路径 | 目标路径 | 操作 | 备注 |
|---------|---------|------|------|
| `report.md` | `docs/references/archive/project-reports/improvement-report.md` | 移动 | 改进报告 |
| `feasibility_report.md` | `docs/references/archive/feasibility/feasibility-report.md` | 移动 | 可行性报告 Markdown；命名去下划线改为连字符 |
| `feasibility report.pdf` | `docs/references/archive/feasibility/feasibility-report.pdf` | 移动 | 可行性报告 PDF；命名去空格改为连字符 |
| `feasibility report.tex` | `docs/references/archive/feasibility/feasibility-report.tex` | 移动 | LaTeX 源文件；命名去空格改为连字符 |
| `feasibility report.aux` | `docs/references/archive/feasibility/feasibility-report.aux` | 移动 | LaTeX 辅助文件；命名去空格改为连字符 |
| `feasibility report.log` | `docs/references/archive/feasibility/feasibility-report.log` | 移动 | LaTeX 编译日志；命名去空格改为连字符 |
| `feasibility report.synctex.gz` | `docs/references/archive/feasibility/feasibility-report.synctex.gz` | 移动 | LaTeX 同步文件；命名去空格改为连字符 |

### C. 移动到 `docs/references/` 主题子目录（外部参考资料）

| 来源路径 | 目标路径 | 操作 | 备注 |
|---------|---------|------|------|
| `AppFlow.pdf` | `docs/references/papers/AppFlow.pdf` | 移动 | AppFlow 论文原文 |
| `research paper referenced/ATPP.md` | `docs/references/papers/ATPP.md` | 移动 | ATPP 论文引用说明 |
| `research paper referenced/ATPP.bib` | `docs/references/papers/ATPP.bib` | 移动 | ATPP BibTeX 条目 |
| `dataset referenced/LSApp.md` | `docs/references/datasets/LSApp.md` | 移动 | LSApp 数据集说明 |
| `scripts/tracing_the_processes/feasibility_report.md` | `docs/references/scripts/tracing-feasibility-report.md` | 移动 | 脚本目录下的可行性报告副本；命名去下划线改为连字符 |

### D. 复制到 `docs/references/`（原位置保留）

| 来源路径 | 目标路径 | 操作 | 备注 |
|---------|---------|------|------|
| `app/src/main/cpp/TransformerPredictor/README.md` | `docs/references/native/TransformerPredictor.md` | 复制 | C++ 组件局部说明；原位置保留供代码浏览 |
| `benchmark/README.md` | `docs/references/benchmarks/README.md` | 复制 | Benchmark 目录局部说明；原位置保留 |
| `dataset referenced/README.md` | `docs/references/datasets/README.md` | 复制 | 数据集目录入口说明；原位置保留 |
| `research paper referenced/README.md` | `docs/references/papers/README.md` | 复制 | 论文引用目录入口说明；原位置保留 |
| `scripts/README.md` | `docs/references/scripts/README.md` | 复制 | 脚本目录局部说明；原位置保留 |

### E. 目录重命名（内部文件不动）

| 原目录 | 新目录 | 操作 | 备注 |
|-------|-------|------|------|
| `docs/meeting logs/` | `docs/meeting_logs/` | 重命名 | 目录名去空格改为下划线；内部 8 个 conference 文件未动 |

---

## 命名变更记录

本次移动过程中，对文件名进行了以下规范化处理，以消除空格与下划线混用的问题：

| 原文件名 | 新文件名 | 变更 |
|---------|---------|------|
| `feasibility_report.md` | `feasibility-report.md` | 下划线 → 连字符 |
| `feasibility report.pdf` | `feasibility-report.pdf` | 空格 → 连字符 |
| `feasibility report.tex` | `feasibility-report.tex` | 空格 → 连字符 |
| `feasibility report.aux` | `feasibility-report.aux` | 空格 → 连字符 |
| `feasibility report.log` | `feasibility-report.log` | 空格 → 连字符 |
| `feasibility report.synctex.gz` | `feasibility-report.synctex.gz` | 空格 → 连字符 |
| `experiment_protocol.md` | `experiment-protocol.md` | 下划线 → 连字符 |
| `system_integration_plan.md` | `system-integration-plan.md` | 下划线 → 连字符 |
| `appflow_reproduction_plan.md` | `appflow-reproduction-plan.md` | 下划线 → 连字符 |
| `feasibility_report.md` (scripts 下副本) | `tracing-feasibility-report.md` | 下划线 → 连字符，加前缀避免冲突 |

---

## 未纳入本次归拢的文件

以下文件虽在 Git 追踪范围内，但属于代码/脚本而非报告/说明类文档，因此未纳入本次归拢：

- `scripts/tracing_the_processes/*.d`、`*.bt`（DTrace/bpftrace 脚本）
- `app/src/main/cpp/TransformerPredictor/` 下除 README.md 外的全部源码与权重文件
- `configs/*.json` 等配置示例

---

## 保留原位的局部 README 列表

以下文件因属于子目录局部入口说明，按“仅复制、不移动”规则继续保留在原位置：

1. `app/src/main/cpp/TransformerPredictor/README.md`
2. `benchmark/README.md`
3. `dataset referenced/README.md`
4. `research paper referenced/README.md`
5. `scripts/README.md`

---

## 目录变更摘要

### 新建目录

```
docs/references/
docs/references/project-design/
docs/references/archive/
docs/references/archive/project-reports/
docs/references/archive/feasibility/
docs/references/papers/
docs/references/datasets/
docs/references/native/
docs/references/benchmarks/
docs/references/scripts/
```

### 重命名目录

```
docs/meeting logs/  →  docs/meeting_logs/
```

### 受影响的旧目录状态

- `dataset referenced/`：保留 `README.md`（复制未移动），`LSApp.md` 已移出
- `research paper referenced/`：保留 `README.md`（复制未移动），`ATPP.md`/`ATPP.bib` 已移出
- `benchmark/`：保留 `README.md`（复制未移动）
- `docs/meeting logs/`：已重命名为 `docs/meeting_logs/`，旧目录不存在

---

## 后续建议

1. **更新引用**：`AGENTS.md`（若未来纳入 Git）和任何内部硬编码路径中提到的旧路径（如 `docs/architecture.md`、`dataset referenced/LSApp.md` 等）需同步更新指向新位置。
2. **根目录 README**：你提到后续会有新的根目录 `README.md`，该 README 不再用于项目说明，因此建议在根目录 README 中明确声明“项目说明已迁移至 `docs/references/project-design/`”。
3. **清理空目录**：如果你认为 `dataset referenced/`、`research paper referenced/`、`benchmark/` 在仅剩 README 后已无保留必要，可在未来单独删除这些目录及其 README（届时它们不再是“局部说明”，因为对应的内容文件已全部迁出）。
