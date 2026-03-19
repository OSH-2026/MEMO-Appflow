package com.memoos.system.memory

import android.util.Log

class RetentionController {
    fun retain(packageNames: List<String>): List<String> {
        val targets = packageNames.distinct()
        Log.i("MemoOS", "retention.targets=${targets.joinToString()} mode=app_level")
        // TODO: Replace with privileged memory retention hook when framework integration is available.
        return targets
    }
}
