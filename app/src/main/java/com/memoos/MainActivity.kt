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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var showDiagnostics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RootShell.configureBridge(this)
        MemoStore(this).clearSyntheticDemoState()
        requestNotificationPermission()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        scroll.addView(root)
        renderState()
        return scroll
    }

    private fun renderState() {
        if (!::root.isInitialized) return
        val state = MemoStore(this).load()
        root.removeAllViews()

        root.addView(title("MEMO-Appflow"))
        root.addView(subtitle("Device-side prediction, real app recommendations, and scheduling actions"))
        root.addView(statusPanel(state))
        root.addView(latencyPanel(state))
        root.addView(controlPanel())
        root.addView(recommendationsPanel(state))
        root.addView(maplePanel(state))
        root.addView(actionsPanel(state))
        root.addView(evidencePanel(state))
        root.addView(diagnosticsToggle())
        if (showDiagnostics) {
            root.addView(diagnosticsPanel(state))
        }
    }

    private fun statusPanel(state: LastMemoState): View {
        val updated = if (state.updatedAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(state.updatedAt))
        } else {
            "No run yet"
        }
        val panel = verticalPanel()
        panel.addView(label("Current State"))
        panel.addView(kv("Last run", updated))
        panel.addView(kv("MAPLE", if (state.maple.available) "Ready via ${state.maple.backend}" else "Not ready: ${state.maple.error ?: "waiting for model"}"))
        panel.addView(kv("Recommendations", if (state.recommendations.isEmpty()) "Not generated yet" else "${state.recommendations.size} real launchable apps"))
        panel.addView(kv("Evidence", evidenceHeadline(state)))
        return panel
    }

    private fun latencyPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("Latency Budget"))
        if (!state.latency.isPresent) {
            panel.addView(body("No latency data yet. Runs now record capture, parsing, MAPLE, app mapping, actions, and total time."))
            return panel
        }
        val statusColor = when (state.latency.realtimeStatus) {
            "ok" -> Color.rgb(22, 101, 52)
            "degraded" -> Color.rgb(180, 83, 9)
            else -> Color.rgb(185, 28, 28)
        }
        panel.addView(statusText(state.latency.realtimeStatus.uppercase(Locale.US), statusColor))
        panel.addView(kv("Foreground", "${formatDuration(state.latency.foregroundMs)} / budget ${formatDuration(state.latency.realtimeBudgetMs)}"))
        panel.addView(kv("Total incl. MAPLE", formatDuration(state.latency.totalMs)))
        panel.addView(kv("Parsed eBPF records", state.latency.parsedEvents.toString()))
        state.latency.stages
            .sortedByDescending { it.durationMs }
            .take(6)
            .forEach { stage -> panel.addView(bullet("${stage.name}: ${formatDuration(stage.durationMs)}")) }
        return panel
    }

    private fun controlPanel(): View {
        val panel = verticalPanel()
        panel.addView(label("Controls"))
        panel.addView(rowButton("Run Device Pipeline", EBPFCollectorService.ACTION_RUN_ONCE, primary = true))
        panel.addView(rowButton("Stop Collection", EBPFCollectorService.ACTION_STOP, primary = false))
        panel.addView(smallCaption("Real eBPF experiments"))
        panel.addView(rowButton("Record Current Real Usage (28s)", EBPFCollectorService.ACTION_RECORD_CURRENT_USAGE, primary = false))
        panel.addView(rowButton("Open Real Communication App + Record", EBPFCollectorService.ACTION_EXPERIMENT_COMMUNICATION, primary = false))
        panel.addView(rowButton("Open Real Camera/Photo App + Record", EBPFCollectorService.ACTION_EXPERIMENT_CAMERA, primary = false))
        panel.addView(rowButton("Open Real Media/Video App + Record", EBPFCollectorService.ACTION_EXPERIMENT_MEDIA, primary = false))
        panel.addView(rowButton("Open Payment/Security App if Installed + Record", EBPFCollectorService.ACTION_EXPERIMENT_PAYMENT, primary = false))
        panel.addView(rowButton("Run Real Scroll/Display Interaction + Record", EBPFCollectorService.ACTION_EXPERIMENT_SCROLL, primary = false))
        panel.addView(smallCaption("Real eBPF ablation"))
        panel.addView(rowButton("Run Ablation on Latest Real Trace", EBPFCollectorService.ACTION_REAL_ABLATION_LATEST, primary = false))
        return panel
    }

    private fun recommendationsPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("Top-3 Apps"))
        if (state.recommendations.isEmpty()) {
            panel.addView(body("Run the device pipeline or a real eBPF experiment. Recommendations will be real installed apps, not processes or services."))
            return panel
        }
        state.recommendations.forEachIndexed { index, app ->
            panel.addView(appRow(index + 1, app))
        }
        return panel
    }

    private fun maplePanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("MAPLE Prediction"))
        if (state.maple.available) {
            panel.addView(kv("Backend", state.maple.backend))
            panel.addView(kv("MAPLE App ID", state.maple.predictedAppId.takeIf { it > 0 }?.toString() ?: "No specific ID"))
            if (state.maple.stage1.isNotEmpty()) {
                panel.addView(body("Predicted resource needs:"))
                state.maple.stage1.take(4).forEach { panel.addView(chip(it)) }
            }
        } else {
            panel.addView(statusText(if (state.maple.backend == "pending") "Running" else "Blocked", Color.rgb(180, 83, 9)))
            panel.addView(body(state.maple.error ?: "MAPLE model or Android native engine is not available yet."))
        }
        return panel
    }

    private fun actionsPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("Executed System Actions"))
        if (state.actions.isEmpty()) {
            panel.addView(body("No action has run yet."))
            return panel
        }
        state.actions.forEach { panel.addView(actionRow(it)) }
        return panel
    }

    private fun evidencePanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("Evidence Summary"))
        val userFacing = state.evidenceLines
            .filterNot { it.startsWith("process ") }
            .take(6)
        if (userFacing.isEmpty()) {
            panel.addView(body("No device evidence collected yet."))
        } else {
            userFacing.forEach { panel.addView(bullet(it)) }
        }
        panel.addView(smallCaption("Kernel details are hidden from the normal view. Open Advanced diagnostics only when debugging the collector."))
        return panel
    }

    private fun diagnosticsToggle(): View {
        return Button(this).apply {
            text = if (showDiagnostics) "Hide Advanced Diagnostics" else "Show Advanced Diagnostics"
            isAllCaps = false
            setTextColor(Color.rgb(15, 23, 42))
            background = rounded(Color.rgb(226, 232, 240), dp(8))
            setOnClickListener {
                showDiagnostics = !showDiagnostics
                renderState()
            }
            layoutParams = LinearLayout.LayoutParams(match(), dp(46)).apply { bottomMargin = dp(12) }
        }
    }

    private fun diagnosticsPanel(state: LastMemoState): View {
        val panel = verticalPanel()
        panel.addView(label("Advanced Diagnostics"))
        panel.addView(kv("Raw evidence lines", state.evidenceLines.size.toString()))
        panel.addView(kv("Scenario JSON", if (state.scenarioJson.isBlank()) "not generated" else "${state.scenarioJson.length} chars stored"))
        panel.addView(kv("MAPLE raw JSON", if (state.rawMapleJson.isBlank()) "empty" else "${state.rawMapleJson.length} chars stored"))
        panel.addView(kv("Actions raw JSON", if (state.rawActionsJson.isBlank()) "empty" else "${state.rawActionsJson.length} chars stored"))
        panel.addView(kv("Latency raw JSON", if (state.rawLatencyJson.isBlank()) "empty" else "${state.rawLatencyJson.length} chars stored"))

        val scanned = AppIdMapping.scanInstalledApps(this)
        panel.addView(kv("Launchable apps visible", scanned.size.toString()))
        scanned.take(8).forEach { app ->
            panel.addView(bullet("${app.label}: ${app.inferredCategories.take(4).joinToString()}"))
        }
        if (state.evidenceLines.isNotEmpty()) {
            panel.addView(smallCaption("Raw evidence sample"))
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
        info.addView(text(app.category, 13f, Color.rgb(37, 99, 235)))
        val why = app.reason.substringBefore(" perms=").replace("auto-classified from Android metadata: ", "")
        if (why.isNotBlank()) info.addView(text(why, 12f, Color.rgb(71, 85, 105)))

        val open = Button(this).apply {
            text = "Open"
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
        row.addView(text("${actionTitle(action.name)}: ${action.target}", 14f, Color.rgb(15, 23, 42), bold = true))
        row.addView(statusText(action.status.uppercase(Locale.US), statusColor))
        if (action.detail.isNotBlank()) row.addView(text(action.detail, 12f, Color.rgb(71, 85, 105)))
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
            "widget_update" -> "Widget update"
            "latency_policy" -> "Latency policy"
            "latency_summary" -> "Latency summary"
            "maple_background" -> "MAPLE background"
            "memory_policy" -> "Memory policy"
            "thermal_policy" -> "Thermal policy"
            "warm_launch" -> "Warm launch"
            "network_candidate_priority" -> "Network priority"
            "network_stats_refresh" -> "Network refresh"
            "camera_media_candidate" -> "Camera/media prep"
            "camera_media_prewarm" -> "Camera/media policy"
            "display_ui_policy" -> "Display policy"
            "binder_service_policy" -> "Service policy"
            "binder_service_refresh" -> "Service refresh"
            "maple_backend" -> "MAPLE backend"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun evidenceHeadline(state: LastMemoState): String {
        return state.evidenceLines.firstOrNull { "records" in it }
            ?: state.evidenceLines.firstOrNull()
            ?: "No evidence collected yet"
    }

    private fun formatDuration(ms: Long): String = PipelineLatency.formatMs(ms)

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT
}
