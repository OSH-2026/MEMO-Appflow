package com.memoos.ebpf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.memoos.MainActivity

class MemoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: intent.action ?: EBPFCollectorService.ACTION_RUN_ONCE
        val activityIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ACTION, action)
        context.startActivity(activityIntent)
    }

    companion object {
        const val ACTION_COMMAND = "com.memoos.action.COMMAND"
        const val EXTRA_ACTION = "memo_action"
    }
}
