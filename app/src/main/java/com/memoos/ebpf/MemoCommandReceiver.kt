package com.memoos.ebpf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.memoos.MainActivity

class MemoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: intent.action ?: EBPFCollectorService.ACTION_RUN_ONCE
        if (action in SERVICE_ACTIONS) {
            val serviceIntent = Intent(context, EBPFCollectorService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
            } catch (_: Exception) {
                openActivityForAction(context, action)
            }
        } else {
            openActivityForAction(context, action)
        }
    }

    private fun openActivityForAction(context: Context, action: String) {
        val activityIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ACTION, action)
        context.startActivity(activityIntent)
    }

    companion object {
        const val ACTION_COMMAND = "com.memoos.action.COMMAND"
        const val EXTRA_ACTION = "memo_action"
        private val SERVICE_ACTIONS = setOf(
            EBPFCollectorService.ACTION_RUN_ONCE,
            EBPFCollectorService.ACTION_STOP,
            EBPFCollectorService.ACTION_CHECK_SETUP,
            EBPFCollectorService.ACTION_REALTIME_START,
            EBPFCollectorService.ACTION_REALTIME_STOP,
            EBPFCollectorService.ACTION_WARM_TOP_APP,
            EBPFCollectorService.ACTION_FULL_LOCAL_EVALUATION,
            EBPFCollectorService.ACTION_RECORD_CURRENT_USAGE,
            EBPFCollectorService.ACTION_EXPERIMENT_COMMUNICATION,
            EBPFCollectorService.ACTION_EXPERIMENT_CAMERA,
            EBPFCollectorService.ACTION_EXPERIMENT_MEDIA,
            EBPFCollectorService.ACTION_EXPERIMENT_PAYMENT,
            EBPFCollectorService.ACTION_EXPERIMENT_SCROLL,
            EBPFCollectorService.ACTION_REAL_ABLATION_LATEST,
            EBPFCollectorService.ACTION_PRESSURE_EXPERIMENT,
            EBPFCollectorService.ACTION_REALTIME_TOP3_SHIFT_EXPERIMENT,
            EBPFCollectorService.ACTION_SYNTHETIC_USER_30_EXPERIMENT,
        )
    }
}
