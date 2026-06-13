# OSH Lab4 提交说明

本目录为 OSH 2026 Lab4 提交材料，包含 llama.cpp 主线实验和 Ray 选择性必做实验。

## 文件说明

- `报告.md`：中文实验报告。
- `prompts.txt`：Ray 批量推理使用的 25 条 prompt。
- `prompts_quality.txt`：输出质量评估使用的 5 条 prompt。
- `scripts/ray_inference.py`：Ray Task 批量推理脚本。
- `scripts/run_quality_eval.ps1`：输出质量评估复现实验脚本。
- `results/`：单机性能、参数优化、RPC、Ray 等实验结果。

## 未提交的大文件

模型文件 `*.gguf`、llama.cpp 源码和构建产物体积较大，且仓库根目录 `.gitignore` 已忽略 `*.gguf` 和 build 目录，因此本次提交不包含：

- `models/Qwen_Qwen3.5-0.8B-Q4_K_M.gguf`
- `llama.cpp/`
- `llama.cpp/build-rpc/`

复现实验时，将模型放在 `Lab4/models/` 下，并按报告中的命令编译 llama.cpp 即可。
