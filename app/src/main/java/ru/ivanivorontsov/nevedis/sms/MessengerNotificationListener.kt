package ru.ivanivorontsov.nevedis.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer
import ru.ivanivorontsov.nevedis.call.CallSession
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications
import ru.ivanivorontsov.nevedis.settings.AppSettings

/**
 * Необязательный фильтр сообщений мессенджеров (Telegram, WhatsApp, VK, MAX, Viber).
 * Видит только текст уведомлений, проверяет его на устройстве и ничего не хранит.
 */
class MessengerNotificationListener : NotificationListenerService() {
    private val recent = ArrayDeque<Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MESSENGERS) return
        if (!AppSettings(this).messengerFilterEnabled) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: return
        val key = (sbn.packageName + title + text).hashCode()
        if (key in recent) return
        recent.addLast(key)
        while (recent.size > 50) recent.removeFirst()
        val report = MessageAnalyzer.analyze(null, text, CallSession.isInCall(this))
        if (report.level != ScamPhraseAnalyzer.Level.LOW) {
            ScamNotifications.showMessageWarning(this, "Сообщение от ${title ?: "мессенджера"}", report)
        }
    }

    companion object {
        val MESSENGERS = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "com.whatsapp", "com.whatsapp.w4b", "com.vkontakte.android", "ru.oneme.app", "com.viber.voip"
        )
    }
}
