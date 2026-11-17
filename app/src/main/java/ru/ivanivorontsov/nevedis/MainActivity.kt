package ru.ivanivorontsov.nevedis

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer
import ru.ivanivorontsov.nevedis.screening.ScamCallScreeningService
import ru.ivanivorontsov.nevedis.screening.normalizePhoneNumber

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { NeVedisScreen(onEnableCallScreening = ::requestCallScreeningRole, onVibrate = ::vibrateWarning) }
    }

    private fun requestCallScreeningRole() {
        val manager = getSystemService(RoleManager::class.java)
        if (manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        ) {
            roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

    private fun vibrateWarning() {
        val pattern = longArrayOf(0, 220, 90, 220, 90, 420)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}

@Composable
private fun NeVedisScreen(onEnableCallScreening: () -> Unit, onVibrate: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ScamCallScreeningService.PREFS_NAME, 0) }
    val trusted = remember {
        mutableStateListOf<String>().apply {
            addAll(
                prefs.getStringSet(ScamCallScreeningService.KEY_TRUSTED_NUMBERS, emptySet())
                    .orEmpty()
                    .map(::normalizePhoneNumber)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
            )
        }
    }
    var numberInput by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ScamPhraseAnalyzer.Report?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = androidx.compose.ui.graphics.Color(0xFFB42318),
        error = androidx.compose.ui.graphics.Color(0xFFB42318)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("НЕ ВЕДИСЬ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Локальный помощник против телефонного обмана", style = MaterialTheme.typography.bodyLarge)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Защита входящих звонков", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Приложение предупредит о номере, которого нет в вашем локальном списке доверенных. Оно не объявляет незнакомца мошенником и не блокирует звонки автоматически.")
                        Button(onClick = onEnableCallScreening, modifier = Modifier.fillMaxWidth()) {
                            Text("Включить защиту звонков")
                        }
                        Text("Для этого Android попросит назначить приложение помощником определения и фильтрации звонков.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Проверка подозрительной фразы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Вставьте текст сообщения или фразу, которую услышали. Анализ выполняется на устройстве, без отправки текста в сеть.")
                        OutlinedTextField(
                            value = transcript,
                            onValueChange = { transcript = it; report = null },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text("Например: «Переведите деньги на безопасный счёт…»") }
                        )
                        Button(
                            onClick = {
                                report = ScamPhraseAnalyzer.analyze(transcript)
                                if (report?.level == ScamPhraseAnalyzer.Level.HIGH) onVibrate()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = transcript.isNotBlank()
                        ) { Text("Проверить фразу") }
                        report?.let { RiskReport(it) }
                        if (report != null) {
                            OutlinedButton(
                                onClick = {
                                    val text = buildString {
                                        append("Предупреждение от «Не ведись». Уровень риска: ${report!!.level}.\n")
                                        append(report!!.advice)
                                    }
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Отправить предупреждение"))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Поделиться предупреждением") }
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Мои доверенные номера", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Номера хранятся только в настройках приложения на этом устройстве.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = numberInput,
                            onValueChange = { numberInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Номер телефона") }
                        )
                        Button(
                            onClick = {
                                val normalized = normalizePhoneNumber(numberInput)
                                if (normalized.length >= 6 && normalized !in trusted) {
                                    trusted.add(normalized)
                                    prefs.edit().putStringSet(ScamCallScreeningService.KEY_TRUSTED_NUMBERS, trusted.toSet()).apply()
                                    numberInput = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = normalizePhoneNumber(numberInput).length >= 6
                        ) { Text("Добавить номер") }
                        if (trusted.isEmpty()) Text("Пока список пуст.", style = MaterialTheme.typography.bodyMedium)
                        trusted.forEach { number ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(number, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    trusted.remove(number)
                                    prefs.edit().putStringSet(ScamCallScreeningService.KEY_TRUSTED_NUMBERS, trusted.toSet()).apply()
                                }) {
                                    androidx.compose.material3.Icon(Icons.Default.Delete, contentDescription = "Удалить номер")
                                }
                            }
                            Divider()
                        }
                    }
                }

                OutlinedButton(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Что приложение может и чего не может")
                }
                Text("Важно: отсутствие найденных маркеров не гарантирует безопасность. При сомнении завершите разговор и перезвоните по официальному номеру.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Честно о возможностях") },
            text = {
                Text(
                    "Android не разрешает обычным сторонним приложениям читать звук стандартного сотового звонка: доступ к аудио вызова защищён системным разрешением. Поэтому эта версия не подслушивает собеседника, не записывает звонки и не делает вид, что распознаёт мошенника в реальном времени. " +
                        "Она проверяет введённый текст локально и показывает предупреждение о незнакомом номере. Автоматическая блокировка отключена."
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Понятно") } }
        )
    }
}

@Composable
private fun RiskReport(report: ScamPhraseAnalyzer.Report) {
    val (label, color) = when (report.level) {
        ScamPhraseAnalyzer.Level.HIGH -> "ВЫСОКИЙ РИСК" to androidx.compose.ui.graphics.Color(0xFFB42318)
        ScamPhraseAnalyzer.Level.CAUTION -> "НУЖНА ОСТОРОЖНОСТЬ" to androidx.compose.ui.graphics.Color(0xFF9A6700)
        ScamPhraseAnalyzer.Level.LOW -> "ЯВНЫХ МАРКЕРОВ МАЛО" to androidx.compose.ui.graphics.Color(0xFF18794E)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = color, fontWeight = FontWeight.Black)
            Text("Индекс совпадений: ${report.score}/100")
            report.findings.forEach { finding ->
                Text("• ${finding.phrase}: ${finding.reason}", style = MaterialTheme.typography.bodySmall)
            }
            Text(report.advice, fontWeight = FontWeight.SemiBold)
        }
    }
}
