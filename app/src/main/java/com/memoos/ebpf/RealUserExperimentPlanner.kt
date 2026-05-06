package com.memoos.ebpf

import android.content.Context
import com.memoos.action.AppIdMapping
import com.memoos.action.AppIdMapping.InstalledAppProfile
import com.memoos.device.RootShell

data class RealUserExperimentPlan(
    val id: String,
    val title: String,
    val description: String,
    val targetPackage: String?,
    val targetLabel: String?,
    val desiredCategories: List<String>,
    val interactionCommands: List<String>,
) {
    fun execute(context: Context): String {
        val commands = mutableListOf<String>()
        targetPackage?.let { pkg ->
            launchCommand(context, pkg)?.let { commands += it }
        }
        commands += interactionCommands
        if (commands.isEmpty()) {
            return "record_current_usage only; no app launch or synthetic evidence was injected"
        }
        val result = RootShell.run(commands.joinToString("; "), requireRoot = true, timeoutMs = 18_000L)
        return if (result.ok) {
            "executed real Android interaction plan target=${targetPackage ?: "current_app"}"
        } else {
            "interaction plan failed: ${result.stderr.ifBlank { result.stdout }.take(220)}"
        }
    }

    private fun launchCommand(context: Context, packageName: String): String? {
        val component = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component
            ?.flattenToShortString()
            ?: return null
        return "am start -W -n '$component' >/dev/null 2>&1"
    }
}

object RealUserExperimentPlanner {
    const val KIND_CURRENT = "current_usage"
    const val KIND_COMMUNICATION = "communication_usage"
    const val KIND_CAMERA = "camera_photo_usage"
    const val KIND_MEDIA = "media_video_usage"
    const val KIND_PAYMENT = "payment_security_usage"
    const val KIND_SCROLL = "scroll_display_usage"

    private const val CATEGORY_COMMUNICATION = "Communication"
    private const val CATEGORY_CAMERA = "Camera Service"
    private const val CATEGORY_MEDIA = "Media Codec"
    private const val CATEGORY_NETWORK = "Network IO"
    private const val CATEGORY_PAYMENT = "Payment/Security"
    private const val CATEGORY_DISPLAY = "Display Composition"
    private const val CATEGORY_RUNTIME = "App Process Runtime"

    fun plan(context: Context, kind: String): RealUserExperimentPlan {
        val apps = AppIdMapping.scanInstalledApps(context)
        return when (kind) {
            KIND_COMMUNICATION -> planFor(
                id = KIND_COMMUNICATION,
                title = "Real communication app usage",
                description = "Launch a real installed communication/network app, then record naturally generated Binder, network, file, display, and process eBPF evidence.",
                apps = apps,
                desired = listOf(CATEGORY_COMMUNICATION, CATEGORY_NETWORK),
                commands = openAndBrowseCommands(),
            )
            KIND_CAMERA -> planFor(
                id = KIND_CAMERA,
                title = "Real camera or photo app usage",
                description = "Launch a real installed camera/photo-capable app and record camera/media/display/process evidence from the device.",
                apps = apps,
                desired = listOf(CATEGORY_CAMERA, CATEGORY_MEDIA, CATEGORY_DISPLAY),
                commands = cameraLikeCommands(),
            )
            KIND_MEDIA -> planFor(
                id = KIND_MEDIA,
                title = "Real media or video app usage",
                description = "Launch a real installed media/network-capable app and record MediaCodec, UDP, SurfaceFlinger, Binder, and memory evidence.",
                apps = apps,
                desired = listOf(CATEGORY_MEDIA, CATEGORY_NETWORK, CATEGORY_DISPLAY),
                commands = mediaLikeCommands(),
            )
            KIND_PAYMENT -> planFor(
                id = KIND_PAYMENT,
                title = "Real payment or security app usage if installed",
                description = "Use a real installed wallet/security-capable app when present. If none exists, the run is marked as a real no-target capture instead of fabricating payment evidence.",
                apps = apps,
                desired = listOf(CATEGORY_PAYMENT, CATEGORY_NETWORK, CATEGORY_COMMUNICATION),
                commands = openAndBrowseCommands(),
                requireDirectCategory = true,
            )
            KIND_SCROLL -> planFor(
                id = KIND_SCROLL,
                title = "Real scrolling and display usage",
                description = "Launch a real installed app and drive Android input swipes so SurfaceFlinger, RenderThread, Binder, file, and scheduler evidence comes from the kernel.",
                apps = apps,
                desired = listOf(CATEGORY_DISPLAY, CATEGORY_NETWORK, CATEGORY_MEDIA, CATEGORY_RUNTIME),
                commands = scrollCommands(),
            )
            else -> RealUserExperimentPlan(
                id = KIND_CURRENT,
                title = "Record current real app usage",
                description = "Keep eBPF running while the user manually opens and uses apps. Product logic runs after the real device window closes.",
                targetPackage = null,
                targetLabel = null,
                desiredCategories = emptyList(),
                interactionCommands = emptyList(),
            )
        }
    }

