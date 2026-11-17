package ru.ivanivorontsov.nevedis.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.ivanivorontsov.nevedis.R

object ScamNotifications {
    private const val CHANNEL_ID = "untrusted_call_alerts"

    fun showUntrustedCaller(context: Context, caller: String) {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Осторожно: новый номер")
            .setContentText("$caller не добавлен в доверенные. Не сообщайте коды и не переводите деньги.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Входящий звонок: $caller. Номер не добавлен в ваш локальный список доверенных. " +
                    "Это не означает, что звонящий мошенник. Не сообщайте коды из СМС и не переводите деньги под давлением."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(caller.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notifications may be disabled by the user. Call screening still returns a safe allow response.
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Предупреждения о незнакомых звонках",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ненавязчивые предупреждения о номерах, не добавленных в доверенные"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
