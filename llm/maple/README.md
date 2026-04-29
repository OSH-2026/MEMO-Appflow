# MAPLE C++ 推理引擎

MAPLE（Mobile App Prediction Leveraging Large Language Model Embeddings）推理流水线的 C++ 实现，基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) 和 **Qwen3.5-0.8B** 构建。

本项目通过封装良好的 C++ 类复现 MAPLE 效果，支持功能开关（Feature Toggle）、两阶段预测策略，并暴露 C 接口以便后续通过胶水代码（glue code）对接 Python/Java。

---

## 0. 分发版说明（必读）

本目录为精简后的可分发版本，已移除大体积非必要文件，方便快速下载与构建。

### 已移除的内容

| 内容 | 原大小 | 移除原因 |
|------|--------|----------|
| 模型权重 `Qwen3.5-0.8B-Q4_K_M.gguf` | ~503 MB | 体积过大，提供下载指令 |
| Hugging Face 缓存 `.cache/` | ~443 MB | 运行时缓存，可自动重建 |
| Python 虚拟环境 `.venv/` | ~82 MB | 非 C++ 推理必需 |
| llama.cpp `.git/` 历史 | ~33 MB | 源码已附带，无需 git |
| llama.cpp `build/` 中间产物 | ~22 MB | 编译后可重新生成 |
| llama.cpp `models/` 测试词表 | ~34 MB | 运行非必需 |
| `maple_engine/build/` 中间产物 | ~7.6 MB | 编译后可重新生成 |
| 原始打包 `MAPLE.zip` | ~1.2 GB | 与解压后的源码重复 |

**精简后总大小：约 34 MB**（原项目约 2.3 GB）。

### 快速开始（三步走）

#### 步骤一：编译 llama.cpp（已附带源码，无需克隆）

```bash
cd llama.cpp
cmake -B build \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build build --target llama ggml ggml-base ggml-cpu llama-common -j$(nproc)
cd ..
```

#### 步骤二：下载模型权重

```bash
mkdir -p models
cd models
wget https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf
cd ..
```

> 若 `wget` 下载速度较慢，也可通过 `huggingface-cli` 或浏览器手动下载后放入 `models/` 目录：
> ```bash
> pip install huggingface-hub
> huggingface-cli download unsloth/Qwen3.5-0.8B-GGUF Qwen3.5-0.8B-Q4_K_M.gguf --local-dir models
> ```

#### 步骤三：编译 MAPLE 引擎并运行演示

```bash
cd maple_engine
cmake -B build .
cmake --build build -j$(nproc)
cd ..
./maple_engine/build/maple_demo
```

---

## 1. 模型

| 属性 | 值 |
|------|-------|
| **模型** | Qwen3.5-0.8B |
| **格式** | GGUF（Q4_K_M 量化） |
| **参数量** | 0.8B（< 1B，符合要求） |
| **大小** | 约 508 MB |
| **后端** | llama.cpp（纯 CPU，无需 GPU） |
| **下载源** | [unsloth/Qwen3.5-0.8B-GGUF](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF) |

> **注意**：`models/` 目录默认为空，首次使用前请参考上方【步骤二】下载模型权重。

模型通过 llama.cpp 的 C API 本地加载，完全在 CPU 上运行。无需训练或微调，所有预测均为零样本/少样本提示工程驱动。

---

## 2. 方法论

本实现遵循 MAPLE 论文中描述的两阶段预测策略：

### 阶段一：应用类型预测（ATP - App Type Prediction）

基于上下文特征预测用户将打开的**应用大类**，上下文特征包括：
- 历史应用类别
- 历史应用 ID
- 预测时间（星期几 + 时刻）
- 兴趣点 / 地理位置
- 已安装应用
- 用户人口统计信息（年龄、性别）

模板化的提示构建器（Prompt Builder）将这些结构化特征转换为自然语言句子（与论文 Table 4 对应）。大模型生成简洁的预测结果，例如：

```
Communication app (75%)
```

### 阶段二：具体应用预测（NTP - Next App Prediction）

进一步 narrowing down 到**具体的应用 ID**，输入信息包括：
- 阶段一预测出的应用类别
- 历史应用使用序列
- 各类别下的已安装应用集合
- 补充上下文（时间、地点）

大模型输出类似：

```
App 1
```

### 功能开关（Feature Toggle）

每种上下文类型均可通过位掩码独立开启或关闭：

