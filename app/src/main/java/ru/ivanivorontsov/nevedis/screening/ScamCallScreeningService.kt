package ru.ivanivorontsov.nevedis.screening

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications
import ru.ivanivorontsov.nevedis.settings.AppSettings
import java.util.Calendar

/**
 * Фильтр входящих по метаданным (номер, скрытость, проверка оператора, история).
 * Звук обычного звонка этому сервису Android не передаёт.
 */
class ScamCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (Build.VERSION.SDK_INT >= 29 && callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        val settings = AppSettings(this)
        val rawNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
        val hidden = rawNumber.isBlank() || callDetails.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val verificationFailed = Build.VERSION.SDK_INT >= 30 &&
            callDetails.callerNumberVerificationStatus == android.telecom.Connection.VERIFICATION_STATUS_FAILED
        val now = System.currentTimeMillis()
        val info = CallFilterEngine.CallInfo(
            rawNumber = rawNumber,
            hidden = hidden,
            inContacts = if (hidden) false else isInContacts(rawNumber),
            verificationFailed = verificationFailed,
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            now = now
        )
        val history = settings.history
        val decision = CallFilterEngine.decide(info, settings.filterSettings, settings.whitelist, settings.blacklist, history)
        if (decision.action != CallFilterEngine.Action.ALLOW && info.normalized.isNotEmpty()) {
            history.record(info.normalized, now)
            settings.history = history
        }

        val builder = CallResponse.Builder()
        when (decision.action) {
            CallFilterEngine.Action.BLOCK -> builder.setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(false)
            CallFilterEngine.Action.SILENCE -> if (Build.VERSION.SDK_INT >= 29) builder.setSilenceCall(true)
            else -> Unit
        }
        respondToCall(callDetails, builder.build())

        if (decision.action != CallFilterEngine.Action.ALLOW) {
            ScamNotifications.showCallDecision(this, rawNumber.ifBlank { "Скрытый номер" }, info.normalized, decision)
        }
    }

    /** Проверка «есть ли номер в контактах» — только при выданном READ_CONTACTS, без копирования контактов. */
    private fun isInContacts(number: String): Boolean? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFS_NAME = AppSettings.PREFS_NAME
        const val KEY_TRUSTED_NUMBERS = AppSettings.KEY_TRUSTED_NUMBERS
    }
}
