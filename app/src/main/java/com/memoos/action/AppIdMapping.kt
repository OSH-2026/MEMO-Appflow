package com.memoos.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject

data class RecommendedApp(
    val packageName: String,
    val label: String,
    val category: String,
    val appId: Int,
    val confidence: Double,
    val reason: String,
)

object AppIdMapping {
    private const val CATEGORY_COMMUNICATION = "Communication"
    private const val CATEGORY_CAMERA = "Camera Service"
    private const val CATEGORY_MEDIA = "Media Codec"
    private const val CATEGORY_NETWORK = "Network IO"
    private const val CATEGORY_PAYMENT = "Payment/Security"
    private const val CATEGORY_LOCATION = "Navigation/Location"
    private const val CATEGORY_DISPLAY = "Display Composition"
    private const val CATEGORY_RUNTIME = "App Process Runtime"

    data class InstalledAppProfile(
        val packageName: String,
        val label: String,
        val isUserInstalled: Boolean,
        val androidCategory: Int,
        val requestedPermissions: Set<String>,
        val intentCapabilities: Set<String>,
        val roleCategories: Set<String>,
        val lexicalHints: Set<String>,
        val inferredCategories: Set<String>,
    )

    private val categoryIds = mapOf(
        "Android Service IPC" to 110,
        "Communication" to 110,
        "Display Composition" to 115,
        "Native Runtime Loading" to 120,
        "Framework Loading" to 130,
        "System Property Access" to 140,
        "APEX Runtime Loading" to 150,
        "Kernel Trace Setup" to 160,
        "Process State Inspection" to 170,
        "Database" to 180,
        "Dex/OAT Loading" to 190,
        "Cache/File Cache" to 200,
        "Config File Access" to 210,
        "Memory Management" to 220,
        "Other File Access" to 230,
        "Device/IPC Node Access" to 235,
        "Input Interaction" to 240,
        "Network IO" to 245,
        "Camera Service" to 250,
        "Payment/Security" to 255,
        "Media Codec" to 260,
        "Navigation/Location" to 270,
        "App Process Runtime" to 280,
        "Android System Services" to 290,
        "Power/Thermal Management" to 300,
    )

    fun categoryId(category: String): Int = categoryIds[normalizeCategory(category)] ?: categoryIds[category] ?: 999

    fun installedAppsForMaple(context: Context, categories: List<String>): JSONObject {
        val obj = JSONObject()
        categories.forEach { category ->
            obj.put(normalizeCategory(category), JSONArray(listOf(categoryId(category))))
        }
        val profiles = scanInstalledApps(context).take(32)
        profiles.groupBy { profile ->
            profile.inferredCategories.firstOrNull() ?: CATEGORY_RUNTIME
        }.forEach { (category, apps) ->
            obj.put(category, JSONArray(apps.indices.map { categoryId(category) + it }))
        }
        return obj
    }

