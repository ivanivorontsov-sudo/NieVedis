package ru.ivanivorontsov.nevedis.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Получает смену состояния звонка (нужно разрешение READ_PHONE_STATE). Номер не читается. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = CallMicLifecycle.fromTelephonyExtra(intent.getStringExtra(TelephonyManager.EXTRA_STATE)) ?: return
        CallSession.onCallState(context, state)
    }
}
