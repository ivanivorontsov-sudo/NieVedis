package ru.ivanivorontsov.nevedis.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications
import ru.ivanivorontsov.nevedis.settings.AppSettings

/** Связывает события звонка с жизненным циклом микрофона. Один экземпляр на процесс. */
object CallSession {
    private const val TAG = "NeVedisCall"
    val lifecycle = CallMicLifecycle()

    @Volatile var lastOffhookAt: Long = 0L
        private set

    fun isInCall(context: Context): Boolean {
        if (lifecycle.state == CallMicLifecycle.CallState.OFFHOOK) return true
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun onCallState(context: Context, state: CallMicLifecycle.CallState) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        if (state == CallMicLifecycle.CallState.OFFHOOK) lastOffhookAt = System.currentTimeMillis()
        val cmd = lifecycle.onCallState(
            newState = state,
            featureEnabled = settings.callListenEnabled,
            micGranted = micGranted(app),
            autoStart = settings.autoStartListen,
            revokeAfterCall = settings.revokeMicAfterCall
        )
        execute(app, cmd)
    }

    /** Вызывается из видимого окна CallGuardActivity после согласия пользователя. */
    @Synchronized
    fun onUserConsent(context: Context, granted: Boolean): Boolean {
        val cmd = lifecycle.onUserConsent(granted)
        return when {
            cmd == CallMicLifecycle.Command.StartListening -> startListeningService(context)
            granted && lifecycle.listening -> startListeningService(context)
            else -> false
        }
    }

    private fun execute(context: Context, cmd: CallMicLifecycle.Command) {
        Log.d(TAG, "command $cmd")
        when (cmd) {
            CallMicLifecycle.Command.StartListening, CallMicLifecycle.Command.AskUser -> {
                // Android 14+ запрещает запускать сервис микрофона из фона. Поэтому всегда идём через
                // видимое окно CallGuardActivity: оно запрашивает разрешение (если нужно) и запускает сервис.
                // Если включена спецвозможность «Не ведись», окно открывается само; иначе — по нажатию на уведомление.
                val auto = cmd == CallMicLifecycle.Command.StartListening
                if (!GuardAccessibilityService.launchGuard(context, auto)) {
                    lifecycle.onListeningStopped()
                    ScamNotifications.showListenPrompt(context)
                }
            }
            is CallMicLifecycle.Command.StopListening -> {
                ScamNotifications.cancelListenPrompt(context)
                context.stopService(Intent(context, CallListenService::class.java))
                if (cmd.revokePermission) revokeMicPermission(context)
            }
            CallMicLifecycle.Command.None -> Unit
        }
    }

    /** На Android 13+ просим систему отозвать разрешение микрофона, когда процесс приложения завершится. */
    fun revokeMicPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && micGranted(context)) {
            try {
                context.revokeSelfPermissionsOnKill(listOf(Manifest.permission.RECORD_AUDIO))
            } catch (e: Exception) {
                Log.w(TAG, "revoke failed", e)
            }
        }
    }

    /** Прямой запуск сервиса — только из видимого окна приложения. */
    fun startListeningService(context: Context): Boolean {
        if (!lifecycle.canOpenMic && !isInCall(context)) return false
        return try {
            ContextCompat.startForegroundService(context, Intent(context, CallListenService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot start listening", e)
            lifecycle.onListeningStopped()
            false
        }
    }
}
