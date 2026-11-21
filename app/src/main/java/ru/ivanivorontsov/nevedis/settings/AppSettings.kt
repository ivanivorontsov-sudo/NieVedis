package ru.ivanivorontsov.nevedis.settings

import android.content.Context
import android.content.SharedPreferences
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine
import ru.ivanivorontsov.nevedis.screening.CallHistory
import ru.ivanivorontsov.nevedis.screening.normalizePhoneNumber

/** Все настройки и списки хранятся только в SharedPreferences на устройстве. */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var filterSettings: CallFilterEngine.Settings
        get() = CallFilterEngine.Settings(
            protection = enumOr(prefs.getString(KEY_PROTECTION, null), CallFilterEngine.Protection.STANDARD),
            warnUnknown = prefs.getBoolean(KEY_WARN_UNKNOWN, true),
            hiddenAction = enumOr(prefs.getString(KEY_HIDDEN_ACTION, null), CallFilterEngine.Action.WARN),
            blockInternational = prefs.getBoolean(KEY_BLOCK_INTL, false),
            contactsOnly = prefs.getBoolean(KEY_CONTACTS_ONLY, false),
            blockSpoofedOrgNumbers = prefs.getBoolean(KEY_BLOCK_SPOOFED, false)
        )
        set(v) {
            prefs.edit()
                .putString(KEY_PROTECTION, v.protection.name)
                .putBoolean(KEY_WARN_UNKNOWN, v.warnUnknown)
                .putString(KEY_HIDDEN_ACTION, v.hiddenAction.name)
                .putBoolean(KEY_BLOCK_INTL, v.blockInternational)
                .putBoolean(KEY_CONTACTS_ONLY, v.contactsOnly)
                .putBoolean(KEY_BLOCK_SPOOFED, v.blockSpoofedOrgNumbers)
                .apply()
        }

    val whitelist: Set<String> get() = prefs.getStringSet(KEY_TRUSTED_NUMBERS, emptySet()).orEmpty()
    val blacklist: Set<String> get() = prefs.getStringSet(KEY_BLACKLIST, emptySet()).orEmpty()

    fun addToWhitelist(number: String) {
        editSet(KEY_BLACKLIST) { it -= clean(number) }
        editSet(KEY_TRUSTED_NUMBERS) { it += clean(number) }
    }

    fun addToBlacklist(number: String) {
        editSet(KEY_TRUSTED_NUMBERS) { it -= clean(number) }
        editSet(KEY_BLACKLIST) { it += clean(number) }
    }

    fun removeFromWhitelist(number: String) = editSet(KEY_TRUSTED_NUMBERS) { it -= number }
    fun removeFromBlacklist(number: String) = editSet(KEY_BLACKLIST) { it -= number }

    var history: CallHistory
        get() = CallHistory.parse(prefs.getString(KEY_HISTORY, null))
        set(v) { prefs.edit().putString(KEY_HISTORY, v.serialize()).apply() }

    var smsFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_FILTER, true)
        set(v) { prefs.edit().putBoolean(KEY_SMS_FILTER, v).apply() }

    var messengerFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESSENGER_FILTER, true)
        set(v) { prefs.edit().putBoolean(KEY_MESSENGER_FILTER, v).apply() }

    var callListenEnabled: Boolean
        get() = prefs.getBoolean(KEY_LISTEN_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_LISTEN_ENABLED, v).apply() }

    var autoStartListen: Boolean
        get() = prefs.getBoolean(KEY_LISTEN_AUTO, false)
        set(v) { prefs.edit().putBoolean(KEY_LISTEN_AUTO, v).apply() }

    var revokeMicAfterCall: Boolean
        get() = prefs.getBoolean(KEY_REVOKE_AFTER, true)
        set(v) { prefs.edit().putBoolean(KEY_REVOKE_AFTER, v).apply() }

    private fun clean(number: String): String {
        val t = number.trim()
        return if (t.endsWith("*")) t.filter { it.isDigit() || it == '+' } + "*" else normalizePhoneNumber(t)
    }

    private fun editSet(key: String, block: (MutableSet<String>) -> Unit) {
        val set = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        block(set)
        set.removeAll { it.isBlank() || it == "*" }
        prefs.edit().putStringSet(key, set).apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val PREFS_NAME = "ne_vedis_local_settings"
        const val KEY_TRUSTED_NUMBERS = "trusted_numbers"
        const val KEY_BLACKLIST = "blacklist_numbers"
        const val KEY_HISTORY = "unknown_call_history"
        private const val KEY_PROTECTION = "protection"
        private const val KEY_WARN_UNKNOWN = "warn_unknown"
        private const val KEY_HIDDEN_ACTION = "hidden_action"
        private const val KEY_BLOCK_INTL = "block_international"
        private const val KEY_CONTACTS_ONLY = "contacts_only"
        private const val KEY_BLOCK_SPOOFED = "block_spoofed_org"
        private const val KEY_SMS_FILTER = "sms_filter"
        private const val KEY_MESSENGER_FILTER = "messenger_filter"
        private const val KEY_LISTEN_ENABLED = "call_listen_enabled"
        private const val KEY_LISTEN_AUTO = "call_listen_auto"
        private const val KEY_REVOKE_AFTER = "revoke_mic_after_call"
    }
}
