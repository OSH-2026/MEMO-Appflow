package com.memoos

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 251))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        root.addView(
            text(
                "MEMO-Appflow",
                size = 30f,
                color = Color.rgb(15, 23, 42),
                bold = true,
                bottom = dp(10),
            ),
        )
        root.addView(
            text(
                "system evidence + MAPLE reasoning demo shell",
                size = 17f,
                color = Color.rgb(71, 85, 105),
                bottom = dp(24),
            ),
        )

        root.addView(section("What this app is"))
        root.addView(
            text(
                "This APK is intentionally lightweight. The main project logic runs in the host-side Android eBPF collector and the MAPLE module.",
                size = 16f,
                color = Color.rgb(30, 41, 59),
                bottom = dp(20),
            ),
        )

        root.addView(section("Pipeline"))
        listOf(
            "1. Simulate Android user actions on the Android 14 emulator.",
            "2. Capture Binder, file, scheduler, graphics, and memory evidence with eBPF where supported.",
            "3. Structure events into JSONL evidence and a MAPLE scenario.",
            "4. Run the teammate-provided MAPLE engine locally.",
            "5. Inspect resource-demand evidence for memory scheduling decisions.",
        ).forEach {
            root.addView(text(it, size = 15f, color = Color.rgb(51, 65, 85), bottom = dp(10)))
        }

        root.addView(section("Artifacts to inspect"))
        listOf(
            "trace_memo_events.jsonl",
            "ebpf_scenarios_graphics_synced.json",
            "llm/maple/maple_engine",
            "custom_android14_6.1_branch_ftrace_syscalls_graphics/bzImage",
            "docs/ebpf_report.md",
        ).forEach {
            root.addView(text(it, size = 15f, color = Color.rgb(21, 101, 192), bottom = dp(8)))
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun section(value: String): TextView {
        return text(value, size = 20f, color = Color.rgb(15, 23, 42), bold = true, top = 18, bottom = 10)
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
            gravity = Gravity.START
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = top
                bottomMargin = bottom
            }
        }
    }
}