| 标志位 | 上下文特征 | 论文对应 |
|------|----------------|---------|
| `0x01` | 历史应用类别 | Table 4, row 1 |
| `0x02` | 预测时间 | Table 4, row 2 |
| `0x04` | 兴趣点 | Table 4, row 3 |
| `0x08` | 历史应用使用 | Table 4, row 4 |
| `0x10` | 已安装应用 | Table 4, row 5 |
| `0x20` | 用户人口统计 | Table 1 |

默认值：`0x1F`（除人口统计外全部启用）。

---

## 3. 文件结构

```
MAPLE/
|
|-- maple_engine/
|   |-- CMakeLists.txt              # 构建配置
|   |-- include/
|   |   |-- maple_engine.h          # 主类 + C API
|   |   |-- maple_types.h           # 数据结构 & 功能标志
|   |   |-- llama_backend.h         # llama.cpp 封装层
|   |   |-- prompt_builder.h        # 模板化提示生成
|   |   |-- result_parser.h         # 输出解析
|   |-- src/
|   |   |-- maple_engine.cpp        # MAPLEEngine 实现
|   |   |-- llama_backend.cpp       # 模型加载 & 文本生成
|   |   |-- prompt_builder.cpp      # 提示模板构造
|   |   |-- result_parser.cpp       # 基于正则的结果提取
|   |
|-- demo/
|   |-- maple_demo.cpp              # 独立可执行演示程序
|
|-- examples/
|   |-- scenarios.json              # 10 条真实移动使用场景
|
|-- models/
|   |-- (空)                        # 请按 README 步骤二下载 .gguf 模型
|
|-- llama.cpp/                      # llama.cpp 完整源码（已附带，无需额外克隆）
|
|-- README.md                       # 本文档
```

---

## 4. 如何构建

### 环境要求

- CMake >= 3.16
- GCC / Clang，支持 C++17
- Linux（已在 x86_64 上验证）
- （可选）`wget` 或 `huggingface-cli`，用于下载模型

### 步骤一：编译 llama.cpp

`llama.cpp/` 目录已包含完整的 llama.cpp 源码，**无需额外克隆**，直接编译即可：

```bash
cd llama.cpp
cmake -B build \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build build --target llama ggml ggml-base ggml-cpu llama-common -j$(nproc)
cd ..
```

编译完成后，llama.cpp 的静态库将生成在 `llama.cpp/build/` 下的各子目录中。

### 步骤二：下载模型

```bash
mkdir -p models
cd models
wget https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf
cd ..
```

> **替代方式**：使用 Hugging Face CLI
> ```bash
> pip install huggingface-hub
> huggingface-cli download unsloth/Qwen3.5-0.8B-GGUF Qwen3.5-0.8B-Q4_K_M.gguf --local-dir models
> ```

### 步骤三：编译 MAPLE 引擎

```bash
cd maple_engine
cmake -B build .
cmake --build build -j$(nproc)
cd ..
```

### 构建产物

编译完成后，会在以下位置生成：

```
maple_engine/build/libmaple_engine.so   # 共享库（供 ctypes/JNI 使用）
maple_engine/build/libmaple_engine.a    # 静态库
maple_engine/build/maple_demo           # 演示可执行文件
```

---

## 5. 如何运行

### 运行演示

```bash
./maple_engine/build/maple_demo
```

演示程序读取 `examples/scenarios.json`，并仅运行第一个场景，完整展示两阶段预测流水线。

---

## 6. 示例输出

以下是在终端直接运行演示程序得到的**完整、未截断**的输出：