    private fun planFor(
        id: String,
        title: String,
        description: String,
        apps: List<InstalledAppProfile>,
        desired: List<String>,
        commands: List<String>,
        requireDirectCategory: Boolean = false,
    ): RealUserExperimentPlan {
        val target = chooseTarget(apps, desired, requireDirectCategory)
        val noTargetSuffix = if (target == null) {
            " No matching launchable app was installed, so this captures current real usage without injecting synthetic eBPF evidence."
        } else {
            ""
        }
        return RealUserExperimentPlan(
            id = id,
            title = title,
            description = description + noTargetSuffix,
            targetPackage = target?.packageName,
            targetLabel = target?.label,
            desiredCategories = desired,
            interactionCommands = if (target == null) emptyList() else commands,
        )
    }

    private fun chooseTarget(
        apps: List<InstalledAppProfile>,
        desired: List<String>,
        requireDirectCategory: Boolean,
    ): InstalledAppProfile? {
        val direct = apps
            .map { app -> app to desired.maxOfOrNull { category -> score(app, category) }.orZero() }
            .filter { it.second >= 0.70 }
            .sortedWith(compareByDescending<Pair<InstalledAppProfile, Double>> { it.second }
                .thenByDescending { it.first.isUserInstalled }
                .thenBy { it.first.label.lowercase() })
            .firstOrNull()
            ?.first
        if (direct != null || requireDirectCategory) return direct
        return apps.sortedWith(
            compareByDescending<InstalledAppProfile> { it.isUserInstalled }
                .thenBy { it.label.lowercase() },
        ).firstOrNull()
    }

    private fun score(app: InstalledAppProfile, category: String): Double {
        return when {
            category in app.roleCategories -> 1.0
            category in app.intentCapabilities -> 0.94
            category in app.inferredCategories -> 0.84
            category in app.lexicalHints -> 0.80
            category == CATEGORY_DISPLAY && app.inferredCategories.any { it in setOf(CATEGORY_MEDIA, CATEGORY_NETWORK, CATEGORY_CAMERA) } -> 0.58
            category == CATEGORY_NETWORK && app.inferredCategories.any { it in setOf(CATEGORY_COMMUNICATION, CATEGORY_MEDIA) } -> 0.55
            else -> 0.0
        }
    }

    private fun openAndBrowseCommands(): List<String> {
        return listOf(
            "sleep 1",
            "input tap 540 960 >/dev/null 2>&1",
            "sleep 1",
            "input swipe 520 1500 520 650 500 >/dev/null 2>&1",
            "sleep 1",
            "input keyevent KEYCODE_BACK >/dev/null 2>&1",
        )
    }

    private fun cameraLikeCommands(): List<String> {
        return listOf(
            "sleep 2",
            "input tap 540 1800 >/dev/null 2>&1",
            "sleep 2",
            "input keyevent KEYCODE_BACK >/dev/null 2>&1",
        )
    }

    private fun mediaLikeCommands(): List<String> {
        return listOf(
            "sleep 1",
            "input tap 540 1000 >/dev/null 2>&1",
            "sleep 2",
            "input swipe 540 1550 540 520 700 >/dev/null 2>&1",
            "sleep 1",
            "input keyevent KEYCODE_BACK >/dev/null 2>&1",
        )
    }

    private fun scrollCommands(): List<String> {
        return listOf(
            "sleep 1",
            "input swipe 540 1650 540 450 800 >/dev/null 2>&1",
            "sleep 1",
            "input swipe 540 450 540 1650 800 >/dev/null 2>&1",
            "sleep 1",
            "input swipe 540 1650 540 450 800 >/dev/null 2>&1",
        )
    }

    private fun Double?.orZero(): Double = this ?: 0.0
}
