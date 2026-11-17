package ru.ivanivorontsov.nevedis

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer
import ru.ivanivorontsov.nevedis.screening.ScamCallScreeningService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen() }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ScamCallScreeningService.PREFS_NAME, Context.MODE_PRIVATE) }
    var message by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var trusted by remember { mutableStateOf(prefs.getStringSet(ScamCallScreeningService.KEY_TRUSTED_NUMBERS, emptySet()).orEmpty().toList().sorted()) }
    var report by remember { mutableStateOf<ScamPhraseAnalyzer.Report?>(null) }
    var status by remember { mutableStateOf("Анализ текста выполняется локально.") }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = if (it.resultCode == RESULT_OK) "Проверка входящих звонков включена." else "Роль проверки звонков не назначена."
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = if (it) "Уведомления разрешены." else "Уведомления отключены."
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Не ведись", style = MaterialTheme.typography.headlineLarge)
            Text("Помощник против телефонного мошенничества")
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Проверить фразу", style = MaterialTheme.typography.titleLarge)
                    Text("Вставьте сообщение или перескажите, что от вас требуют. Текст никуда не отправляется.")
                    OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), minLines = 4, label = { Text("Текст для анализа") })
                    Button(onClick = {
                        report = ScamPhraseAnalyzer.analyze(message)
                        if (report?.level == ScamPhraseAnalyzer.Level.HIGH) vibratePhone(context)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Проверить") }
                    report?.let { result ->
                        val heading = when (result.level) {
                            ScamPhraseAnalyzer.Level.HIGH -> "Высокий риск"
                            ScamPhraseAnalyzer.Level.CAUTION -> "Будьте осторожны"
                            ScamPhraseAnalyzer.Level.LOW -> "Явных маркеров не найдено"
                        }
                        Text("$heading · ${result.score}/100", style = MaterialTheme.typography.titleMedium)
                        result.findings.forEach { Text("• ${it.phrase}: ${it.reason}") }
                        Text(result.advice)
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Не ведись: $heading. ${result.advice}")
                            }
                            context.startActivity(Intent.createChooser(send, "Поделиться предупреждением"))
                        }) { Text("Поделиться предупреждением") }
                    }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Входящие звонки", style = MaterialTheme.typography.titleLarge)
                    Text("Можно включить предупреждения о номерах, которых нет в доверенном списке. Приложение не слышит собеседника и не блокирует звонки.")
                    Button(onClick = {
                        val manager = context.getSystemService(RoleManager::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                            roleRequest.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        } else status = "Роль проверки звонков недоступна на этом устройстве."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Включить проверку звонков") }
                    if (Build.VERSION.SDK_INT >= 33) {
                        TextButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Разрешить уведомления") }
                    }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Доверенные номера", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(number, { number = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Номер телефона") })
                    Button(onClick = {
                        val clean = number.filter(Char::isDigit)
                        if (clean.length < 5) status = "Введите корректный номер."
                        else {
                            trusted = (trusted + clean).distinct().sorted()
                            prefs.edit().putStringSet(ScamCallScreeningService.KEY_TRUSTED_NUMBERS, trusted.toSet()).apply()
                            number = ""
                            status = "Номер сохранён только на этом устройстве."
                        }
                    }) { Text("Добавить") }
                    trusted.forEach { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item)
                            TextButton(onClick = {
                                trusted = trusted - item
                                prefs.edit().putStringSet(ScamCallScreeningService.KEY_TRUSTED_NUMBERS, trusted.toSet()).apply()
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Детектор эвристический: возможны ошибки. Незнакомый номер сам по себе не означает мошенничество. При сомнении завершите разговор и перезвоните по официальному номеру.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun vibratePhone(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    else @Suppress("DEPRECATION") vibrator.vibrate(250)
}