```
============================================================
  MAPLE Inference Demo
============================================================
Model:     models/Qwen3.5-0.8B-Q4_K_M.gguf
Scenarios: examples/scenarios.json
[MAPLE] Model loaded successfully.

============================================================
  Scenario: commute_morning
============================================================
Description: Morning commute, user checking communication apps

>>> Stage 1: App Type Prediction (ATP)
------------------------------------------------------------
[1] INPUT INFO:
    Historical app categories: Communication, Navigation
    Historical app IDs:        1, 5
    Prediction time:           Monday 08:30AM
    Points of interest:        subway station, coffee shop
    Installed apps:
      - Communication: 1, 2, 3
      - Navigation: 5, 6
      - Social: 7, 8
    User age:                  28
    User gender:               male

[2] PROMPT sent to model:
You are a mobile app usage predictor. Based on the user's context, predict the most likely app category they will use next.
Be concise. Output ONLY the predicted category name and percentage.
Example format: Communication app (70%), Social app (20%), Navigation app (10%)

Context:
- The apps Communication, and Navigation are used prior to the prediction.
- On Monday 08:30AM.
- The user is close to subway station, and coffee shop.
- The apps 1, and 5 are used prior to the prediction.
- Installed apps:
- Communication apps: 1, 2, and 3
- Navigation apps: 5, and 6
- Social apps: 7, and 8
- The user's age is 28.
- The user's gender is male.

Prediction:
Based on the global information, the next app will be a 

[3] MODEL RAW OUTPUT:
    "<think>

</think>

Communication app (75%)"

[4] PARSED RESULT:
    -> Predicted app type: "Communication"  (confidence: 75%)

>>> Stage 2: Next App Prediction (NTP)
------------------------------------------------------------
[1] INPUT INFO:
    Stage 1 predicted type:    Communication (75%)
    Historical app IDs:        1, 5
    Installed apps:
      - Communication: 1, 2, 3
      - Navigation: 5, 6
      - Social: 7, 8

[2] PROMPT sent to model:
You are a mobile app usage predictor. Based on the predicted app category and additional user context, predict the specific app ID the user will use next.
Be concise. Output ONLY the prediction sentence.
Example format: This user will use App 4.

Predicted app category: Communication (75%).

Additional context:
- The apps 1, and 5 are used prior to the prediction.
- Installed apps:
- Communication apps: 1, 2, and 3
- Navigation apps: 5, and 6
- Social apps: 7, and 8
- On Monday 08:30AM.
- The user is close to subway station, and coffee shop.

Prediction:
This user will use App 

[3] MODEL RAW OUTPUT:
    "<think>

</think>

App 1"

[4] PARSED RESULT:
    -> Predicted specific app: App 1

============================================================
  FINAL PREDICTION SUMMARY
============================================================
  Scenario:  Morning commute, user checking communication apps
  App Type:  "Communication"
  App ID:    1
============================================================
```

### 结果解读

| 阶段 | 问题 | 答案 |
|-------|----------|--------|
| **ATP** | 用户接下来会打开**什么类型**的应用？ | **Communication**（置信度 75%） |
| **NTP** | 用户接下来会打开**哪个具体应用**？ | **App 1** |

---

## 7. C API（供胶水代码调用）

以下 C 兼容函数在 `maple_engine.h` 中暴露，可用于对接 Python（`ctypes`）或 Java（JNI）：

```c
typedef void* maple_engine_t;

maple_engine_t maple_engine_create(const char* model_path,
                                   int n_ctx, int n_threads,
                                   float temperature, int max_tokens);
void maple_engine_destroy(maple_engine_t engine);
void maple_engine_set_flags(maple_engine_t engine, uint32_t flags);

// 返回 0 表示成功；将 JSON 写入 out_buf
int maple_predict_app_type(maple_engine_t engine,
                           const char* context_json,
                           char* out_buf, size_t out_buf_size);

int maple_predict_next_app(maple_engine_t engine,
                           const char* context_json,
                           const char* stage1_json,
                           char* out_buf, size_t out_buf_size);
```

`maple_predict_app_type` 的 JSON 输入示例：

```json
{
  "historical_app_categories": ["Communication", "Navigation"],
  "historical_app_ids": [1, 5],
  "prediction_time": "Monday 08:30AM",
  "points_of_interest": ["subway station", "coffee shop"],
  "installed_apps": {
    "Communication": [1, 2, 3],
    "Navigation": [5, 6],
    "Social": [7, 8]
  },
  "user_age": "28",
  "user_gender": "male"
}
```

---

## 8. 说明

- **无需训练**：模型以零样本方式使用，预测质量完全依赖提示工程及 Qwen3.5-0.8B 的预训练知识。
- **上下文重建**：`LlamaBackend` 在每次生成前重建 `llama_context`，防止序列预测时 KV Cache 溢出。
- **日志静默**：通过 `llama_log_set()` 屏蔽了 llama.cpp 所有内部日志，终端输出保持整洁。
- **准确率声明**：如要求所述，"格式正确性 > 预测准确率"。本实现展示的是正确的 MAPLE 流水线结构，不声称达到 SOTA 准确率。
