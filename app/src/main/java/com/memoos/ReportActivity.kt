package com.memoos

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.memoos.perf.PipelineLatency
import com.memoos.store.MemoStore
import org.json.JSONArray
import org.json.JSONObject

class ReportActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        scroll.addView(root)
        render()
        return scroll
    }

    private fun render() {
        val state = MemoStore(this).load()
        val section = intent?.getStringExtra(EXTRA_SECTION) ?: SECTION_USAGE
        val usage = jsonOrNull(state.usageReportJson)
        val ablation = jsonOrNull(state.ablationReportJson)
        val pressure = jsonOrNull(state.pressureReportJson)
        root.removeAllViews()
        root.addView(title("MEMO 报告"))
        root.addView(body("这里展示手机本地生成的真实使用分析和消融实验结果。页面只做格式化，不直接展示 JSON 原文。"))
        root.addView(backButton())
        when (section) {
            SECTION_ABLATION -> {
                renderAblationReport(ablation)
                renderUsageReport(usage)
                renderPressureReport(pressure)
            }
            SECTION_PRESSURE -> {
                renderPressureReport(pressure)
                renderUsageReport(usage)
                renderAblationReport(ablation)
            }
            else -> {
                renderUsageReport(usage)
                renderAblationReport(ablation)
                renderPressureReport(pressure)
            }
        }
    }

    private fun renderUsageReport(report: JSONObject?) {
        val panel = verticalPanel()
        val isFreeSession = report?.optString("session_mode") == "free_usage" ||
            report?.optString("experiment_type") == "free_usage_session"
        panel.addView(label(if (isFreeSession) "实时窗口使用分析" else "真实使用分析"))
        if (report == null) {
            panel.addView(body("还没有报告。请先回首页开启实时模式，或运行“9 分钟实时 Top-3 变化实验”。"))
            root.addView(panel)
            return
        }
        val launch = report.optJSONObject("launch_metrics")
        val ebpf = report.optJSONObject("ebpf")
        val delta = report.optJSONObject("system_delta")
        panel.addView(smallCaption("实验设计"))
        if (isFreeSession) {
            panel.addView(body("实时模式在后台记录前台应用变化并采集 eBPF；手机本地把最近窗口整理成应用序列、系统证据、MAPLE 推荐和调度动作。"))
        } else {
            panel.addView(body("手机本地运行真实应用使用实验，同步采集 eBPF。报告把应用使用序列、系统事件、MAPLE 推荐和调度动作放在一起看。"))
        }
        panel.addView(kv("实验类型", friendlyExperimentType(report.optString("experiment_type"))))
        panel.addView(kv(if (isFreeSession) "连续 app 片段" else "真实 app 打开", "${report.optInt("interaction_count_observed")}/${report.optInt("interaction_count_requested")} 次"))
        panel.addView(kv("覆盖应用", "${report.optInt("unique_apps_opened")} 个"))
        panel.addView(kv("报告文件", report.optJSONObject("report_files")?.optString("usage_report") ?: "未导出"))
        panel.addView(kv("raw eBPF 文件", report.optString("raw_trace_path")))
        panel.addView(kv("eBPF 事件", "${ebpf?.optInt("parsed_events") ?: 0} 条"))
        if (isFreeSession) {
            panel.addView(kv("平均停留时长", formatMs(launch?.opt("avg_dwell_time_ms"))))
            panel.addView(kv("P50 停留时长", formatMs(launch?.opt("p50_dwell_time_ms"))))
            panel.addView(body("怎么看：连续 app 片段表示刚才实际切换过的应用段落；停留时长用来理解切换节奏，不是越大越好；eBPF 事件代表证据规模，模型输入会再压缩。"))
        } else {
            panel.addView(kv("平均启动耗时", formatMs(launch?.opt("avg_total_time_ms"))))
            panel.addView(kv("P50 启动耗时", formatMs(launch?.opt("p50_total_time_ms"))))
            panel.addView(kv("平均等待耗时", formatMs(launch?.opt("avg_wait_time_ms"))))
            panel.addView(body("怎么看：真实 app 打开越接近请求次数越完整；启动和等待耗时越小越好；eBPF 事件代表证据规模，不是越大越好，模型输入会再压缩。"))
        }
        panel.addView(kv("内存变化", formatKb(delta?.opt("mem_available_delta_kb"))))
        panel.addView(kv("UDP 收发变化", "in=${formatLong(delta?.opt("udp_in_delta"))}, out=${formatLong(delta?.opt("udp_out_delta"))}"))
        panel.addView(kv("回收压力变化", "直接回收 ${formatLong(delta?.opt("pgscan_direct_delta"))}，后台回收 ${formatLong(delta?.opt("pgscan_kswapd_delta"))}"))
        panel.addView(body("怎么看：可用内存变化为负代表可用内存减少；内存回收压力通常越小越好；UDP 收发是场景活跃度，不直接代表好坏。"))
        addList(panel, "打开最多的应用", report.optJSONArray("app_usage_top"), 8) { obj ->
            "${obj.optString("label")}：${obj.optInt("count")} 次 (${obj.optString("package_name")})"
        }
        addObjectEntries(panel, "eBPF 事件类型", ebpf?.optJSONObject("event_counts"), 8)
        addList(panel, "MAPLE 推荐应用", report.optJSONArray("recommendations"), 3) { obj ->
            "${obj.optString("label")}：${friendlyCategory(obj.optString("category"))}，置信度 ${"%.2f".format(obj.optDouble("confidence"))}"
        }
        addList(panel, "已执行/已准备的调度动作", report.optJSONArray("actions"), 10) { obj ->
            "${friendlyActionName(obj.optString("name"))} / ${friendlyStatus(obj.optString("status"))}：${friendlyActionDetail(obj.optString("detail"))}"
        }
        root.addView(panel)
    }

    private fun renderAblationReport(report: JSONObject?) {
        val panel = verticalPanel()
        panel.addView(label("消融实验报告"))
        if (report == null) {
            panel.addView(body("还没有消融报告。请先运行“最新真实证据消融”，或先完成一次实时 Top-3 变化实验生成真实 scenario。"))
            root.addView(panel)
            return
        }
        val summary = report.optJSONObject("summary")
        panel.addView(smallCaption("实验设计"))
        panel.addView(body("固定同一次真实 100-use scenario，逐项删掉网络、相机/媒体、显示、Binder、内存等证据。若预测或调度明显改变，说明被删掉的证据对系统判断重要。"))
        panel.addView(kv("参照配置", "完整真实 eBPF"))
        panel.addView(kv("配置数量", "${summary?.optInt("config_count") ?: 0} 组"))
        panel.addView(kv("MAPLE 可用配置", "${summary?.optInt("maple_available_count") ?: 0} 组"))
        panel.addView(kv("预测 ID 改变", joinAblationArray(summary?.optJSONArray("changed_predicted_app_configs"))))
        panel.addView(kv("Top-1 改变", joinAblationArray(summary?.optJSONArray("changed_top1_app_configs"))))
        panel.addView(kv("调度域改变", joinAblationArray(summary?.optJSONArray("changed_predicted_scheduler_domain_configs"))))
        panel.addView(kv("平均端到端耗时", formatMs(summary?.opt("avg_end_to_end_ms"))))
        panel.addView(body("怎么看：改变 Top-1 或预测 ID 说明推荐受影响；改变调度域说明系统动作受影响；耗时越小越好，但这里主要看证据重要性。"))
        addList(panel, "逐配置结果", report.optJSONArray("results"), 12) { obj ->
            val cmp = obj.optJSONObject("comparison_to_full")
            "${friendlyAblationConfig(obj.optString("config_id"))}：Top-1 ${sameLabel(cmp?.optBoolean("top1_same"))}，Top-3 相似度 ${"%.2f".format(cmp?.optDouble("top3_jaccard") ?: 0.0)}，调度相似度 ${"%.2f".format(cmp?.optDouble("action_domain_jaccard") ?: 0.0)}"
        }
        root.addView(panel)
    }

    private fun renderPressureReport(report: JSONObject?) {
        val panel = verticalPanel()
        panel.addView(label("手机性能 A/B 报告"))
        if (report == null) {
            panel.addView(body("还没有性能报告。请先回首页运行“系统调度性能实验”。"))
            root.addView(panel)
            return
        }
        val summary = report.optJSONObject("summary")
        panel.addView(smallCaption("实验设计"))
        panel.addView(body("同一批真实 app workload 分别在 MEMO 关闭和开启后执行，对比手机整体压力。这个实验衡量继续使用其他 app 时，MEMO 是否让系统更轻松。"))
        panel.addView(kv("实验类型", friendlyExperimentType(report.optString("experiment_type"))))
        panel.addView(kv("对比 workload", "${summary?.optInt("workloads_compared") ?: 0} 组"))
        panel.addView(kv("综合压力改善", formatPct(summary?.opt("avg_pressure_score_improvement_pct"))))
        panel.addView(kv("启动耗时改善", formatPct(summary?.opt("avg_launch_total_time_improvement_pct"))))
        panel.addView(kv("等待耗时改善", formatPct(summary?.opt("avg_wait_time_improvement_pct"))))
        panel.addView(kv("CPU busy 改善", formatPct(summary?.opt("avg_cpu_busy_improvement_pct"))))
        panel.addView(kv("iowait 改善", formatPct(summary?.opt("avg_iowait_improvement_pct"))))
        panel.addView(kv("内存占用改善", formatPct(summary?.opt("avg_memory_drop_improvement_pct"))))
        panel.addView(kv("内存回收改善", formatPct(summary?.opt("avg_reclaim_improvement_pct"))))
        panel.addView(body(summary?.optString("claim_scope") ?: "该实验只比较同一台手机、同一批真实 app workload 的 A/B 结果。"))
        panel.addView(body("怎么看：这里正数表示 MEMO-on 更好，负数表示该指标没有改善。综合压力越低越好；启动、等待、CPU busy、iowait 和回收压力也都是越低越好。"))
        addList(panel, "逐 workload 对比", report.optJSONArray("comparisons"), 12) { obj ->
            "${friendlyCondition(obj.optString("condition"))} / ${obj.optString("target_label")}：压力 ${formatPct(obj.opt("pressure_score_improvement_pct"))}，启动 ${formatPct(obj.opt("launch_total_time_improvement_pct"))}，CPU ${formatPct(obj.opt("cpu_busy_improvement_pct"))}"
        }
        root.addView(panel)
    }

    private fun addList(panel: LinearLayout, heading: String, array: JSONArray?, limit: Int, render: (JSONObject) -> String) {
        if (array == null || array.length() == 0) return
        panel.addView(smallCaption(heading))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.take(limit).forEach { obj ->
            panel.addView(text(render(obj), 13f, Color.rgb(51, 65, 85), bottom = 5))
        }
    }

    private fun addObjectEntries(panel: LinearLayout, heading: String, obj: JSONObject?, limit: Int) {
        if (obj == null || obj.length() == 0) return
        panel.addView(smallCaption(heading))
        obj.keys().asSequence()
            .map { it to obj.optInt(it) }
            .sortedByDescending { it.second }
            .take(limit)
            .forEach { (key, value) -> panel.addView(text("${friendlyEventType(key)}：$value 条", 13f, Color.rgb(51, 65, 85), bottom = 5)) }
    }

    private fun jsonOrNull(raw: String): JSONObject? {
        return try {
            if (raw.isBlank()) null else JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun joinArray(array: JSONArray?): String {
        if (array == null || array.length() == 0) return "无"
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }.joinToString()
    }

    private fun joinAblationArray(array: JSONArray?): String {
        if (array == null || array.length() == 0) return "无"
        return (0 until array.length())
            .mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
            .joinToString("、") { friendlyAblationConfig(it) }
    }

    private fun friendlyExperimentType(value: String): String {
        return when (value) {
            "free_usage_session" -> "实时窗口使用"
            "real_usage_100_app_rotation" -> "100 次真实应用使用"
            "realtime_top3_shift_9min" -> "9 分钟实时 Top-3 变化实验"
            "user_app_pressure_ab", "system_scheduler_performance" -> "系统调度性能实验"
            else -> value.replace('_', ' ')
        }
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
            else -> type.removePrefix("MEMO_").lowercase(java.util.Locale.US).replace('_', ' ')
        }
    }

    private fun friendlyCategory(category: String): String {
        val lower = category.lowercase(java.util.Locale.US)
        return when {
            "network" in lower -> "网络通信"
            "communication" in lower || "binder" in lower -> "通信/系统服务"
            "camera" in lower -> "相机/图片"
            "media" in lower -> "音视频"
            "display" in lower -> "界面显示"
            "memory" in lower -> "内存状态"
            else -> category
        }
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

    private fun friendlyActionName(value: String): String {
        return when (value) {
            "widget_update" -> "更新推荐展示"
            "latency_policy" -> "异步推理策略"
            "memory_policy" -> "内存策略"
            "network_candidate_priority" -> "网络应用优先"
            "network_stats_refresh" -> "网络状态刷新"
            "camera_media_candidate" -> "相机/媒体候选"
            "display_ui_policy" -> "界面流畅策略"
            "binder_service_policy" -> "系统服务策略"
            "warm_launch_policy" -> "预热策略"
            else -> value.replace('_', ' ')
        }
    }

    private fun friendlyStatus(value: String): String {
        return when (value) {
            "ok" -> "已执行"
            "planned" -> "已准备"
            "skipped" -> "已跳过"
            "failed" -> "失败"
            "blocked" -> "需要处理"
            else -> value.replace('_', ' ')
        }
    }

    private fun friendlyActionDetail(value: String): String {
        return value
            .replace("published 3 real app recommendations", "已经发布 3 个真实应用推荐")
            .replace("MAPLE prediction ran asynchronously", "MAPLE 已在后台异步完成推理")
            .replace("normal memory; lightweight warm launch remains enabled", "内存正常，保留轻量预热")
            .replace("UDP sendto/recvfrom evidence prioritizes network-capable apps", "检测到网络收发，优先保留网络类应用")
            .replace("refreshed network stats before recommendation", "推荐前刷新了网络状态")
            .replace("Binder/system-service evidence kept as MAPLE scheduling context", "系统服务证据已进入 MAPLE 上下文")
            .replace("non-intrusive background mode; do not switch visible apps while the user continues using the phone", "后台模式不切换屏幕，避免打断当前操作")
            .replace('_', ' ')
    }

    private fun friendlyCondition(value: String): String {
        return when (value) {
            "crowded_cached_apps" -> "缓存应用较多"
            "normal_rotation" -> "普通应用轮换"
            "media_network" -> "媒体/网络场景"
            else -> value.replace('_', ' ')
        }
    }

    private fun sameLabel(value: Boolean?): String {
        return if (value == true) "未改变" else "已改变"
    }

    private fun formatMs(value: Any?): String {
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

    private fun formatKb(value: Any?): String {
        return when (value) {
            is Number -> "${value.toLong()} kB"
            else -> "暂无"
        }
    }

    private fun formatPct(value: Any?): String {
        return when (value) {
            is Number -> String.format(java.util.Locale.US, "%+.1f%%", value.toDouble())
            else -> "暂无"
        }
    }

    private fun backButton(): View {
        return Button(this).apply {
            text = "返回"
            isAllCaps = false
            setTextColor(Color.rgb(15, 23, 42))
            background = rounded(Color.rgb(226, 232, 240), dp(8))
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(match(), dp(44)).apply { bottomMargin = dp(14) }
        }
    }

    private fun verticalPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(14) }
        }
    }

    private fun title(value: String) = text(value, 28f, Color.rgb(15, 23, 42), bold = true, bottom = 6)
    private fun label(value: String) = text(value, 18f, Color.rgb(15, 23, 42), bold = true, bottom = 8)
    private fun body(value: String) = text(value, 14f, Color.rgb(51, 65, 85), bottom = 10)
    private fun smallCaption(value: String) = text(value, 12f, Color.rgb(100, 116, 139), top = 8, bottom = 4)
    private fun kv(key: String, value: String): View = text("$key: $value", 13f, Color.rgb(51, 65, 85), bottom = 5)

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0, bottom: Int = 3): TextView {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    companion object {
        const val EXTRA_SECTION = "memo_report_section"
        private const val SECTION_USAGE = "usage"
        private const val SECTION_ABLATION = "ablation"
        private const val SECTION_PRESSURE = "pressure"
    }
}
