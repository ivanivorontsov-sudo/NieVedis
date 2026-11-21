package ru.ivanivorontsov.nevedis.call

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications

/**
 * Небольшое окно поверх звонка: «Включить локальную проверку разговора?».
 * Именно здесь, во время звонка, запрашивается разрешение на микрофон.
 */
class CallGuardActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAndClose() else {
            CallSession.onUserConsent(this, false)
            Toast.makeText(this, "Без микрофона проверка разговора не работает", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScamNotifications.cancelListenPrompt(this)
        if (!CallSession.isInCall(this)) { finish(); return }
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        if (auto && CallSession.micGranted(this)) { startAndClose(); return }
        setContent {
            MaterialTheme {
                Card(Modifier.padding(16.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Проверить разговор на мошенничество?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Речь распознаётся на телефоне, без интернета. Звук не записывается, текст стирается после звонка. " +
                                "Телефон слышит ваш голос, а собеседника — только по громкой связи: включите динамик."
                        )
                        Button(onClick = ::onYes, modifier = Modifier.fillMaxWidth()) { Text("Да, проверять этот звонок") }
                        OutlinedButton(onClick = { CallSession.onUserConsent(this@CallGuardActivity, false); finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Не сейчас")
                        }
                    }
                }
            }
        }
    }

    private fun onYes() {
        if (CallSession.micGranted(this)) startAndClose()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startAndClose() {
        // Сервис запускается, пока окно на экране, — это требование Android 14+.
        val ok = CallSession.onUserConsent(this, true) || CallSession.startListeningService(this)
        if (!ok) Toast.makeText(this, "Звонок уже завершён или Android не разрешил микрофон", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object { const val EXTRA_AUTO = "auto" }
}