    fun resolveTopApps(
        context: Context,
        predictedAppId: Int,
        stage1Categories: List<String>,
        scenarioCategories: List<String>,
        foregroundPackage: String?,
    ): List<RecommendedApp> {
        val profiles = scanInstalledApps(context)
        val desiredCategories = mutableListOf<String>()
        categoryForId(predictedAppId)?.let { desiredCategories += it }
        desiredCategories += stage1Categories.map { normalizeCategory(it) }
        desiredCategories += scenarioCategories.map { normalizeCategory(it) }
        foregroundPackage?.let { desiredCategories += followUpCategoryForPackage(it) }

        val scored = profiles.map { profile ->
            val categoryScore = desiredCategories
                .filter { it.isNotBlank() }
                .maxOfOrNull { desired -> categoryMatchScore(desired, profile) }
                ?: 0.0
            val foregroundBoost = if (profile.packageName == foregroundPackage) 0.18 else 0.0
            val userBoost = if (profile.isUserInstalled) 0.04 else 0.0
            val score = categoryScore + foregroundBoost + userBoost
            val matched = desiredCategories.maxByOrNull { categoryMatchScore(it, profile) }
                ?.takeIf { categoryMatchScore(it, profile) > 0.0 }
                ?: profile.inferredCategories.firstOrNull()
                ?: "Installed Android launchable apps"
            profile to (matched to score)
        }.filter { it.second.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<InstalledAppProfile, Pair<String, Double>>> { it.second.second }
                    .thenByDescending { it.first.isUserInstalled }
                    .thenBy { it.first.label.lowercase() },
            )

        val recommendations = linkedMapOf<String, RecommendedApp>()
        desiredCategories
            .map { normalizeCategory(it) }
            .distinct()
            .filter { it.isNotBlank() }
            .forEach { desired ->
                val candidate = scored
                    .filter { it.first.packageName !in recommendations }
                    .filter { categoryMatchScore(desired, it.first) >= 0.55 }
                    .maxByOrNull { categoryMatchScore(desired, it.first) }
                    ?: return@forEach
                val profile = candidate.first
                recommendations[profile.packageName] = RecommendedApp(
                    packageName = profile.packageName,
                    label = profile.label,
                    category = desired,
                    appId = categoryId(desired),
                    confidence = categoryMatchScore(desired, profile).coerceIn(0.0, 1.0),
                    reason = "reserved for predicted evidence category: $desired; roles=${profile.roleCategories.joinToString()} intents=${profile.intentCapabilities.joinToString()} perms=${profile.requestedPermissions.size}",
                )
                if (recommendations.size >= 3) return recommendations.values.toList()
            }
        scored.forEach { (profile, match) ->
            if (profile.packageName !in recommendations) {
                recommendations[profile.packageName] = RecommendedApp(
                    packageName = profile.packageName,
                    label = profile.label,
                    category = normalizeCategory(match.first),
                    appId = categoryId(match.first),
                    confidence = match.second.coerceIn(0.0, 1.0),
                    reason = "auto-classified from Android metadata: roles=${profile.roleCategories.joinToString()} intents=${profile.intentCapabilities.joinToString()} perms=${profile.requestedPermissions.size}",
                )
            }
            if (recommendations.size >= 3) return recommendations.values.toList()
        }

        if (recommendations.size < 3) {
            profiles.filter { it.packageName !in recommendations }
                .sortedWith(compareByDescending<InstalledAppProfile> { it.isUserInstalled }.thenBy { it.label.lowercase() })
                .take(3 - recommendations.size)
                .forEach { profile ->
                    val category = profile.inferredCategories.firstOrNull() ?: CATEGORY_RUNTIME
                    recommendations[profile.packageName] = RecommendedApp(
                        packageName = profile.packageName,
                        label = profile.label,
                        category = category,
                        appId = categoryId(category),
                        confidence = 0.42,
                        reason = "coverage completion from real installed launchable apps",
                    )
                }
        }
        return recommendations.values.take(3)
    }

    fun scanInstalledApps(context: Context): List<InstalledAppProfile> {
        val packageManager = context.packageManager
        val roleMap = roleCategories(context)
        val intentMap = intentCapabilities(context)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val appInfo = try {
                    packageManager.getApplicationInfo(packageName, 0)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val label = packageManager.getApplicationLabel(appInfo).toString()
                val permissions = requestedPermissions(packageManager, packageName)
                val roles = roleMap[packageName].orEmpty()
                val intents = intentMap[packageName].orEmpty()
                val lexical = lexicalHints(packageName, label)
                val userInstalled = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val inferred = inferCategories(
                    androidCategory = appInfo.category,
                    permissions = permissions,
                    intentCapabilities = intents,
                    roleCategories = roles,
                    lexicalHints = lexical,
                )
                InstalledAppProfile(
                    packageName = packageName,
                    label = label,
                    isUserInstalled = userInstalled,
                    androidCategory = appInfo.category,
                    requestedPermissions = permissions,
                    intentCapabilities = intents,
                    roleCategories = roles,
                    lexicalHints = lexical,
                    inferredCategories = inferred,
                )
            }
            .distinctBy { it.packageName }
    }

    fun categoryForPackage(packageName: String): String {
        val lower = packageName.lowercase()
        return when {
            lower.contains("camera") || lower.contains("photos") || lower.contains("gallery") -> CATEGORY_CAMERA
            lower.contains("video") || lower.contains("player") -> CATEGORY_MEDIA
            lower.contains("browser") || lower.contains("web") -> CATEGORY_NETWORK
            lower.contains("map") || lower.contains("location") -> CATEGORY_LOCATION
            lower.contains("wallet") || lower.contains("pay") -> CATEGORY_PAYMENT
            lower.contains("messag") || lower.contains("dialer") || lower.contains("contact") -> CATEGORY_COMMUNICATION
            else -> CATEGORY_RUNTIME
        }
    }

