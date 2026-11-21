package ru.ivanivorontsov.nevedis.call

/**
 * Чистая логика «микрофон только во время звонка». Не зависит от Android и покрыта unit-тестами.
 * Микрофон может быть открыт только в состоянии OFFHOOK (идёт разговор). На IDLE — немедленная остановка.
 */
class CallMicLifecycle {
    enum class CallState { IDLE, RINGING, OFFHOOK }

    sealed class Command {
        /** Запустить локальное распознавание (разрешение уже есть, пользователь выбрал автозапуск). */
        object StartListening : Command()
        /** Спросить пользователя: показать окно/уведомление с кнопкой и запросом разрешения микрофона. */
        object AskUser : Command()
        /** Остановить распознавание, закрыть микрофон, стереть текст; при необходимости — отозвать разрешение. */
        data class StopListening(val revokePermission: Boolean) : Command()
        object None : Command()
    }

    var state: CallState = CallState.IDLE
        private set
    var listening: Boolean = false
        private set
    private var asked = false

    val canOpenMic: Boolean get() = state == CallState.OFFHOOK

    fun onCallState(
        newState: CallState,
        featureEnabled: Boolean,
        micGranted: Boolean,
        autoStart: Boolean,
        revokeAfterCall: Boolean
    ): Command {
        val previous = state
        state = newState
        return when (newState) {
            CallState.IDLE -> {
                val wasActive = listening || asked
                listening = false
                asked = false
                if (wasActive || previous == CallState.OFFHOOK) Command.StopListening(revokeAfterCall && wasActive) else Command.None
            }
            CallState.RINGING -> Command.None
            CallState.OFFHOOK -> when {
                !featureEnabled -> Command.None
                listening -> Command.None
                micGranted && autoStart -> { listening = true; Command.StartListening }
                asked -> Command.None
                else -> { asked = true; Command.AskUser }
            }
        }
    }

    /** Пользователь ответил на запрос. Запуск возможен только если звонок всё ещё идёт. */
    fun onUserConsent(granted: Boolean): Command {
        if (!granted || state != CallState.OFFHOOK) return Command.None
        if (listening) return Command.None
        listening = true
        return Command.StartListening
    }

    /** Сервис остановился сам (ошибка, пользователь нажал «Стоп»). */
    fun onListeningStopped() { listening = false }

    companion object {
        fun fromTelephonyExtra(extra: String?): CallState? = when (extra) {
            "IDLE" -> CallState.IDLE
            "RINGING" -> CallState.RINGING
            "OFFHOOK" -> CallState.OFFHOOK
            else -> null
        }
    }
}
