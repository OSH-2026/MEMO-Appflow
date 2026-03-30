package com.memoos.appflow.profile

import com.memoos.appflow.model.AppLaunchProfile

class LaunchProfileRepository {
    fun profileFor(packageName: String): AppLaunchProfile {
        val normalized = packageName.lowercase()
        return exactProfiles[normalized]
            ?: heuristicProfiles.firstNotNullOfOrNull { (needle, profile) ->
                profile.takeIf { normalized.contains(needle) }
            }
            ?: genericProfile(packageName)
    }

    private fun genericProfile(packageName: String): AppLaunchProfile {
        return AppLaunchProfile(
            packageName = packageName,
            coldLaunchMs = 1600L,
            smallHotSetMb = 18,
            hotLargeSegmentsMb = 24,
            streamingTailMb = 120,
            baselineRelaunchMb = 180,
            bloatedBackgroundMb = 260,
            cutoffKb = 128,
        )
    }

    private companion object {
        val exactProfiles: Map<String, AppLaunchProfile> = listOf(
            AppLaunchProfile(
                packageName = "com.ss.android.ugc.aweme",
                coldLaunchMs = 1900L,
                smallHotSetMb = 24,
                hotLargeSegmentsMb = 30,
                streamingTailMb = 180,
                baselineRelaunchMb = 310,
                bloatedBackgroundMb = 470,
                cutoffKb = 128,
            ),
            AppLaunchProfile(
                packageName = "com.tencent.ig",
                coldLaunchMs = 2200L,
                smallHotSetMb = 28,
                hotLargeSegmentsMb = 36,
                streamingTailMb = 240,
                baselineRelaunchMb = 420,
                bloatedBackgroundMb = 640,
                cutoffKb = 128,
            ),
            AppLaunchProfile(
                packageName = "com.google.android.youtube",
                coldLaunchMs = 1500L,
                smallHotSetMb = 20,
                hotLargeSegmentsMb = 28,
                streamingTailMb = 140,
                baselineRelaunchMb = 260,
                bloatedBackgroundMb = 390,
                cutoffKb = 128,
            ),
        ).associateBy { it.packageName.lowercase() }

        val heuristicProfiles: List<Pair<String, AppLaunchProfile>> = listOf(
            "tiktok" to AppLaunchProfile(
                packageName = "tiktok",
                coldLaunchMs = 1900L,
                smallHotSetMb = 24,
                hotLargeSegmentsMb = 30,
                streamingTailMb = 180,
                baselineRelaunchMb = 310,
                bloatedBackgroundMb = 470,
                cutoffKb = 128,
            ),
            "pubg" to AppLaunchProfile(
                packageName = "pubg",
                coldLaunchMs = 2200L,
                smallHotSetMb = 28,
                hotLargeSegmentsMb = 36,
                streamingTailMb = 240,
                baselineRelaunchMb = 420,
                bloatedBackgroundMb = 640,
                cutoffKb = 128,
            ),
            "qwen" to AppLaunchProfile(
                packageName = "qwen",
                coldLaunchMs = 2400L,
                smallHotSetMb = 32,
                hotLargeSegmentsMb = 40,
                streamingTailMb = 320,
                baselineRelaunchMb = 360,
                bloatedBackgroundMb = 520,
                cutoffKb = 128,
            ),
            "gemma" to AppLaunchProfile(
                packageName = "gemma",
                coldLaunchMs = 2500L,
                smallHotSetMb = 34,
                hotLargeSegmentsMb = 42,
                streamingTailMb = 360,
                baselineRelaunchMb = 390,
                bloatedBackgroundMb = 560,
                cutoffKb = 128,
            ),
            "snow" to AppLaunchProfile(
                packageName = "snow",
                coldLaunchMs = 1800L,
                smallHotSetMb = 22,
                hotLargeSegmentsMb = 30,
                streamingTailMb = 170,
                baselineRelaunchMb = 290,
                bloatedBackgroundMb = 430,
                cutoffKb = 128,
            ),
            "rednote" to AppLaunchProfile(
                packageName = "rednote",
                coldLaunchMs = 1750L,
                smallHotSetMb = 20,
                hotLargeSegmentsMb = 26,
                streamingTailMb = 160,
                baselineRelaunchMb = 300,
                bloatedBackgroundMb = 440,
                cutoffKb = 128,
            ),
            "llm" to AppLaunchProfile(
                packageName = "llm",
                coldLaunchMs = 2450L,
                smallHotSetMb = 32,
                hotLargeSegmentsMb = 44,
                streamingTailMb = 340,
                baselineRelaunchMb = 380,
                bloatedBackgroundMb = 540,
                cutoffKb = 128,
            ),
            "game" to AppLaunchProfile(
                packageName = "game",
                coldLaunchMs = 2100L,
                smallHotSetMb = 26,
                hotLargeSegmentsMb = 34,
                streamingTailMb = 260,
                baselineRelaunchMb = 400,
                bloatedBackgroundMb = 610,
                cutoffKb = 128,
            ),
            "media" to AppLaunchProfile(
                packageName = "media",
                coldLaunchMs = 1700L,
                smallHotSetMb = 20,
                hotLargeSegmentsMb = 26,
                streamingTailMb = 180,
                baselineRelaunchMb = 250,
                bloatedBackgroundMb = 370,
                cutoffKb = 128,
            ),
        )
    }
}
