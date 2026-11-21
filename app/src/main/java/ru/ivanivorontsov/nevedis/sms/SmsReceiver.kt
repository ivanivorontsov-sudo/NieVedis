package ru.ivanivorontsov.nevedis.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer
import ru.ivanivorontsov.nevedis.call.CallSession
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications
import ru.ivanivorontsov.nevedis.settings.AppSettings

/**
 * Читает входящие СМС только для проверки на устройстве. Сообщения не сохраняются, не изменяются
 * и не блокируются (это может только приложение СМС по умолчанию) — показывается предупреждение.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppSettings(context).smsFilterEnabled) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        parts.groupBy { it.displayOriginatingAddress }.forEach { (sender, msgs) ->
            val body = msgs.joinToString("") { it.displayMessageBody.orEmpty() }
            val report = MessageAnalyzer.analyze(sender, body, CallSession.isInCall(context))
            if (report.level != ScamPhraseAnalyzer.Level.LOW) {
                ScamNotifications.showMessageWarning(context, "СМС от ${sender ?: "неизвестного"}", report)
            }
        }
    }
}
