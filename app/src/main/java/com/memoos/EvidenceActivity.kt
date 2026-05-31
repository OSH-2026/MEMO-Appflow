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
            overview.addView(body("还没有可读取的 raw eBPF 记录。请先运行“开始智能优化”，或者运行一次真实使用采集。"))
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
        feed.addView(label("真实收到的前 ${records.size} 条事件"))
        if (records.isEmpty()) {
            feed.addView(body("没有可格式化的真实事件记录。"))
        } else {
            feed.addView(body("下面每一条都来自 raw trace 文件。当前 raw collector 在采集窗口结束时读取 eBPF counter map，并把窗口内真实发生的内核事件计数展开成 MEMO 记录；这里显示文件里的前 ${records.size} 条，不手写、不随机生成。"))
            records.forEach { record -> feed.addView(eventCard(record)) }
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

    private fun eventCard(record: CapturedRecord): View {
        val event = record.event
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(248, 250, 252), dp(8))
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) }
        }
        panel.addView(text("第 ${record.index} 条真实记录 · ${eventName(event.eventType)}", 14f, Color.rgb(15, 23, 42), bold = true))
        val process = event.comm?.takeIf { it.isNotBlank() } ?: event.traceTask ?: "unknown"
        panel.addView(text("进程: $process  PID: ${event.pid ?: "-"}  TID: ${event.tid ?: event.traceTid ?: "-"}", 12f, Color.rgb(71, 85, 105)))
        panel.addView(text("资源类别: ${categoryName(event.evidenceCategory, event.eventType)}", 12f, Color.rgb(37, 99, 235)))
        event.path?.takeIf { it.isNotBlank() }?.let {
            panel.addView(text("对象: ${shorten(it)}", 12f, Color.rgb(71, 85, 105)))
        }
        event.detail?.takeIf { it.isNotBlank() && it != event.path }?.let {
            panel.addView(text("细节: ${shorten(it)}", 12f, Color.rgb(71, 85, 105)))
        }
        panel.addView(text("内核时间戳: ${eventTime(event)}", 11f, Color.rgb(100, 116, 139)))
        panel.addView(monoText("原始记录: ${shorten(record.rawLine, 180)}"))
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
        event.timestampNs?.let { return "%.3f s".format(Locale.US, it / 1_000_000_000.0) }
        event.timestampS?.let { return "%.3f s".format(Locale.US, it) }
        return "unknown"
    }

    private fun captureStartedAt(path: String): String {
        if (path.isBlank()) return "还没有记录"
        val millis = Regex("""(?:device_window|real_user)_(\d+)""")
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

    private companion object {
        const val RAW_RECORD_LIMIT = 60
    }
}
