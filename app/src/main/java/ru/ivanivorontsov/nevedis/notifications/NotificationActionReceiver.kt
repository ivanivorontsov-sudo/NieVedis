package ru.ivanivorontsov.nevedis.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import ru.ivanivorontsov.nevedis.settings.AppSettings

/** Кнопки в уведомлении о звонке: «В чёрный список» и «Доверять». */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val settings = AppSettings(context)
        when (intent.action) {
            ACTION_BLACKLIST -> settings.addToBlacklist(number)
            ACTION_TRUST -> settings.addToWhitelist(number)
        }
        NotificationManagerCompat.from(context).cancelAll()
    }

    companion object {
        const val ACTION_BLACKLIST = "ru.ivanivorontsov.nevedis.BLACKLIST"
        const val ACTION_TRUST = "ru.ivanivorontsov.nevedis.TRUST"
        const val EXTRA_NUMBER = "number"
    }
}
