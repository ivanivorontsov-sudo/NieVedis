package ru.ivanivorontsov.nevedis.call

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Специальная возможность «Не ведись». Нужна только для трёх вещей:
 * 1) Android разрешает обычному приложению слышать микрофон во время звонка, только если у него
 *    включена спецвозможность (см. «Sharing audio input» в документации Android);
 * 2) открыть окно запуска проверки, когда начинается разговор;
 * 3) показать крупное предупреждение поверх экрана звонка.
 * Содержимое экрана не читается (canRetrieveWindowContent=false), события не анализируются.
 */
class GuardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var modeListener: Any? = null

    override fun onServiceConnected() {
        instance = this
        if (Build.VERSION.SDK_INT >= 31) {
            // Звонки через мессенджеры (VoIP) не дают событий телефонии — следим за режимом аудио.
            val am = getSystemService(AudioManager::class.java)
            val l = AudioManager.OnModeChangedListener { mode ->
                val inVoip = mode == AudioManager.MODE_IN_COMMUNICATION
                val telephonyIdle = CallSession.lifecycle.state == CallMicLifecycle.CallState.IDLE
                if (inVoip && telephonyIdle) CallSession.onCallState(this, CallMicLifecycle.CallState.OFFHOOK)
                else if (mode == AudioManager.MODE_NORMAL && CallSession.lifecycle.state == CallMicLifecycle.CallState.OFFHOOK &&
                    !isTelephonyOffhook()) CallSession.onCallState(this, CallMicLifecycle.CallState.IDLE)
            }
            am.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        }
    }

    private fun isTelephonyOffhook(): Boolean =
        getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let {
                getSystemService(AudioManager::class.java).removeOnModeChangedListener(it)
            }
        }
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun show(alert: LiveTranscriptDetector.Alert) {
        removeOverlay()
        val wm = getSystemService(WindowManager::class.java)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            setBackgroundColor(if (alert.high) Color.rgb(180, 35, 24) else Color.rgb(154, 103, 0))
            addView(TextView(context).apply {
                text = alert.title
                setTextColor(Color.WHITE)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = alert.details + "\n\nНажмите, чтобы скрыть."
                setTextColor(Color.WHITE)
                textSize = 16f
            })
            setOnClickListener { removeOverlay() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        try {
            wm.addView(box, lp)
            overlay = box
            handler.postDelayed({ removeOverlay() }, 20_000)
        } catch (e: Exception) {
            Log.w("NeVedisA11y", "overlay failed", e)
        }
    }

    private fun removeOverlay() {
        overlay?.let { v -> try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {} }
        overlay = null
    }

    companion object {
        @Volatile private var instance: GuardAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        /** Открывает окно запуска проверки. Без спецвозможности Android не даёт открыть окно из фона. */
        fun launchGuard(context: Context, auto: Boolean): Boolean {
            val svc = instance ?: return false
            return try {
                svc.startActivity(
                    Intent(svc, CallGuardActivity::class.java)
                        .putExtra(CallGuardActivity.EXTRA_AUTO, auto)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                )
                true
            } catch (e: Exception) {
                Log.w("NeVedisA11y", "cannot open guard", e)
                false
            }
        }

        fun showOverlayAlert(alert: LiveTranscriptDetector.Alert) {
            val svc = instance ?: return
            svc.handler.post { svc.show(alert) }
        }

        fun hideOverlay() {
            val svc = instance ?: return
            svc.handler.post { svc.removeOverlay() }
        }
    }
}
