# 实时模式与 MAPLE bitmask 调度

本文记录当前 MEMO-Appflow 的实时产品逻辑。目标不是“点一次按钮跑一批 host 脚本”，而是让 rooted Android 设备自己长期后台运行：持续收集真实前台 app 序列、raw eBPF/system evidence、设备状态，周期性交给 MAPLE 推理，并把推理结果转成 Top-3 app 和系统调度动作。

## 入口

对应代码：

| 功能 | 文件 |
| --- | --- |
| 主界面“开启实时模式/关闭实时模式” | `app/src/main/java/com/memoos/MainActivity.kt` |
| 后台服务 action 分发 | `app/src/main/java/com/memoos/ebpf/EBPFCollectorService.kt` |
| 实时窗口循环 | `app/src/main/java/com/memoos/realtime/RealtimePreloadController.kt` |
| 3 分钟窗口采集 | `app/src/main/java/com/memoos/realtime/RealtimeWindowRunner.kt` |
| 有界 EMA memory | `app/src/main/java/com/memoos/realtime/RealtimeMemoryStore.kt` |
| MAPLE scenario 构造 | `app/src/main/java/com/memoos/maple/MapleScenarioBuilder.kt` |
| MAPLE 输出解析 | `app/src/main/java/com/memoos/maple/MaplePrediction.kt`, `app/src/main/java/com/memoos/maple/MapleShellBackend.kt` |
| 系统动作执行 | `app/src/main/java/com/memoos/action/ActionExecutor.kt` |
| 桌面 Widget | `app/src/main/java/com/memoos/widget/MemoWidgetProvider.kt` |

## 算法

实时模式开启后，设备端一直循环：

```text
最近 3 分钟真实前台 app 采样
+ 最近 3 分钟 raw eBPF 事件
+ 当前 memory/battery/network/media/display/service 状态
+ 有界 EMA 历史 memory
-> Kotlin 构造 MAPLE scenario
-> MAPLE 预测接下来三个真实 app
-> MAPLE 输出 scheduler_bits
-> ActionExecutor 执行 0/1 对应的预编程调度动作
-> 刷新 App 首页和桌面 Widget 的 Top-3
```

历史 memory 不会无限增长。`RealtimeMemoryStore` 维护固定大小的 EMA 统计和最近窗口摘要：

```text
M_t = beta * M_(t-1) + alpha * x_t
alpha = 1 - 2^(-window_minutes / half_life_minutes)
```

当前默认窗口为 3 分钟，half-life 为 30 分钟。这样最近行为权重更高，但过去一段时间的使用习惯仍然保留；同时 key 数量和最近窗口数量都有上限，不会把 prompt 或本地存储撑爆。

## MAPLE 输入里有什么

`MapleScenarioBuilder` 给 MAPLE 的 context 包含：

- `observed_app_sequence`：带时间戳的真实前台 app 段，不是进程名。
- `timeline_windows`：按 app 时间线切分后的 eBPF 压缩统计，每个窗口保留 event type、detail、count、rate。
- `system_evidence`：memory、battery、network、camera/media、display/UI、process/service 的结构化摘要。
- `realtime_recent_window`：最近 3 分钟窗口的 raw 数量、压缩数量、app 摘要、eBPF 摘要。
- `realtime_memory`：EMA 历史特征，用来表示长期偏好和近期趋势。
- `available_scheduler_actions`：固定动作表，每个动作有 `bit_index`。

## MAPLE 输出要求

现在 prompt 明确要求预测用户接下来可能使用的三个真实 Android app，并输出系统调度 bitmask：

```json
{
  "top3_apps": ["browser", "camera", "messages"],
  "pressure_analysis": {
    "memory": "normal",
    "network": "active",
    "display": "busy"
  },
  "scheduler_bits": "101001010",
  "scheduler_plan": [
    {
      "action_id": "warm_launch_top1",
      "execute": true,
      "reason": "network/app sequence suggests browser follow-up"
    }
  ]
}
```

`scheduler_plan` 是可选解释；真正优先执行的是 `scheduler_bits`。如果模型只输出 bitmask，Android 端也能执行。

## bitmask 动作表

`scheduler_bits[i] == 1` 表示执行第 i 个预编程动作，`0` 表示跳过。

| bit | action_id | 系统动作 |
| --- | --- | --- |
| 0 | `warm_launch_top1` | 预热 Top-1 推荐 app，然后回 HOME。critical memory/thermal 时跳过。 |
| 1 | `warm_launch_top2_if_idle` | 仅在前台空闲、memory/thermal normal 时预热 Top-2。 |
| 2 | `trim_memory_low_priority_apps` | 对低优先级推荐候选发送 `am send-trim-memory RUNNING_LOW`。 |
| 3 | `kill_selected_background_package` | 仅在 memory pressure 下停止一个安全的非前台、非系统、非推荐 user-installed app。 |
| 4 | `drop_cache_if_critical_memory` | 仅在 critical memory 下执行 page-cache drop。 |
| 5 | `refresh_network_stats` | 网络证据强时刷新 `dumpsys netstats`。 |
| 6 | `refresh_service_manager` | Binder/service 证据强时刷新 `service list`。 |
| 7 | `reduce_prewarm_when_display_busy` | UI/display 忙时降低预热强度，避免 jank。 |
| 8 | `skip_camera_prewarm_when_thermal_high` | 热风险高时跳过 camera/media 预热。 |

这个设计把“大模型辅助调度”限制在安全边界内：MAPLE 只输出 0/1，不直接发明 shell 命令；真正能执行什么由 Android 端代码预先定义。

## 验证记录

本轮已验证：

- `.\gradlew.bat :app:assembleDebug` 构建成功。
- APK 已安装到连接的 Pixel 5：`adb install -r app\build\outputs\apk\debug\app-debug.apk` 成功。
- 通过 MainActivity 的 `memo_action=com.memoos.action.REALTIME_START` 启动实时模式后，可以看到 `memo_libbpf_collector` 进程。
- 通过 `memo_action=com.memoos.action.REALTIME_STOP` 停止后，`memo_libbpf_collector` 和 `maple_demo` 均无残留进程。

