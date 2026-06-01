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
import com.memoos.device.RootShell
import com.memoos.ebpf.EBPFEvent
import com.memoos.ebpf.EBPFTraceParser
import com.memoos.store.MemoStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvidenceActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RootShell.configureBridge(this)
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
        root.removeAllViews()
        root.addView(title("eBPF 证据详情"))
        root.addView(body("这里展示的是 MEMO 在手机本地采到的内核事件。为了可读性，原始记录被整理成事件类型、进程、资源类别和关键对象。"))
        root.addView(backButton())

        val summary = verticalPanel()
        summary.addView(label("本次采集"))
        summary.addView(kv("采集文件", state.rawTracePath.ifBlank { "还没有记录到 raw trace 路径" }))
        summary.addView(kv("采集开始时间", captureStartedAt(state.rawTracePath)))
        summary.addView(kv("摘要条目", "${state.evidenceLines.size} 条"))
        summary.addView(kv("MAPLE 状态", if (state.maple.available) "已经完成推理" else "暂未完成"))
        root.addView(summary)

        val records = readRawRecords(state.rawTracePath)
        val events = records.map { it.event }
        val overview = verticalPanel()
        overview.addView(label("事件概览"))
        if (events.isEmpty()) {
            overview.addView(body("还没有可读取的 raw eBPF 记录。请先运行“智能优化：刷新当前推荐”，或者运行一次真实使用采集。"))
            state.evidenceLines.take(8).forEach { overview.addView(readableSummary(it)) }
        } else {
            events.groupingBy { it.eventType }.eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
                .forEach { (type, count) -> overview.addView(kv(eventName(type), "$count 条")) }
        }
        root.addView(overview)

        val feed = verticalPanel()
        val groupedRecords = aggregateRecords(records)
        feed.addView(label("真实收到的前 ${records.size} 条事件，合并为 ${groupedRecords.size} 组"))
        if (records.isEmpty()) {
            feed.addView(body("没有可格式化的真实事件记录。"))
        } else {
            feed.addView(body("下面每一组都来自 raw trace 文件的前 ${records.size} 条真实记录。相同事件类型、进程、资源对象和细节会合并显示，并保留首次/末次收到时间和重复次数。"))
            groupedRecords.forEach { record -> feed.addView(eventCard(record)) }
        }
        root.addView(feed)
    }

    private fun readRawRecords(path: String): List<CapturedRecord> {
        if (path.isBlank()) return emptyList()
        val quoted = path.replace("'", "'\"'\"'")
        val result = RootShell.run("sed -n '/^MEMO/p' '$quoted' 2>/dev/null | head -n $RAW_RECORD_LIMIT", timeoutMs = 8_000L)
        if (!result.ok || result.stdout.isBlank()) return emptyList()
        return result.stdout.lineSequence()
            .mapIndexedNotNull { index, line ->
                EBPFTraceParser.parseLine(line)?.let { event ->
                    CapturedRecord(index + 1, line.trimEnd(), event)
                }
            }
            .toList()
    }

    private fun aggregateRecords(records: List<CapturedRecord>): List<AggregatedRecord> {
        val groups = LinkedHashMap<String, MutableList<CapturedRecord>>()
        records.forEach { record ->
            groups.getOrPut(record.groupKey()) { mutableListOf() }.add(record)
        }
        return groups.values.map { group ->
            AggregatedRecord(
                firstIndex = group.first().index,
                lastIndex = group.last().index,
                occurrences = group.size,
                firstRawLine = group.first().rawLine,
                lastRawLine = group.last().rawLine,
                event = group.first().event,
                firstWallTimeMs = group.mapNotNull { it.event.wallTimeMs() }.minOrNull(),
                lastWallTimeMs = group.mapNotNull { it.event.wallTimeMs() }.maxOrNull(),
            )
        }
    }

    private fun CapturedRecord.groupKey(): String {
        val event = event
        val process = event.comm?.takeIf { it.isNotBlank() } ?: event.traceTask ?: "unknown"
        val target = event.path?.takeIf { it.isNotBlank() }
            ?: event.detail?.takeIf { it.isNotBlank() }
            ?: event.extra["raw_type"]
            ?: ""
        val operation = event.code?.toString()
            ?: event.extra["arg0"]?.takeIf { it.isNotBlank() }
            ?: ""
        return listOf(
            event.eventType,
            process,
            event.pid?.toString() ?: "",
            event.tid?.toString() ?: event.traceTid?.toString().orEmpty(),
            target,
            operation,
            event.evidenceCategory ?: "",
        ).joinToString("|")
    }

    private fun readableSummary(line: String): View {
        return TextView(this).apply {
            text = line
                .replace("device-side eBPF records in the observed Android window", "条系统级事件来自当前手机使用窗口")
                .replace("event_type", "内核事件")
                .replace("MAPLE evidence/resource category", "资源信号")
                .replace("count=", "数量=")
            textSize = 13f
            setTextColor(Color.rgb(51, 65, 85))
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(match(), wrap())
        }
    }

    private fun eventCard(record: AggregatedRecord): View {
        val event = record.event
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(248, 250, 252), dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) }
        }
        val range = if (record.firstIndex == record.lastIndex) {
            "第 ${record.firstIndex} 条"
        } else {
            "第 ${record.firstIndex}-${record.lastIndex} 条"
        }
        panel.addView(text("$range · ${eventName(event.eventType)} · 重复 ${record.occurrences} 次", 14f, Color.rgb(15, 23, 42), bold = true))
        panel.addView(text("收到时间: ${record.displayTime()}", 12f, Color.rgb(100, 116, 139)))
        val process = event.comm?.takeIf { it.isNotBlank() } ?: event.traceTask ?: "unknown"
        panel.addView(text("进程: $process  PID: ${event.pid ?: "-"}  TID: ${event.tid ?: event.traceTid ?: "-"}", 12f, Color.rgb(71, 85, 105)))
        panel.addView(text("资源类别: ${categoryName(event.evidenceCategory, event.eventType)}", 12f, Color.rgb(37, 99, 235)))
        event.path?.takeIf { it.isNotBlank() }?.let {
            panel.addView(text("对象: ${shorten(it)}", 12f, Color.rgb(71, 85, 105)))
        }
        event.detail?.takeIf { it.isNotBlank() && it != event.path }?.let {
            panel.addView(text("细节: ${shorten(it)}", 12f, Color.rgb(71, 85, 105)))
        }
        panel.addView(text("内核时间: ${eventTime(event)}", 11f, Color.rgb(100, 116, 139)))
        panel.addView(monoText("代表原始记录: ${shorten(record.firstRawLine, 180)}"))
        if (record.occurrences > 1 && record.lastRawLine != record.firstRawLine) {
            panel.addView(monoText("本组最后记录: ${shorten(record.lastRawLine, 180)}"))
        }
        return panel
    }

    private fun eventName(type: String): String {
        return when (type) {
            "MEMO_BINDER" -> "Binder/系统服务调用"
            "MEMO_OPENAT" -> "文件或资源访问"
            "MEMO_SENDTO" -> "网络发送"
            "MEMO_RECVFROM" -> "网络接收"
            "MEMO_SCHED" -> "调度/线程切换"
            "MEMO_PROCESS_FORK" -> "进程创建"
            "MEMO_PROCESS_EXEC" -> "进程启动"
            "MEMO_PROCESS_EXIT" -> "进程退出"
            "MEMO_INPUT" -> "输入事件"
            "MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "MEMO_KSWAPD_WAKE", "MEMO_MEMORY" -> "内存回收/压力"
            "MEMO_STATUS" -> "采集器状态"
            else -> type.removePrefix("MEMO_").lowercase(Locale.US).replace('_', ' ')
        }
    }

    private fun categoryName(category: String?, eventType: String): String {
        if (category.isNullOrBlank()) {
            return when (eventType) {
                "MEMO_SENDTO", "MEMO_RECVFROM" -> "网络通信"
                "MEMO_BINDER" -> "系统服务"
                "MEMO_SCHED" -> "调度状态"
                "MEMO_PROCESS_FORK", "MEMO_PROCESS_EXEC", "MEMO_PROCESS_EXIT" -> "应用进程"
                else -> "系统事件"
            }
        }
        return when (category) {
            "java_framework_or_classpath" -> "Java 框架/Classpath"
            "native_library" -> "Native 库"
            "android_property_area" -> "Android 属性区"
            "procfs_process_state" -> "进程状态"
            "sysfs_kernel_state" -> "内核状态"
            "apex_runtime_asset" -> "APEX 运行时资源"
            "device_or_ipc_node" -> "设备或 IPC 节点"
            "cache" -> "缓存"
            "database" -> "数据库"
            "dex_or_oat" -> "Dex/OAT 代码"
            else -> "其他系统资源"
        }
    }

    private fun eventTime(event: EBPFEvent): String {
        event.wallTimeMs()?.let { return formatWallTime(it) }
        event.timestampNs?.let { return "相对内核时间 %.3f s".format(Locale.US, it / 1_000_000_000.0) }
        event.timestampS?.let { return "相对内核时间 %.3f s".format(Locale.US, it) }
        return "unknown"
    }

    private fun EBPFEvent.wallTimeMs(): Long? {
        timestampNs?.let {
            if (it >= EPOCH_NS_THRESHOLD) return it / 1_000_000L
        }
        timestampS?.let {
            if (it >= EPOCH_SECONDS_THRESHOLD) return (it * 1000.0).toLong()
        }
        return null
    }

    private fun formatWallTime(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
    }

    private fun captureStartedAt(path: String): String {
        if (path.isBlank()) return "还没有记录"
        val millis = Regex("""(?:device_window|real_user|usage_100)_(\d+)""")
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return "未知，见采集文件名"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }

    private fun shorten(value: String, limit: Int = 96): String {
        if (value.length <= limit) return value
        return "..." + value.takeLast(limit - 3)
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
    private fun kv(key: String, value: String): View = text("$key: $value", 13f, Color.rgb(51, 65, 85), bottom = 5)

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, bottom: Int = 3): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.14f)
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(bottom) }
        }
    }

    private fun monoText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.rgb(241, 245, 249), dp(6))
            setLineSpacing(0f, 1.08f)
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(5) }
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

    private data class CapturedRecord(
        val index: Int,
        val rawLine: String,
        val event: EBPFEvent,
    )

    private data class AggregatedRecord(
        val firstIndex: Int,
        val lastIndex: Int,
        val occurrences: Int,
        val firstRawLine: String,
        val lastRawLine: String,
        val event: EBPFEvent,
        val firstWallTimeMs: Long?,
        val lastWallTimeMs: Long?,
    ) {
        fun displayTime(): String {
            val first = firstWallTimeMs
            val last = lastWallTimeMs
            if (first == null && last == null) return "见内核相对时间"
            if (first == null) return formatMillis(last!!)
            if (last == null || first == last) return formatMillis(first)
            return "${formatMillis(first)} 到 ${formatMillis(last)}"
        }

        private fun formatMillis(millis: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
        }
    }

    private companion object {
        const val RAW_RECORD_LIMIT = 60
        const val EPOCH_SECONDS_THRESHOLD = 946684800.0
        const val EPOCH_NS_THRESHOLD = 946684800_000_000_000L
    }
}
