package com.memoos.ui.main

import android.app.Application
import androidx.work.Configuration

class MemoApplication : Application(), Configuration.Provider {
    val graph: MemoGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MemoGraph.create(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
