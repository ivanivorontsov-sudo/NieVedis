package ru.ivanivorontsov.nevedis.call

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications
import kotlin.concurrent.thread

/**
 * Локальное распознавание речи во время звонка (Vosk, офлайн).
 * - Работает только пока идёт звонок; каждые 2 секунды проверяет это и сам останавливается.
 * - Аудио не сохраняется: звук идёт сразу в распознаватель, текст держится в памяти (последние ~80 слов).
 * - У приложения нет разрешения на интернет, поэтому отправить что-либо оно физически не может.
 */
class CallListenService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private val detector = LiveTranscriptDetector()
    private var notInCallTicks = 0
    @Volatile private var stopped = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (CallSession.isInCall(this@CallListenService)) notInCallTicks = 0 else notInCallTicks++
            if (notInCallTicks >= 2) { stopSelf(); return }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val notification = ScamNotifications.listeningNotification(this, "Готовлю офлайн-распознавание…")
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ScamNotifications.LISTEN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ScamNotifications.LISTEN_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            CallSession.lifecycle.onListeningStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CallSession.isInCall(this) || !CallSession.micGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (speech == null) startRecognition()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 2000)
        return START_NOT_STICKY
    }

    private fun startRecognition() {
        thread(name = "vosk-init") {
            try {
                val root = VoskModel.prepare(this)
                if (stopped) return@thread
                val m = Model(root.absolutePath)
                val r = Recognizer(m, SAMPLE_RATE)
                val s = SpeechService(r, SAMPLE_RATE)
                handler.post {
                    if (stopped) { s.shutdown(); r.close(); m.close(); return@post }
                    model = m; recognizer = r; speech = s
                    s.startListening(this)
                    ScamNotifications.updateListening(this, "Слушаю разговор локально. Ничего не записывается и не отправляется.")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "recognition init failed", e)
                handler.post {
                    ScamNotifications.updateListening(this, "Не удалось запустить распознавание: ${e.message ?: e.javaClass.simpleName}")
                    handler.postDelayed({ stopSelf() }, 4000)
                }
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) = handle(hypothesis, "partial", false)
    override fun onResult(hypothesis: String?) = handle(hypothesis, "text", true)
    override fun onFinalResult(hypothesis: String?) = handle(hypothesis, "text", true)

    private fun handle(json: String?, key: String, final: Boolean) {
        if (json.isNullOrBlank() || stopped) return
        val text = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        if (text.isBlank()) return
        val alert = detector.onText(text, final, System.currentTimeMillis()) ?: return
        ScamNotifications.showLiveAlert(this, alert)
        GuardAccessibilityService.showOverlayAlert(alert)
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "recognizer error", exception)
        ScamNotifications.updateListening(this, "Микрофон недоступен: ${exception?.message ?: "ошибка"}. Включите спецвозможность «Не ведись».")
    }

    override fun onTimeout() { stopSelf() }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        speech = null; recognizer = null; model = null
        detector.clear() // стираем всё услышанное
        CallSession.lifecycle.onListeningStopped()
        GuardAccessibilityService.hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NeVedisListen"
        const val SAMPLE_RATE = 16000f
        const val ACTION_STOP = "ru.ivanivorontsov.nevedis.STOP_LISTEN"
    }
}
