package ru.ivanivorontsov.nevedis.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.ivanivorontsov.nevedis.MainActivity
import ru.ivanivorontsov.nevedis.R
import ru.ivanivorontsov.nevedis.call.CallGuardActivity
import ru.ivanivorontsov.nevedis.call.CallListenService
import ru.ivanivorontsov.nevedis.call.LiveTranscriptDetector
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine
import ru.ivanivorontsov.nevedis.sms.MessageAnalyzer

object ScamNotifications {
    private const val CHANNEL_CALLS = "untrusted_call_alerts"
    private const val CHANNEL_LIVE = "live_scam_alerts"
    private const val CHANNEL_LISTEN = "call_listening"
    private const val CHANNEL_MESSAGES = "message_alerts"
    const val LISTEN_ID = 4100
    private const val PROMPT_ID = 4101
    private const val LIVE_ID = 4102

    fun showCallDecision(context: Context, caller: String, normalized: String, decision: CallFilterEngine.Decision) {
        ensureChannels(context)
        val title = when (decision.action) {
            CallFilterEngine.Action.BLOCK -> "Звонок заблокирован"
            CallFilterEngine.Action.SILENCE -> "Подозрительный звонок (без звука)"
            else -> if (decision.risk >= 45) "Осторожно: подозрительный звонок" else "Осторожно: новый номер"
        }
        val body = "$caller. ${decision.summary}. Не сообщайте коды и не переводите деньги."
        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + "\nЭто подсказка, а не приговор: настоящий звонок тоже может выглядеть необычно."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        if (normalized.isNotEmpty()) {
            builder.addAction(0, "В чёрный список", listAction(context, NotificationActionReceiver.ACTION_BLACKLIST, normalized))
            builder.addAction(0, "Доверять", listAction(context, NotificationActionReceiver.ACTION_TRUST, normalized))
        }
        notify(context, ("call" + normalized + caller).hashCode(), builder.build())
    }

    fun showListenPrompt(context: Context) {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 7, Intent(context, CallGuardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Проверить этот разговор?")
            .setContentText("Нажмите, чтобы включить локальное распознавание на время звонка")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, "Включить", pi)
            .build()
        notify(context, PROMPT_ID, n)
    }

    fun cancelListenPrompt(context: Context) = NotificationManagerCompat.from(context).cancel(PROMPT_ID)

    fun listeningNotification(context: Context, text: String): Notification {
        ensureChannels(context)
        val stop = PendingIntent.getService(
            context, 8, Intent(context, CallListenService::class.java).setAction(CallListenService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LISTEN)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("«Не ведись» слушает разговор на устройстве")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Остановить", stop)
            .build()
    }

    fun updateListening(context: Context, text: String) = notify(context, LISTEN_ID, listeningNotification(context, text))

    fun showLiveAlert(context: Context, alert: LiveTranscriptDetector.Alert) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(alert.title)
            .setContentText(alert.details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.details))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, LIVE_ID, n)
        vibrate(context, alert.high)
    }

    fun showMessageWarning(context: Context, from: String, report: MessageAnalyzer.Report) {
        ensureChannels(context)
        val high = report.level == ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer.Level.HIGH
        val title = if (high) "Похоже на мошенничество: $from" else "Подозрительное сообщение: $from"
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(report.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(report.summary + "\nНе переходите по ссылкам и никому не называйте коды."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, (from + report.summary).hashCode(), n)
        if (high) vibrate(context, true)
    }

    fun vibrate(context: Context, strong: Boolean) {
        val pattern = if (strong) longArrayOf(0, 400, 150, 400, 150, 800) else longArrayOf(0, 250, 120, 250)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) } catch (_: Exception) {}
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun listAction(context: Context, action: String, number: String): PendingIntent = PendingIntent.getBroadcast(
        context, (action + number).hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_NUMBER, number),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Уведомления запрещены пользователем — фильтры продолжают работать без них.
        }
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, "Предупреждения о звонках", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Подозрительные и заблокированные входящие"
            enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_LIVE, "Тревога во время разговора", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Громкое предупреждение, если в разговоре звучат фразы мошенников"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_LISTEN, "Проверка разговора", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Показывается, пока микрофон используется во время звонка"
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Подозрительные сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "СМС и сообщения мессенджеров с признаками обмана"
        })
    }
}
