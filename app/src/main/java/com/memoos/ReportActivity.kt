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
        root.addView(body("这里展示手机本地生成的真实使用分析和消融实验结果。页面只做格式化，不把 JSON 原文直接丢给用户。"))
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
        panel.addView(label("100 次真实使用分析"))
        if (report == null) {
            panel.addView(body("还没有报告。请先回首页运行“100 次真实使用分析”。"))
            root.addView(panel)
            return
        }
        val launch = report.optJSONObject("launch_metrics")
        val ebpf = report.optJSONObject("ebpf")
        val delta = report.optJSONObject("system_delta")
        panel.addView(kv("实验类型", report.optString("experiment_type")))
        panel.addView(kv("真实 app 打开", "${report.optInt("interaction_count_observed")}/${report.optInt("interaction_count_requested")} 次"))
        panel.addView(kv("覆盖应用", "${report.optInt("unique_apps_opened")} 个"))
        panel.addView(kv("报告文件", report.optJSONObject("report_files")?.optString("usage_report") ?: "未导出"))
        panel.addView(kv("raw eBPF 文件", report.optString("raw_trace_path")))
        panel.addView(kv("eBPF 事件", "${ebpf?.optInt("parsed_events") ?: 0} 条"))
        panel.addView(kv("平均启动耗时", formatMs(launch?.opt("avg_total_time_ms"))))
        panel.addView(kv("P50 启动耗时", formatMs(launch?.opt("p50_total_time_ms"))))
        panel.addView(kv("平均等待耗时", formatMs(launch?.opt("avg_wait_time_ms"))))
        panel.addView(kv("内存变化", formatKb(delta?.opt("mem_available_delta_kb"))))
        panel.addView(kv("UDP 收发变化", "in=${formatLong(delta?.opt("udp_in_delta"))}, out=${formatLong(delta?.opt("udp_out_delta"))}"))
        panel.addView(kv("回收压力变化", "direct=${formatLong(delta?.opt("pgscan_direct_delta"))}, kswapd=${formatLong(delta?.opt("pgscan_kswapd_delta"))}"))
        addList(panel, "打开最多的应用", report.optJSONArray("app_usage_top"), 8) { obj ->
            "${obj.optString("label")}：${obj.optInt("count")} 次 (${obj.optString("package_name")})"
        }
        addObjectEntries(panel, "eBPF 事件类型", ebpf?.optJSONObject("event_counts"), 8)
        addList(panel, "MAPLE 推荐应用", report.optJSONArray("recommendations"), 3) { obj ->
            "${obj.optString("label")}：${obj.optString("category")} confidence=${"%.2f".format(obj.optDouble("confidence"))}"
        }
        addList(panel, "已执行/已准备的调度动作", report.optJSONArray("actions"), 10) { obj ->
            "${obj.optString("name")} / ${obj.optString("status")}：${obj.optString("detail")}"
        }
        root.addView(panel)
    }

    private fun renderAblationReport(report: JSONObject?) {
        val panel = verticalPanel()
        panel.addView(label("消融实验报告"))
        if (report == null) {
            panel.addView(body("还没有消融报告。请先运行“最新真实证据消融”或“100 次真实使用分析”。"))
            root.addView(panel)
            return
        }
        val summary = report.optJSONObject("summary")
        panel.addView(kv("参照配置", "full_real_ebpf"))
        panel.addView(kv("配置数量", "${summary?.optInt("config_count") ?: 0} 组"))
        panel.addView(kv("MAPLE 可用配置", "${summary?.optInt("maple_available_count") ?: 0} 组"))
        panel.addView(kv("预测 ID 改变", joinArray(summary?.optJSONArray("changed_predicted_app_configs"))))
        panel.addView(kv("Top-1 改变", joinArray(summary?.optJSONArray("changed_top1_app_configs"))))
        panel.addView(kv("调度域改变", joinArray(summary?.optJSONArray("changed_predicted_scheduler_domain_configs"))))
        panel.addView(kv("平均端到端耗时", formatMs(summary?.opt("avg_end_to_end_ms"))))
        addList(panel, "逐配置结果", report.optJSONArray("results"), 12) { obj ->
            val cmp = obj.optJSONObject("comparison_to_full")
            "${obj.optString("config_id")}：Top1相同=${cmp?.optBoolean("top1_same")}，Top3 Jaccard=${"%.2f".format(cmp?.optDouble("top3_jaccard") ?: 0.0)}，调度 Jaccard=${"%.2f".format(cmp?.optDouble("action_domain_jaccard") ?: 0.0)}"
        }
        root.addView(panel)
    }

    private fun renderPressureReport(report: JSONObject?) {
        val panel = verticalPanel()
        panel.addView(label("手机性能 A/B 报告"))
        if (report == null) {
            panel.addView(body("还没有性能报告。请先回首页运行“手机压力 A/B 实验”。"))
            root.addView(panel)
            return
        }
        val summary = report.optJSONObject("summary")
        panel.addView(kv("实验类型", report.optString("experiment_type")))
        panel.addView(kv("对比 workload", "${summary?.optInt("workloads_compared") ?: 0} 组"))
        panel.addView(kv("综合压力改善", formatPct(summary?.opt("avg_pressure_score_improvement_pct"))))
        panel.addView(kv("启动耗时改善", formatPct(summary?.opt("avg_launch_total_time_improvement_pct"))))
        panel.addView(kv("等待耗时改善", formatPct(summary?.opt("avg_wait_time_improvement_pct"))))
        panel.addView(kv("CPU busy 改善", formatPct(summary?.opt("avg_cpu_busy_improvement_pct"))))
        panel.addView(kv("iowait 改善", formatPct(summary?.opt("avg_iowait_improvement_pct"))))
        panel.addView(kv("内存占用改善", formatPct(summary?.opt("avg_memory_drop_improvement_pct"))))
        panel.addView(kv("内存回收改善", formatPct(summary?.opt("avg_reclaim_improvement_pct"))))
        panel.addView(body(summary?.optString("claim_scope") ?: "该实验只比较同一台手机、同一批真实 app workload 的 A/B 结果。"))
        addList(panel, "逐 workload 对比", report.optJSONArray("comparisons"), 12) { obj ->
            "${obj.optString("condition")} / ${obj.optString("target_label")}：压力=${formatPct(obj.opt("pressure_score_improvement_pct"))}，启动=${formatPct(obj.opt("launch_total_time_improvement_pct"))}，CPU=${formatPct(obj.opt("cpu_busy_improvement_pct"))}"
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
            .forEach { (key, value) -> panel.addView(text("$key：$value 条", 13f, Color.rgb(51, 65, 85), bottom = 5)) }
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
