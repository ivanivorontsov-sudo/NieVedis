package ru.ivanivorontsov.nevedis.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications

/** Screens caller metadata only. Android does not expose ordinary cellular-call audio to this app. */
class ScamCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val rawNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
        val normalized = normalizePhoneNumber(rawNumber)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val trusted = normalized.isNotBlank() && prefs.getStringSet(KEY_TRUSTED_NUMBERS, emptySet())
            .orEmpty().contains(normalized)

        if (!trusted) {
            ScamNotifications.showUntrustedCaller(
                context = this,
                caller = rawNumber.ifBlank { "Скрытый номер" }
            )
        }

        // Never reject calls automatically in this initial version.
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }

    companion object {
        const val PREFS_NAME = "ne_vedis_local_settings"
        const val KEY_TRUSTED_NUMBERS = "trusted_numbers"
    }
}