    private fun followUpCategoryForPackage(packageName: String): String {
        return when (categoryForPackage(packageName)) {
            CATEGORY_COMMUNICATION -> CATEGORY_CAMERA
            CATEGORY_CAMERA -> CATEGORY_COMMUNICATION
            CATEGORY_MEDIA -> CATEGORY_NETWORK
            CATEGORY_NETWORK -> CATEGORY_MEDIA
            CATEGORY_PAYMENT -> CATEGORY_COMMUNICATION
            else -> categoryForPackage(packageName)
        }
    }

    private fun categoryMatchScore(category: String, profile: InstalledAppProfile): Double {
        val normalized = normalizeCategory(category)
        if (normalized in profile.roleCategories) return 0.98
        if (normalized in profile.intentCapabilities) return 0.90
        if (normalized in profile.lexicalHints) return 0.88
        if (profile.inferredCategories.firstOrNull() == normalized) return 0.84
        if (normalized in profile.inferredCategories) return 0.62
        return when (normalized) {
            CATEGORY_COMMUNICATION -> if (profile.inferredCategories.any { it in setOf(CATEGORY_NETWORK, CATEGORY_CAMERA) }) 0.48 else 0.0
            CATEGORY_CAMERA -> if (profile.inferredCategories.any { it in setOf(CATEGORY_COMMUNICATION, CATEGORY_MEDIA) }) 0.50 else 0.0
            CATEGORY_MEDIA -> if (profile.inferredCategories.any { it in setOf(CATEGORY_NETWORK, CATEGORY_DISPLAY, CATEGORY_CAMERA) }) 0.54 else 0.0
            CATEGORY_NETWORK -> if (CATEGORY_COMMUNICATION in profile.inferredCategories || CATEGORY_MEDIA in profile.inferredCategories) 0.50 else 0.0
            CATEGORY_DISPLAY -> if (profile.inferredCategories.any { it in setOf(CATEGORY_MEDIA, CATEGORY_NETWORK, CATEGORY_CAMERA) }) 0.46 else 0.0
            CATEGORY_PAYMENT -> if (CATEGORY_NETWORK in profile.inferredCategories) 0.36 else 0.0
            else -> 0.0
        }
    }

    private fun inferCategories(
        androidCategory: Int,
        permissions: Set<String>,
        intentCapabilities: Set<String>,
        roleCategories: Set<String>,
        lexicalHints: Set<String>,
    ): Set<String> {
        val categories = linkedSetOf<String>()
        categories += roleCategories
        categories += intentCapabilities
        when (androidCategory) {
            ApplicationInfo.CATEGORY_SOCIAL -> categories += CATEGORY_COMMUNICATION
            ApplicationInfo.CATEGORY_IMAGE -> categories += CATEGORY_CAMERA
            ApplicationInfo.CATEGORY_VIDEO -> categories += CATEGORY_MEDIA
            ApplicationInfo.CATEGORY_AUDIO -> categories += CATEGORY_MEDIA
            ApplicationInfo.CATEGORY_MAPS -> categories += CATEGORY_LOCATION
            ApplicationInfo.CATEGORY_GAME -> categories += CATEGORY_DISPLAY
            ApplicationInfo.CATEGORY_NEWS -> categories += CATEGORY_NETWORK
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> categories += CATEGORY_RUNTIME
        }
        if (permissions.any { it.endsWith(".CAMERA") }) categories += CATEGORY_CAMERA
        if (permissions.any { it.endsWith(".RECORD_AUDIO") || it.endsWith(".MODIFY_AUDIO_SETTINGS") }) categories += CATEGORY_MEDIA
        if (permissions.any { it.endsWith(".INTERNET") || it.endsWith(".ACCESS_NETWORK_STATE") }) categories += CATEGORY_NETWORK
        if (permissions.any { it.endsWith(".ACCESS_FINE_LOCATION") || it.endsWith(".ACCESS_COARSE_LOCATION") }) categories += CATEGORY_LOCATION
        if (permissions.any { it.endsWith(".CALL_PHONE") || it.endsWith(".READ_CONTACTS") || it.endsWith(".SEND_SMS") || it.endsWith(".READ_SMS") }) categories += CATEGORY_COMMUNICATION
        if (permissions.any { it.endsWith(".NFC") || it.endsWith(".USE_BIOMETRIC") || it.endsWith(".USE_FINGERPRINT") }) categories += CATEGORY_PAYMENT
        categories += lexicalHints.take(1)
        if (categories.isEmpty()) categories += CATEGORY_RUNTIME
        return categories
    }

