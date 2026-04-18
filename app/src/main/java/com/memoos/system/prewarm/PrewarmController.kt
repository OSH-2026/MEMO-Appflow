package com.memoos.system.prewarm

import android.util.Log

class PrewarmController {
    fun prewarm(packageNames: List<String>): List<String> {
        val targets = packageNames.distinct()
        Log.i("MemoOS", "prewarm.targets=${targets.joinToString()} mode=app_level")
        // TODO: Replace with privileged prewarm hook when system service integration is available.
        return targets
    }
}
