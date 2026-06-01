package com.memoos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.memoos.action.AppIdMapping
import com.memoos.device.RootShell
import com.memoos.ebpf.EBPFCollectorService
import com.memoos.perf.PipelineLatency
import com.memoos.store.ActionState
import com.memoos.store.LastMemoState
import com.memoos.store.MemoStore
import com.memoos.store.RecommendationState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var showDiagnostics = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var lastRenderedSignature = ""
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStateIfChanged()
            refreshHandler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RootShell.configureBridge(this)
        MemoStore(this).clearSyntheticDemoState()
        requestNotificationPermission()
        setContentView(buildContent())
        handleCommandIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        renderState(force = true)
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCommandIntent(intent)
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        scroll.addView(root)
        renderState(force = true)
        return scroll
    }

    private fun refreshStateIfChanged() {
        if (!::root.isInitialized) return
        val state = MemoStore(this).load()
        val signature = stateSignature(state)
        if (signature != lastRenderedSignature) {
            renderState(state, signature)
        }
    }

    private fun renderState(force: Boolean = false) {
        if (!::root.isInitialized) return
        val state = MemoStore(this).load()
        val signature = stateSignature(state)
        if (!force && signature == lastRenderedSignature) return
        renderState(state, signature)
    }

    private fun renderState(state: LastMemoState, signature: String = stateSignature(state)) {
        root.removeAllViews()

        root.addView(title("MEMO-Appflow"))
        root.addView(subtitle("观察真实手机使用，预测接下来可能用到的应用，并在后台做资源调度"))
        root.addView(statusPanel(state))
        root.addView(controlPanel(state))
        root.addView(recommendationsPanel(state))
        root.addView(maplePanel(state))
        root.addView(actionsPanel(state))
        root.addView(observationAndAnalysisPanel(state))
        root.addView(ablationReportPanel(state))
        root.addView(pressureReportPanel(state))
        root.addView(latencyPanel(state))
        root.addView(experimentPanel())
        root.addView(diagnosticsToggle())
        if (showDiagnostics) {
            root.addView(diagnosticsPanel(state))
        }
        lastRenderedSignature = signature
    }

    private fun statusPanel(state: LastMemoState): View {
        val updated = if (state.updatedAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(state.updatedAt))
        } else {
            "还没有运行"
        }
        val panel = verticalPanel()
        panel.addView(label("现在能做什么"))
        val rootIssue = hasRootIssue(state)
        if (rootIssue) {
            panel.addView(statusText("需要处理 root 授权", Color.rgb(185, 28, 28)))
            panel.addView(body("Magisk 当前拒绝了 MEMO-Appflow 的 superuser 权限。没有这个权限，eBPF 采集、系统状态读取和调度动作都会失败。"))
            panel.addView(openMagiskButton())
        } else if (state.freeUsageSession.active) {
            panel.addView(statusText("正在记录自由体验", Color.rgb(37, 99, 235)))
            panel.addView(body("可以离开 MEMO 正常打开任意应用。回来点“结束体验并分析”，这里会生成刚才这段使用的 app 序列、系统证据、Top-3 推荐和调度动作。"))
        } else if (state.maple.backend == "pending") {
            panel.addView(statusText("正在后台推理", Color.rgb(180, 83, 9)))
            panel.addView(body("你可以继续正常使用手机。MAPLE 完成后，Top-3 应用和系统动作会自动刷新。"))
        } else if (state.recommendations.isNotEmpty()) {
            panel.addView(statusText("已经生成推荐", Color.rgb(22, 101, 52)))
            panel.addView(body("MEMO 已根据真实系统证据生成 Top-3 应用，并执行了非侵入式调度策略。"))
        } else {
            panel.addView(statusText("等待开始", Color.rgb(37, 99, 235)))
            panel.addView(body("先点“检查设备授权”，确认 root、eBPF collector 和 MAPLE 模型可用；然后点“智能优化：刷新当前推荐”。"))
        }
        panel.addView(kv("上次更新", updated))
        panel.addView(kv("当前证据", friendlyEvidence(evidenceHeadline(state))))
        return panel
    }

    private fun latencyPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("运行耗时"))
        if (!state.latency.isPresent) {
            panel.addView(body("还没有耗时数据。开始优化后，这里会显示采集、整理、MAPLE 推理和动作执行用了多久。"))
            return panel
        }
        val statusColor = when (state.latency.realtimeStatus) {
            "completed" -> Color.rgb(22, 101, 52)
            "maple_timeout" -> Color.rgb(185, 28, 28)
            else -> Color.rgb(180, 83, 9)
        }
        panel.addView(statusText(latencyStatusLabel(state.latency.realtimeStatus), statusColor))
        panel.addView(kv("前台可见处理耗时", formatDuration(state.latency.foregroundMs)))
        panel.addView(kv("完整设备端耗时", formatDuration(state.latency.totalMs)))
        panel.addView(kv("读取到的系统事件", "${state.latency.parsedEvents} 条"))
        state.latency.stages
            .sortedByDescending { it.durationMs }
            .take(6)
            .forEach { stage -> panel.addView(bullet("${stage.name}: ${formatDuration(stage.durationMs)}")) }
        return panel
    }

    private fun controlPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("开始使用"))
        panel.addView(smallCaption("两个入口的区别"))
        panel.addView(body("智能优化是短窗口刷新：MEMO 立刻采集当前几秒的 eBPF 和系统状态，不主动切换其他应用，然后用 MAPLE 更新 Top-3 和调度动作。适合刚刚打开聊天、浏览器、相机、视频等场景后快速刷新推荐。"))
        panel.addView(body("自由体验是一段完整记录：先开始记录，离开 MEMO 打开若干真实应用，回到 MEMO 结束分析；MEMO 会生成这段时间的 app 序列、压缩 eBPF、MAPLE 结果、Top-3 和调度报告。"))
        panel.addView(rowButton("检查设备授权", EBPFCollectorService.ACTION_CHECK_SETUP, primary = false))
        panel.addView(rowButton("智能优化：刷新当前推荐", EBPFCollectorService.ACTION_RUN_ONCE, primary = true))
        if (state.freeUsageSession.active) {
            panel.addView(rowButton("结束体验并分析", EBPFCollectorService.ACTION_FREE_USAGE_FINISH, primary = true))
        } else {
            panel.addView(rowButton("开始自由体验", EBPFCollectorService.ACTION_FREE_USAGE_START, primary = false))
        }
        panel.addView(rowButton("记录当前窗口", EBPFCollectorService.ACTION_RECORD_CURRENT_USAGE, primary = false))
        panel.addView(rowButton("停止后台任务", EBPFCollectorService.ACTION_STOP, primary = false))

        return panel
    }

    private fun experimentPanel(): View {
        val panel = verticalPanel()
        panel.addView(label("实验和报告"))
        panel.addView(body("这些入口用于复现实验、生成报告和做消融分析；普通使用时只需要上面的智能优化和 Top-3 推荐。"))
        panel.addView(experimentDesign("100 次真实使用分析", "在手机本地连续打开真实可启动应用，同步采集 eBPF，再让 MAPLE 生成 Top-3 和调度动作。看的是产品闭环是否真实跑通。"))
        panel.addView(experimentDesign("手机压力 A/B 实验", "同一批真实 app workload 分别在 MEMO 关闭和开启后运行，比较启动耗时、CPU、内存回收和综合压力。看的是 MEMO 对手机整体使用压力的影响。"))
        panel.addView(experimentDesign("真实证据消融", "固定同一次真实使用 scenario，逐项删掉网络、内存、显示、Binder 等证据。看的是每类 eBPF 证据对预测和调度有多重要。"))
        panel.addView(rowButton("100 次真实使用分析", EBPFCollectorService.ACTION_USAGE_100_ANALYSIS, primary = false))
        panel.addView(rowButton("滚动场景完整评估", EBPFCollectorService.ACTION_FULL_LOCAL_EVALUATION, primary = false))
        panel.addView(rowButton("手机压力 A/B 实验", EBPFCollectorService.ACTION_PRESSURE_EXPERIMENT, primary = false))
        panel.addView(rowButton("滚动/显示场景采集", EBPFCollectorService.ACTION_EXPERIMENT_SCROLL, primary = false))
        panel.addView(rowButton("通信场景采集", EBPFCollectorService.ACTION_EXPERIMENT_COMMUNICATION, primary = false))
        panel.addView(rowButton("媒体场景采集", EBPFCollectorService.ACTION_EXPERIMENT_MEDIA, primary = false))
        panel.addView(rowButton("最新真实证据消融", EBPFCollectorService.ACTION_REAL_ABLATION_LATEST, primary = false))
        return panel
    }

    private fun recommendationsPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("Top-3 推荐应用"))
        panel.addView(kv("最近更新", formatTimestamp(state.recommendationsUpdatedAt)))
        if (state.recommendations.isEmpty()) {
            panel.addView(body("还没有推荐。运行“智能优化：刷新当前推荐”后，这里只会显示手机上真实可打开的应用，不会显示进程名或系统服务。"))
            panel.addView(body("每次完成智能优化或自由体验分析后，MEMO 都会用最新系统证据重新计算这里的三个应用。"))
            return panel
        }
        state.recommendations.forEachIndexed { index, app ->
            panel.addView(appRow(index + 1, app))
        }
        panel.addView(body("Top-3 来自最近一次 MAPLE 推理。每次智能优化或自由体验结束都会重新计算；如果最近场景相似，应用和顺序可能保持不变，更新时间会继续刷新。"))
        panel.addView(rowButton("立即预热第 1 个推荐", EBPFCollectorService.ACTION_WARM_TOP_APP, primary = false))
        return panel
    }

    private fun maplePanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("系统判断"))
        if (state.maple.available) {
            panel.addView(kv("推理引擎", state.maple.backend))
            panel.addView(kv("内部预测 ID", state.maple.predictedAppId.takeIf { it > 0 }?.toString() ?: "无固定 ID"))
            if (state.maple.stage1.isNotEmpty()) {
                panel.addView(body("接下来可能需要的资源："))
                state.maple.stage1.take(4).forEach { panel.addView(chip(friendlyStageLabel(it))) }
            }
        } else {
            panel.addView(statusText(if (state.maple.backend == "pending") "推理中" else "暂不可用", Color.rgb(180, 83, 9)))
            panel.addView(body(friendlyError(state.maple.error ?: "MAPLE 模型或本地推理引擎还不可用。")))
        }
        return panel
    }

    private fun actionsPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("MEMO 已做的系统优化"))
        if (state.actions.isEmpty()) {
            panel.addView(body("还没有执行动作。运行智能优化后，这里会显示 MEMO 是否更新 Widget、降低预热强度、刷新网络状态、处理内存压力等。"))
            return panel
        }
        state.actions.takeLast(8).forEach { panel.addView(actionRow(it)) }
        return panel
    }

    private fun observationAndAnalysisPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        val report = jsonOrNull(state.usageReportJson)
        val isFreeSession = report?.optString("session_mode") == "free_usage" || report?.optString("experiment_type") == "free_usage_session"
        panel.addView(label("本次观察与分析"))
        if (report == null) {
            panel.addView(body("还没有完整使用分析。可以点“开始自由体验”，打开几个应用后回来点“结束体验并分析”；也可以点“100 次真实使用分析”跑固定实验。"))
            appendObservationSummary(panel, state)
            panel.addView(detailButton("查看 eBPF 证据详情") {
                startActivity(Intent(this, EvidenceActivity::class.java))
            })
            return panel
        }
        val launch = report.optJSONObject("launch_metrics")
        val ebpf = report.optJSONObject("ebpf")
        val delta = report.optJSONObject("system_delta")
        panel.addView(smallCaption("实验设计"))
        if (isFreeSession) {
            panel.addView(body("开始自由体验后，MEMO 在后台记录前台应用变化并采集 eBPF。结束体验时，手机本地把这段真实使用整理成 app 序列、系统证据、MAPLE 推荐和调度动作。"))
        } else {
            panel.addView(body("MEMO 在手机本地轮流打开真实应用 100 次，每 5 次作为一个时间窗同步采集 eBPF。MAPLE 看到的是按时间对齐的 app 使用序列和压缩后的系统证据。"))
        }
        val observed = report.optInt("interaction_count_observed")
        val requested = report.optInt("interaction_count_requested", observed)
        val openGuide = if (isFreeSession) {
            "完整性指标。这里表示刚才自由体验里观察到的连续 app 片段数。"
        } else {
            "完整性指标。越接近 100/100，说明实验越完整。"
        }
        panel.addView(metricRow("真实 app 打开", "$observed/$requested 次", openGuide, Color.rgb(37, 99, 235)))
        panel.addView(metricRow("覆盖应用", "${report.optInt("unique_apps_opened")} 个", "多样性指标。越大说明覆盖的真实 app 场景越多，不直接代表性能更好。", Color.rgb(37, 99, 235)))
        panel.addView(metricRow("系统事件", "${ebpf?.optInt("parsed_events") ?: 0} 条", "证据规模。不是越大越好，过少会缺信息；太多会给模型带来重复，所以 MAPLE 输入会再压缩。", Color.rgb(37, 99, 235)))
        val compressedSignals = compressedSignalCount(report)
        if (compressedSignals > 0) {
            panel.addView(metricRow("模型输入信号", "$compressedSignals 条", "压缩后的 eBPF 信号。它把重复事件合成 count 和 rate，让小模型更容易读。", Color.rgb(37, 99, 235)))
        }
        if (isFreeSession) {
            panel.addView(metricRow("平均停留时长", formatNumberMs(launch?.opt("avg_dwell_time_ms")), "体验描述指标。表示这段自由体验里每个 app 片段大概停留多久，不是越大越好。", Color.rgb(37, 99, 235)))
            panel.addView(metricRow("P50 停留时长", formatNumberMs(launch?.opt("p50_dwell_time_ms")), "一半 app 片段不超过这个时长，用来理解刚才是否频繁切换。", Color.rgb(37, 99, 235)))
        } else {
            panel.addView(metricRow("平均启动耗时", formatNumberMs(launch?.opt("avg_total_time_ms")), "性能指标。越小越好，表示这段 app 打开平均更快。", Color.rgb(22, 101, 52)))
            panel.addView(metricRow("P50 启动耗时", formatNumberMs(launch?.opt("p50_total_time_ms")), "典型体验。越小越好，代表一半 app 打开不超过这个时间。", Color.rgb(22, 101, 52)))
        }
        panel.addView(metricRow("可用内存变化", formatDeltaKb(delta?.opt("mem_available_delta_kb")), "稳定性指标。负数表示可用内存减少；绝对值越小通常越稳，正数表示释放了可用内存。", Color.rgb(180, 83, 9)))
        panel.addView(metricRow("网络收发变化", "收 ${formatLong(delta?.opt("udp_in_delta"))} / 发 ${formatLong(delta?.opt("udp_out_delta"))}", "场景描述指标。数值越大说明这段使用里网络越活跃，不直接表示好坏。", Color.rgb(37, 99, 235)))
        panel.addView(metricRow("内存回收压力", "直接回收 ${formatLong(delta?.opt("pgscan_direct_delta"))} / 后台回收 ${formatLong(delta?.opt("pgscan_kswapd_delta"))}", "压力指标。一般越小越好，数值高说明系统更频繁地回收内存。", Color.rgb(180, 83, 9)))
        report.optJSONObject("optimization_effect")?.let { optimization ->
            panel.addView(metricRow("已应用优化", "${optimization.optInt("executed_count")} 个执行 / ${optimization.optInt("prepared_count")} 个准备", "这里统计 MAPLE 结果转成的调度动作，包括推荐展示、网络状态刷新、内存策略、显示策略和候选预热。", Color.rgb(22, 101, 52)))
        }
        panel.addView(smallCaption("打开最多的真实应用"))
        report.optJSONArray("app_usage_top").takeObjects(5).forEach { app ->
            panel.addView(text("${app.optString("label")}：${app.optInt("count")} 次", 13f, Color.rgb(51, 65, 85), bottom = 4))
        }
        appendObservationSummary(panel, state)
        panel.addView(detailButton("查看完整使用报告") {
            openReport("usage")
        })
        panel.addView(detailButton("查看 eBPF 证据详情") {
            startActivity(Intent(this, EvidenceActivity::class.java))
        })
        return panel
    }

    private fun ablationReportPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("消融实验分析"))
        val report = jsonOrNull(state.ablationReportJson)
        if (report == null) {
            panel.addView(body("还没有消融报告。点“最新真实证据消融”会基于最近一次真实 eBPF scenario 跑；点“100 次真实使用分析”会在完整使用分析后自动跑消融。"))
            return panel
        }
        val summary = report.optJSONObject("summary")
        panel.addView(smallCaption("实验设计"))
        panel.addView(body("固定同一次真实 eBPF scenario，然后逐项移除网络、相机/媒体、显示、Binder、内存等证据，观察 MAPLE 预测和 ActionExecutor 调度是否改变。改变越明显，说明该证据模块越重要。"))
        panel.addView(kv("配置数量", "${summary?.optInt("config_count") ?: 0} 组"))
        panel.addView(kv("MAPLE 可用配置", "${summary?.optInt("maple_available_count") ?: 0} 组"))
        panel.addView(kv("预测 ID 被改变", joinAblationArray(summary?.optJSONArray("changed_predicted_app_configs"))))
        panel.addView(kv("Top-1 应用被改变", joinAblationArray(summary?.optJSONArray("changed_top1_app_configs"))))
        panel.addView(kv("调度域被改变", joinAblationArray(summary?.optJSONArray("changed_predicted_scheduler_domain_configs"))))
        panel.addView(kv("平均端到端耗时", formatNumberMs(summary?.opt("avg_end_to_end_ms"))))
        panel.addView(detailButton("查看完整消融报告") {
            openReport("ablation")
        })
        return panel
    }

    private fun pressureReportPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("手机性能 A/B 实验"))
        val report = jsonOrNull(state.pressureReportJson)
        if (report == null) {
            panel.addView(body("还没有性能 A/B 报告。点“手机压力 A/B 实验”后，MEMO 会比较同一批真实 app workload 在 MEMO 关闭和 MEMO 开启后的启动耗时、CPU、内存回收、PSI 和综合压力分数。"))
            return panel
        }
        val summary = report.optJSONObject("summary")
        panel.addView(smallCaption("实验设计"))
        panel.addView(body("同一批真实 app workload 分别在 MEMO 关闭和开启后执行。正数表示 MEMO-on 相比 baseline 更好，负数说明该指标或场景没有改善。"))
        panel.addView(kv("对比 workload", "${summary?.optInt("workloads_compared") ?: 0} 组"))
        panel.addView(kv("综合压力改善", formatPct(summary?.opt("avg_pressure_score_improvement_pct"))))
        panel.addView(kv("启动耗时改善", formatPct(summary?.opt("avg_launch_total_time_improvement_pct"))))
        panel.addView(kv("等待耗时改善", formatPct(summary?.opt("avg_wait_time_improvement_pct"))))
        panel.addView(kv("CPU busy 改善", formatPct(summary?.opt("avg_cpu_busy_improvement_pct"))))
        panel.addView(kv("内存回收改善", formatPct(summary?.opt("avg_reclaim_improvement_pct"))))
        panel.addView(smallCaption("解释"))
        panel.addView(body("正数表示 MEMO-on 比 baseline 压力或延迟更低；负数会原样显示，说明该指标在当前手机和 workload 下没有改善。"))
        panel.addView(detailButton("查看完整性能报告") {
            openReport("pressure")
        })
        return panel
    }

    private fun appendObservationSummary(panel: LinearLayout, state: LastMemoState) {
        panel.addView(smallCaption("系统证据摘要"))
        val observations = observationCards(state)
        if (observations.isEmpty()) {
            panel.addView(body("还没有采集到系统证据。"))
        } else {
            panel.addView(body("下面是 MEMO 把原始 eBPF、app 使用记录和系统状态整理后的可读摘要。原始记录可以点详情查看。"))
            observations.take(6).forEach { panel.addView(observationItem(it)) }
        }
    }

    private fun diagnosticsToggle(): View {
        return Button(this).apply {
            text = if (showDiagnostics) "隐藏高级诊断" else "显示高级诊断"
            isAllCaps = false
            setTextColor(Color.rgb(15, 23, 42))
            background = rounded(Color.rgb(226, 232, 240), dp(8))
            setOnClickListener {
                showDiagnostics = !showDiagnostics
                renderState(force = true)
            }
            layoutParams = LinearLayout.LayoutParams(match(), dp(46)).apply { bottomMargin = dp(12) }
        }
    }

    private fun diagnosticsPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("高级诊断"))
        panel.addView(kv("原始证据行数", state.evidenceLines.size.toString()))
        panel.addView(kv("Scenario JSON", if (state.scenarioJson.isBlank()) "未生成" else "${state.scenarioJson.length} 字符"))
        panel.addView(kv("MAPLE raw JSON", if (state.rawMapleJson.isBlank()) "空" else "${state.rawMapleJson.length} 字符"))
        panel.addView(kv("Actions raw JSON", if (state.rawActionsJson.isBlank()) "空" else "${state.rawActionsJson.length} 字符"))
        panel.addView(kv("Latency raw JSON", if (state.rawLatencyJson.isBlank()) "空" else "${state.rawLatencyJson.length} 字符"))

        val scanned = AppIdMapping.scanInstalledApps(this)
        panel.addView(kv("可启动应用数量", scanned.size.toString()))
        scanned.take(8).forEach { app ->
            panel.addView(bullet("${app.label}: ${app.inferredCategories.take(4).joinToString()}"))
        }
        if (state.evidenceLines.isNotEmpty()) {
            panel.addView(smallCaption("最近一次真实证据摘要"))
            state.evidenceLines.take(10).forEach { panel.addView(monoLine(it)) }
        }
        return panel
    }

    private fun rowButton(label: String, action: String, primary: Boolean): View {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else Color.rgb(15, 23, 42))
            background = rounded(if (primary) Color.rgb(37, 99, 235) else Color.rgb(226, 232, 240), dp(8))
            setOnClickListener {
                val intent = Intent(this@MainActivity, EBPFCollectorService::class.java).setAction(action)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                root.postDelayed({ renderState() }, 1800)
            }
            layoutParams = LinearLayout.LayoutParams(match(), dp(48)).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
        }
    }

    private fun handleCommandIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_MEMO_ACTION) ?: intent?.action
        if (action !in SERVICE_ACTIONS) return
        val serviceIntent = Intent(this, EBPFCollectorService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        if (::root.isInitialized) {
            root.postDelayed({ renderState() }, 1800)
        }
    }

    private fun appRow(rank: Int, app: RecommendationState): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val icon = ImageView(this).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull())
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) }
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
        }
        info.addView(text("$rank. ${app.label}", 16f, Color.rgb(15, 23, 42), bold = true))
        info.addView(text(friendlyCategory(app.category), 13f, Color.rgb(37, 99, 235)))
        info.addView(text(recommendationReason(app), 12f, Color.rgb(71, 85, 105)))

        val open = Button(this).apply {
            text = "打开"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(15, 23, 42), dp(8))
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
        }
        row.addView(icon)
        row.addView(info)
        row.addView(open, LinearLayout.LayoutParams(dp(82), dp(42)))
        return row
    }

    private fun actionRow(action: ActionState): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val statusColor = when (action.status) {
            "ok" -> Color.rgb(22, 101, 52)
            "blocked", "failed" -> Color.rgb(185, 28, 28)
            "skipped", "unsupported" -> Color.rgb(180, 83, 9)
            else -> Color.rgb(71, 85, 105)
        }
        row.addView(text("${actionTitle(action.name)}：${friendlyActionTarget(action)}", 14f, Color.rgb(15, 23, 42), bold = true))
        row.addView(statusText(actionStatus(action.status), statusColor))
        if (action.detail.isNotBlank()) row.addView(text(friendlyActionDetail(action.detail), 12f, Color.rgb(71, 85, 105)))
        return row
    }

    private fun verticalPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(14) }
        }
    }

    private fun title(value: String) = text(value, 28f, Color.rgb(15, 23, 42), bold = true, bottom = 4)
    private fun subtitle(value: String) = text(value, 15f, Color.rgb(71, 85, 105), bottom = 16)
    private fun label(value: String) = text(value, 18f, Color.rgb(15, 23, 42), bold = true, bottom = 10)
    private fun body(value: String) = text(value, 14f, Color.rgb(51, 65, 85), bottom = 6)
    private fun smallCaption(value: String) = text(value, 12f, Color.rgb(100, 116, 139), top = 8, bottom = 4)

    private fun kv(key: String, value: String): View {
        return text("$key: $value", 14f, Color.rgb(51, 65, 85), bottom = 5)
    }

    private fun bullet(value: String): View {
        return text("- $value", 13f, Color.rgb(51, 65, 85), bottom = 4)
    }

    private fun experimentDesign(title: String, detail: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(248, 250, 252), dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) }
            addView(text(title, 13f, Color.rgb(15, 23, 42), bold = true, bottom = 3))
            addView(text(detail, 12f, Color.rgb(71, 85, 105)))
        }
    }

    private fun metricRow(title: String, value: String, guide: String, color: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            layoutParams = LinearLayout.LayoutParams(match(), wrap())
        }
        row.addView(View(this).apply {
            background = rounded(color, dp(2))
        }, LinearLayout.LayoutParams(dp(4), dp(72)).apply { rightMargin = dp(10) })
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
        }
        textBlock.addView(text(title, 12f, Color.rgb(100, 116, 139), bottom = 1))
        textBlock.addView(text(value, 17f, Color.rgb(15, 23, 42), bold = true, bottom = 2))
        textBlock.addView(text(guide, 12f, Color.rgb(71, 85, 105)))
        row.addView(textBlock)
        return row
    }

    private fun evidenceItem(raw: String): View {
        val value = friendlyEvidence(raw)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(248, 250, 252), dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) }
            addView(text(evidenceTitle(raw), 13f, Color.rgb(15, 23, 42), bold = true, bottom = 3))
            addView(text(value, 12f, Color.rgb(71, 85, 105)))
        }
    }

    private fun observationItem(item: ObservationItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap())
            addView(View(this@MainActivity).apply {
                background = rounded(item.color, dp(2))
            }, LinearLayout.LayoutParams(dp(4), dp(72)).apply { rightMargin = dp(10) })
            val info = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
            }
            info.addView(text(item.title, 13f, Color.rgb(15, 23, 42), bold = true, bottom = 2))
            info.addView(text(item.value, 15f, Color.rgb(30, 41, 59), bold = true, bottom = 2))
            info.addView(text(item.detail, 12f, Color.rgb(71, 85, 105)))
            addView(info)
        }
    }

    private fun detailButton(label: String, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.rgb(37, 99, 235))
            background = rounded(Color.rgb(219, 234, 254), dp(8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(match(), dp(44)).apply { topMargin = dp(6) }
        }
    }

    private fun chip(value: String): View {
        return text(value, 13f, Color.rgb(15, 23, 42), bottom = 6).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.rgb(219, 234, 254), dp(16))
        }
    }

    private fun statusText(value: String, color: Int): View {
        return text(value, 12f, color, bold = true, bottom = 3)
    }

    private fun monoLine(value: String): TextView {
        return text(value, 11f, Color.rgb(51, 65, 85), bottom = 3).apply {
            typeface = Typeface.MONOSPACE
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        top: Int = 0,
        bottom: Int = 0,
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.14f)
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply {
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            }
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun actionTitle(name: String): String {
        return when (name) {
            "widget_update" -> "更新推荐展示"
            "latency_policy" -> "异步推理策略"
            "latency_summary" -> "耗时总结"
            "maple_background" -> "MAPLE 后台推理"
            "memory_policy" -> "内存策略"
            "memory_idle_maintenance" -> "空闲维护"
            "memory_trim" -> "内存回收提示"
            "cache_pressure_response" -> "缓存压力响应"
            "thermal_policy" -> "温度策略"
            "thermal_power_mode" -> "功耗模式"
            "warm_launch" -> "轻量预热"
            "warm_launch_policy" -> "预热策略"
            "network_candidate_priority" -> "网络应用优先"
            "network_stats_refresh" -> "网络状态刷新"
            "camera_media_candidate" -> "相机/媒体候选"
            "camera_media_prewarm" -> "相机/媒体策略"
            "display_ui_policy" -> "界面流畅策略"
            "binder_service_policy" -> "系统服务策略"
            "binder_service_refresh" -> "服务状态刷新"
            "maple_backend" -> "MAPLE 引擎"
            "root_permission" -> "Root 授权"
            "collector_runtime" -> "eBPF 运行环境"
            "maple_runtime" -> "MAPLE 模型环境"
            "pipeline_stop" -> "停止后台任务"
            "free_usage_session" -> "自由体验分析"
            "real_usage_100_analysis" -> "100 次真实使用分析"
            "real_ebpf_ablation" -> "真实 eBPF 消融"
            "user_app_pressure_ab" -> "手机压力 A/B 实验"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun evidenceHeadline(state: LastMemoState): String {
        val first = state.evidenceLines.firstOrNull()
        if (first != null && ("failed" in first || "capture produced zero" in first)) {
            return "上次采集没有成功，已保留原因；可以检查授权后重新运行智能优化"
        }
        return state.evidenceLines.firstOrNull { "records" in it }
            ?: first
            ?: "No evidence collected yet"
    }

    private fun formatDuration(ms: Long): String = PipelineLatency.formatMs(ms)

    private fun formatTimestamp(timestampMs: Long): String {
        if (timestampMs <= 0L) return "还没有更新"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    private fun stateSignature(state: LastMemoState): String {
        return listOf(
            state.updatedAt,
            state.recommendationsUpdatedAt,
            state.recommendations.size,
            state.maple.backend,
            state.maple.available,
            state.actions.size,
            state.latency.totalMs,
            state.usageReportJson.length,
            state.ablationReportJson.length,
            state.pressureReportJson.length,
            state.freeUsageSession.active,
            state.freeUsageSession.startedAtMs,
        ).joinToString("|")
    }

    private fun hasRootIssue(state: LastMemoState): Boolean {
        val latestRoot = state.actions.lastOrNull { it.name == "root_permission" }
        if (latestRoot?.status == "ok") return false
        val values = buildList {
            state.maple.error?.let { add(it) }
            state.actions.forEach { add("${it.name} ${it.status} ${it.detail}") }
            state.evidenceLines.forEach { add(it) }
        }.joinToString("\n").lowercase(Locale.US)
        return "superuser" in values || "root unavailable" in values || "denied" in values || "raw libbpf collector is required" in values
    }

    private fun openMagiskButton(): View {
        return Button(this).apply {
            text = "打开 Magisk 授权"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(185, 28, 28), dp(8))
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")
                if (launch != null) {
                    startActivity(launch)
                }
            }
            layoutParams = LinearLayout.LayoutParams(match(), dp(46)).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        }
    }

    private fun openReport(section: String) {
        startActivity(
            Intent(this, ReportActivity::class.java)
                .putExtra(ReportActivity.EXTRA_SECTION, section),
        )
    }

    private fun friendlyEvidence(value: String): String {
        return value
            .replace("Strict real eBPF experiment failed", "真实使用采集没有成功")
            .replace("Strict eBPF pipeline failed", "eBPF 采集流程没有成功")
            .replace("real raw eBPF capture produced zero MEMO events", "这次采集窗口没有读到 MEMO 事件")
            .replace("raw eBPF capture produced zero MEMO events", "这次采集窗口没有读到 MEMO 事件")
            .replace("raw eBPF collector did not attach before user interaction", "eBPF 采集器还没启动好，当前操作已经开始")
            .replace("device-side eBPF records in the observed Android window", "条系统级事件来自当前手机使用窗口")
            .replace("event_type", "系统事件")
            .replace("MAPLE evidence/resource category", "资源信号")
            .replace("observed user action target app=", "本次观察的应用：")
            .replace("count=", "数量=")
    }

    private fun evidenceTitle(value: String): String {
        return when {
            "records" in value -> "采集规模"
            value.startsWith("event_type") -> "内核事件"
            value.startsWith("MAPLE evidence") -> "资源信号"
            value.startsWith("memory") -> "内存状态"
            value.startsWith("battery") -> "电池/温度"
            value.startsWith("network") -> "网络状态"
            "failed" in value || "capture produced zero" in value -> "采集状态"
            else -> "系统证据"
        }
    }

    private fun friendlyError(value: String): String {
        return value
            .replace("MEMO-Appflow is denied superuser rights. Open Magisk -> Superuser -> MEMO-Appflow -> Allow, then tap Check Device Setup again.", "Magisk 拒绝了 MEMO-Appflow 的 root 权限。请打开 Magisk，在 Superuser 里把 MEMO-Appflow 改成 Allow，然后回到这里点“检查设备授权”。")
            .replace("raw libbpf collector is required", "需要 raw eBPF collector")
            .replace("phone-local MAPLE runtime is incomplete", "手机本地 MAPLE 运行环境不完整")
            .replace("Strict eBPF pipeline failed", "eBPF 采集流程失败")
            .replace("Strict MAPLE inference failed", "MAPLE 推理失败")
    }

    private fun actionStatus(status: String): String {
        return when (status) {
            "ok" -> "已执行"
            "blocked" -> "需要处理"
            "failed" -> "失败"
            "skipped" -> "已跳过"
            "planned" -> "已准备"
            "unsupported" -> "设备不支持"
            "pending" -> "等待中"
            "completed" -> "已完成"
            else -> status.uppercase(Locale.US)
        }
    }

    private fun latencyStatusLabel(status: String): String {
        return when (status) {
            "completed" -> "已完成"
            "maple_timeout" -> "MAPLE 安全超时"
            "pending" -> "等待中"
            "unknown" -> "未知"
            else -> status.replace('_', ' ')
        }
    }

    private fun friendlyCategory(category: String): String {
        val lower = category.lowercase(Locale.US)
        return when {
            "network" in lower || "udp" in lower -> "网络通信"
            "communication" in lower || "binder" in lower || "service ipc" in lower -> "通信/系统服务"
            "camera" in lower || "photo" in lower -> "相机/图片"
            "media" in lower || "codec" in lower || "video" in lower -> "音视频"
            "payment" in lower || "wallet" in lower || "security" in lower -> "支付/安全"
            "location" in lower || "navigation" in lower || "map" in lower -> "位置/导航"
            "display" in lower || "render" in lower -> "界面显示"
            "memory" in lower || "reclaim" in lower -> "内存状态"
            "runtime" in lower || "process" in lower -> "应用运行状态"
            else -> category
        }
    }

    private fun friendlyStageLabel(value: String): String {
        val category = value.substringBefore(" (")
        val suffix = value.substringAfter(category, "").trim()
        return "${friendlyCategory(category)} $suffix".trim()
    }

    private fun friendlyActionTarget(action: ActionState): String {
        return when (action.name) {
            "widget_update" -> "推荐卡片"
            "latency_policy", "maple_background" -> "后台推理"
            "latency_summary" -> "整条流水线"
            "memory_policy", "thermal_policy", "thermal_power_mode" -> "预热强度"
            "memory_idle_maintenance", "memory_trim", "cache_pressure_response" -> "内存和缓存"
            "network_candidate_priority" -> readableAppList(action.target)
            "network_stats_refresh" -> "网络状态"
            "camera_media_candidate", "camera_media_prewarm" -> "相机/媒体资源"
            "display_ui_policy" -> "界面流畅度"
            "binder_service_policy", "binder_service_refresh" -> "系统服务"
            "warm_launch" -> "候选应用预热"
            "warm_launch_policy" -> "后台预热准备"
            "root_permission" -> "Magisk 授权"
            "collector_runtime" -> "eBPF 采集器"
            "maple_runtime" -> "本地模型"
            "pipeline_stop" -> "后台任务"
            "free_usage_session" -> "自由体验流程"
            "real_usage_100_analysis" -> "真实使用报告"
            "real_ebpf_ablation" -> "消融报告"
            else -> action.target
        }
    }

    private fun friendlyActionDetail(value: String): String {
        return friendlyError(value)
            .replace("published 3 real app recommendations", "已经把 3 个真实可打开应用发布到推荐区和 Widget。")
            .replace("normal memory; lightweight warm launch remains enabled", "当前内存状态正常，可以保留轻量预热能力。")
            .replace("normal memory; warm launch budget allowed", "当前内存状态正常，可以保留轻量预热能力。")
            .replace("normal battery thermal state", "当前电池温度正常，不需要降低预热强度。")
            .replace("UDP sendto/recvfrom evidence prioritizes network-capable apps", "检测到 UDP 网络收发，优先保留网络类应用。")
            .replace("refreshed network stats before recommendation", "推荐前已经刷新网络状态。")
            .replace("Binder/system-service evidence kept as MAPLE scheduling context", "Binder 和系统服务活动已经进入 MAPLE 调度上下文。")
            .replace("queried service manager after high Binder activity", "检测到 Binder 活跃后刷新了系统服务状态。")
            .replace("non-intrusive background mode; do not switch visible apps while the user continues using the phone", "后台模式不切换当前屏幕，避免打断正在进行的操作。")
            .replace("background mode prepared warm-launch candidates but did not switch the screen; tap explicit prewarm to execute", "已经准备好候选应用。为了不打断你，MEMO 不会自动切屏；点“立即预热第 1 个推荐”会真正执行预热。")
            .replace("root am start + HOME; label=", "已经启动并回到桌面完成预热：")
            .replace("no Top-3 recommendation yet; run smart optimization first", "还没有 Top-3 推荐，请先运行“智能优化：刷新当前推荐”。")
            .replace("no launchable activity", "这个应用没有可启动入口。")
            .replace("stopped eBPF collector, MAPLE process, and MEMO background work on this device", "已经停止 eBPF 采集器、MAPLE 进程和 MEMO 后台任务。")
            .replace("free usage session is recording; leave MEMO, use any apps, then return and finish analysis", "自由体验正在记录。可以离开 MEMO 打开任意应用，回来点“结束体验并分析”。")
            .replace("free usage session is already recording; return to MEMO and finish analysis", "自由体验已经在记录。回来点“结束体验并分析”即可。")
            .replace("analyzed free usage session with ", "已经分析自由体验：")
            .replace(" observed app segments across ", " 个连续 app 片段，覆盖 ")
            .replace("Strict 100-use real analysis failed", "100 次真实使用分析失败")
            .replace("analyzed ", "已经分析 ")
            .replace(" real app openings across ", " 次真实 app 打开，覆盖 ")
            .replace(" apps; report=", " 个应用；报告位置：")
            .replace("ran ", "已经运行 ")
            .replace(" MAPLE ablations on the latest Android-side real eBPF scenario", " 组 MAPLE 消融，使用的是最新一次 Android 端真实 eBPF scenario。")
            .replace("latest real MAPLE scenario is missing; run a real eBPF experiment first", "还没有最新真实 MAPLE scenario，请先运行一次“智能优化：刷新当前推荐”或真实场景采集。")
            .replace("MEMO off/on real app pressure A/B finished; avg_pressure_score_improvement_pct=", "手机压力 A/B 实验完成；综合压力改善=")
            .replace("full local one-tap run completed ", "滚动场景完整评估已经完成 ")
            .replace(" MAPLE ablations on device in ", " 组设备端 MAPLE 消融，用时 ")
            .replace("full local one-tap ablation failed: ", "滚动场景完整评估里的消融步骤失败：")
            .replace("MAPLE result published after asynchronous inference; completion status=completed", "MAPLE 在后台完成推理，Top-3 推荐和调度动作已经发布。")
            .replace("MAPLE prediction ran asynchronously; completion latency=", "MAPLE 在后台异步推理，完成耗时=")
            .replace("; slow runs still finish and still drive actions", "；即使耗时较长，也会继续完成预测并驱动系统动作。")
            .replace("latency=completed", "流水线已完成")
            .replace("latency=maple_timeout", "MAPLE 触发安全超时")
            .replace("foreground_observed=", "前台可见处理=")
            .replace("foreground=", "前台可见处理=")
            .replace("MAPLE=", "MAPLE=")
            .replace("total=", "总耗时=")
    }

    private fun observationCards(state: LastMemoState): List<ObservationItem> {
        val report = jsonOrNull(state.usageReportJson)
        if (report != null) return usageObservationCards(report, state)
        return state.evidenceLines
            .filterNot { it.startsWith("process ") }
            .mapNotNull { observationFromEvidenceLine(it) }
            .take(6)
    }

    private fun usageObservationCards(report: JSONObject, state: LastMemoState): List<ObservationItem> {
        val launch = report.optJSONObject("launch_metrics")
        val ebpf = report.optJSONObject("ebpf")
        val delta = report.optJSONObject("system_delta")
        val observed = report.optInt("interaction_count_observed")
        val requested = report.optInt("interaction_count_requested")
        val compressedSignals = compressedSignalCount(report)
        val recommendations = if (state.recommendations.isNotEmpty()) {
            state.recommendations.take(3).joinToString(" / ") { it.label }
        } else {
            report.optJSONArray("recommendations").takeObjects(3).joinToString(" / ") { it.optString("label") }
        }.ifBlank { "暂无推荐" }
        val reclaim = (delta?.optLong("pgscan_direct_delta") ?: 0L) + (delta?.optLong("pgscan_kswapd_delta") ?: 0L)
        return listOf(
            ObservationItem(
                title = "真实应用使用",
                value = "$observed/$requested 次打开，覆盖 ${report.optInt("unique_apps_opened")} 个应用",
                detail = "这是这轮真实使用实验的完整度和场景覆盖，不是手写序列。",
                color = Color.rgb(37, 99, 235),
            ),
            ObservationItem(
                title = "系统事件证据",
                value = "${ebpf?.optInt("parsed_events") ?: 0} 条 raw eBPF，压缩成 $compressedSignals 条模型信号",
                detail = "原始记录用于审计；MAPLE 读取压缩后的事件数量和发生速率，避免重复信息刷屏。",
                color = Color.rgb(37, 99, 235),
            ),
            ObservationItem(
                title = "启动体验",
                value = "平均 ${formatNumberMs(launch?.opt("avg_total_time_ms"))}，P50 ${formatNumberMs(launch?.opt("p50_total_time_ms"))}",
                detail = "这两个数越小越好，表示 app 打开得更快。",
                color = Color.rgb(22, 101, 52),
            ),
            ObservationItem(
                title = "内存和回收",
                value = "可用内存 ${formatDeltaKb(delta?.opt("mem_available_delta_kb"))}，回收 $reclaim 次",
                detail = "可用内存负数表示减少；回收次数越高，说明系统越忙着找内存。",
                color = Color.rgb(180, 83, 9),
            ),
            ObservationItem(
                title = "网络活动",
                value = "UDP 收 ${formatLong(delta?.opt("udp_in_delta"))} / 发 ${formatLong(delta?.opt("udp_out_delta"))}",
                detail = "这是场景描述指标，说明当前使用窗口里网络是否活跃，不直接代表好坏。",
                color = Color.rgb(37, 99, 235),
            ),
            ObservationItem(
                title = "MAPLE 推荐",
                value = recommendations,
                detail = "这些是已经映射到手机上真实可打开应用的 Top-3，不是进程名或系统服务。",
                color = Color.rgb(22, 101, 52),
            ),
        )
    }

    private fun observationFromEvidenceLine(line: String): ObservationItem? {
        Regex("""(\d+)\s+device-side eBPF records""").find(line)?.let { match ->
            return ObservationItem(
                title = "采集规模",
                value = "${match.groupValues[1]} 条系统事件",
                detail = "这些记录来自当前手机使用窗口里的 eBPF 采集。",
                color = Color.rgb(37, 99, 235),
            )
        }
        Regex("""event_type\s+(MEMO_\w+):\s+count=(\d+)""").find(line)?.let { match ->
            return ObservationItem(
                title = "内核事件",
                value = "${friendlyEventType(match.groupValues[1])}：${match.groupValues[2]} 条",
                detail = "同类事件会被整理为数量，供 MAPLE 判断系统状态。",
                color = Color.rgb(37, 99, 235),
            )
        }
        Regex("""MAPLE evidence/resource category\s+(.+):\s+score=(\d+)""").find(line)?.let { match ->
            return ObservationItem(
                title = "资源信号",
                value = "${friendlyCategory(match.groupValues[1])}：${match.groupValues[2]} 分",
                detail = "分数越高，说明这个资源方向在当前窗口越明显。",
                color = Color.rgb(22, 101, 52),
            )
        }
        if ("failed" in line || "capture produced zero" in line) {
            return ObservationItem(
                title = "采集状态",
                value = "这次采集没有成功",
                detail = friendlyEvidence(line),
                color = Color.rgb(185, 28, 28),
            )
        }
        return null
    }

    private fun compressedSignalCount(report: JSONObject): Int {
        val windows = report.optJSONArray("timeline_windows") ?: return 0
        var count = 0
        for (i in 0 until windows.length()) {
            count += windows.optJSONObject(i)?.optJSONArray("compressed_ebpf")?.length() ?: 0
        }
        return count
    }

    private fun friendlyEventType(type: String): String {
        return when (type) {
            "MEMO_BINDER" -> "系统服务调用"
            "MEMO_OPENAT" -> "文件资源访问"
            "MEMO_SENDTO" -> "网络发送"
            "MEMO_RECVFROM" -> "网络接收"
            "MEMO_SCHED" -> "线程调度"
            "MEMO_PROCESS_FORK" -> "进程创建"
            "MEMO_PROCESS_EXEC" -> "进程启动"
            "MEMO_PROCESS_EXIT" -> "进程退出"
            "MEMO_MEMORY", "MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "MEMO_KSWAPD_WAKE" -> "内存回收"
            "MEMO_INPUT" -> "触摸/按键输入"
            "MEMO_STATUS" -> "采集状态"
            else -> type.removePrefix("MEMO_").lowercase(Locale.US).replace('_', ' ')
        }
    }

    private fun joinAblationArray(array: JSONArray?): String {
        if (array == null || array.length() == 0) return "无"
        return (0 until array.length())
            .mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
            .joinToString("、") { friendlyAblationConfig(it) }
    }

    private fun friendlyAblationConfig(value: String): String {
        return when (value) {
            "full_real_ebpf" -> "完整真实 eBPF"
            "no_network" -> "去掉网络证据"
            "no_camera_media" -> "去掉相机/媒体证据"
            "no_display_ui" -> "去掉显示/UI 证据"
            "no_binder_service" -> "去掉系统服务证据"
            "no_memory" -> "去掉内存证据"
            "counters_only" -> "只保留计数"
            "app_sequence_baseline" -> "只保留应用序列"
            else -> value.replace('_', ' ')
        }
    }

    private fun readableAppList(value: String): String {
        return value
            .replace("com.android.chrome", "Chrome")
            .replace("com.google.android.apps.messaging", "Messages")
            .replace("com.android.settings", "Settings")
            .replace("top_apps", "Top-3 应用")
            .replace(",", "、")
    }

    private fun recommendationReason(app: RecommendationState): String {
        val lower = app.category.lowercase(Locale.US)
        return when {
            "network" in lower || "udp" in lower ->
                "刚才网络收发比较活跃，MEMO 认为这个应用可能很快会被用到。"
            "communication" in lower || "binder" in lower || "service ipc" in lower ->
                "系统服务和通信调用变多，优先保留这类可打开应用。"
            "camera" in lower || "photo" in lower ->
                "相机、图片或分享相关信号增强，提前把它放进候选。"
            "media" in lower || "codec" in lower || "video" in lower ->
                "音视频和显示链路活跃，适合提前准备媒体类应用。"
            "payment" in lower || "wallet" in lower || "security" in lower ->
                "支付或安全相关服务出现，保留可快速进入的候选应用。"
            "display" in lower || "render" in lower ->
                "界面刷新和交互变多，MEMO 会控制预热强度避免卡顿。"
            "runtime" in lower || "process" in lower ->
                "应用切换和进程活动明显，MEMO 保留一个稳定的系统入口。"
            else ->
                "来自 MAPLE 对当前 eBPF 证据的端侧推理结果。"
        }
    }

    private fun jsonOrNull(raw: String): JSONObject? {
        return try {
            if (raw.isBlank()) null else JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONArray?.takeObjects(limit: Int): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }.take(limit)
    }

    private fun joinArray(array: JSONArray?): String {
        if (array == null || array.length() == 0) return "无"
        return (0 until array.length())
            .mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
            .joinToString()
    }

    private fun formatNumberMs(value: Any?): String {
        return when (value) {
            is Number -> PipelineLatency.formatMs(value.toLong())
            else -> "暂无"
        }
    }

    private fun formatLong(value: Any?): String {
        return when (value) {
            is Number -> value.toLong().toString()
            else -> "暂无"
        }
    }

    private fun formatDeltaKb(value: Any?): String {
        return when (value) {
            is Number -> "${value.toLong()} kB"
            else -> "暂无"
        }
    }

    private fun formatPct(value: Any?): String {
        return when (value) {
            is Number -> String.format(Locale.US, "%+.1f%%", value.toDouble())
            else -> "暂无"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private data class ObservationItem(
        val title: String,
        val value: String,
        val detail: String,
        val color: Int,
    )

    companion object {
        private const val EXTRA_MEMO_ACTION = "memo_action"
        private val SERVICE_ACTIONS = setOf(
            EBPFCollectorService.ACTION_RUN_ONCE,
            EBPFCollectorService.ACTION_STOP,
            EBPFCollectorService.ACTION_CHECK_SETUP,
            EBPFCollectorService.ACTION_WARM_TOP_APP,
            EBPFCollectorService.ACTION_FULL_LOCAL_EVALUATION,
            EBPFCollectorService.ACTION_FREE_USAGE_START,
            EBPFCollectorService.ACTION_FREE_USAGE_FINISH,
            EBPFCollectorService.ACTION_RECORD_CURRENT_USAGE,
            EBPFCollectorService.ACTION_EXPERIMENT_COMMUNICATION,
            EBPFCollectorService.ACTION_EXPERIMENT_CAMERA,
            EBPFCollectorService.ACTION_EXPERIMENT_MEDIA,
            EBPFCollectorService.ACTION_EXPERIMENT_PAYMENT,
            EBPFCollectorService.ACTION_EXPERIMENT_SCROLL,
            EBPFCollectorService.ACTION_REAL_ABLATION_LATEST,
            EBPFCollectorService.ACTION_PRESSURE_EXPERIMENT,
            EBPFCollectorService.ACTION_USAGE_100_ANALYSIS,
        )
    }
}