    private fun roleCategories(context: Context): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        fun add(packageName: String?, category: String) {
            if (!packageName.isNullOrBlank() && packageName != context.packageName) {
                result.getOrPut(packageName) { linkedSetOf() } += category
            }
        }
        val pm = context.packageManager
        val browser = pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
        add(browser, CATEGORY_NETWORK)
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
        add(home, CATEGORY_RUNTIME)
        val telecom = context.getSystemService(TelecomManager::class.java)
        add(telecom?.defaultDialerPackage, CATEGORY_COMMUNICATION)
        add(Telephony.Sms.getDefaultSmsPackage(context), CATEGORY_COMMUNICATION)
        return result
    }

    private fun intentCapabilities(context: Context): Map<String, Set<String>> {
        val specs = listOf(
            IntentSpec(CATEGORY_CAMERA, Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
            IntentSpec(CATEGORY_CAMERA, Intent(Intent.ACTION_PICK).setType("image/*")),
            IntentSpec(CATEGORY_MEDIA, Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/*")),
            IntentSpec(CATEGORY_MEDIA, Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/audio/media/1"), "audio/*")),
            IntentSpec(CATEGORY_NETWORK, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            IntentSpec(CATEGORY_COMMUNICATION, Intent(Intent.ACTION_DIAL, Uri.parse("tel:10086"))),
            IntentSpec(CATEGORY_COMMUNICATION, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:10086"))),
            IntentSpec(CATEGORY_LOCATION, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee"))),
            IntentSpec(CATEGORY_PAYMENT, Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=memo@example&pn=MEMO"))),
            IntentSpec(CATEGORY_DISPLAY, Intent(Intent.ACTION_VIEW).setType("image/*")),
        )
        val result = mutableMapOf<String, MutableSet<String>>()
        specs.forEach { spec ->
            for (resolveInfo in context.packageManager.queryIntentActivities(spec.intent, 0)) {
                val pkg = resolveInfo.activityInfo?.packageName ?: continue
                if (pkg != context.packageName) {
                    result.getOrPut(pkg) { linkedSetOf() } += spec.category
                }
            }
        }
        return result
    }

    private data class IntentSpec(val category: String, val intent: Intent)

    private fun lexicalHints(packageName: String, appLabel: String): Set<String> {
        val text = "$packageName $appLabel".lowercase()
        val categories = linkedSetOf<String>()
        if (containsAny(text, "chat", "message", "messaging", "phone", "dialer", "contacts", "sms")) categories += CATEGORY_COMMUNICATION
        if (containsAny(text, "camera", "photo", "gallery", "album", "scan", "lens")) categories += CATEGORY_CAMERA
        if (containsAny(text, "video", "player", "music", "audio", "media")) categories += CATEGORY_MEDIA
        if (containsAny(text, "browser", "web", "mail", "news")) categories += CATEGORY_NETWORK
        if (containsAny(text, "map", "navigation", "location", "gps")) categories += CATEGORY_LOCATION
        if (containsAny(text, "pay", "wallet", "bank", "finance", "nfc")) categories += CATEGORY_PAYMENT
        if (containsAny(text, "game")) categories += CATEGORY_DISPLAY
        return categories
    }

    private fun requestedPermissions(packageManager: PackageManager, packageName: String): Set<String> {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun containsAny(value: String, vararg needles: String): Boolean {
        return needles.any { value.contains(it) }
    }

    private fun categoryForId(id: Int): String? {
        return categoryIds.entries.firstOrNull { it.value == id }?.key?.let { normalizeCategory(it) }
    }

    private fun normalizeCategory(category: String): String {
        val lower = category.lowercase()
        return when {
            "communication" in lower || "service ipc" in lower || "binder" in lower -> CATEGORY_COMMUNICATION
            "camera" in lower || "photo" in lower -> CATEGORY_CAMERA
            "media" in lower || "codec" in lower || "video" in lower -> CATEGORY_MEDIA
            "network" in lower || "udp" in lower || "sendto" in lower || "recvfrom" in lower -> CATEGORY_NETWORK
            "payment" in lower || "security" in lower || "wallet" in lower -> CATEGORY_PAYMENT
            "display" in lower || "render" in lower || "surfaceflinger" in lower -> CATEGORY_DISPLAY
            "memory" in lower || "reclaim" in lower || "lmkd" in lower -> "Memory Management"
            "location" in lower || "navigation" in lower || "maps" in lower -> CATEGORY_LOCATION
            else -> category
        }
    }

}
