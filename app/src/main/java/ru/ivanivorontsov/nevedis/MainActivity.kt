package ru.ivanivorontsov.nevedis

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer
import ru.ivanivorontsov.nevedis.call.GuardAccessibilityService
import ru.ivanivorontsov.nevedis.call.VoskModel
import ru.ivanivorontsov.nevedis.notifications.ScamNotifications
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine
import ru.ivanivorontsov.nevedis.screening.normalizePhoneNumber
import ru.ivanivorontsov.nevedis.settings.AppSettings
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NeVedisScreen(
                tick = refreshTick.intValue,
                actions = ScreenActions(
                    enableCallScreening = ::requestCallScreeningRole,
                    screeningEnabled = ::screeningRoleHeld,
                    requestPermission = { p -> permissionLauncher.launch(p) },
                    granted = ::granted,
                    openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    openAppDetails = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    },
                    openNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    notificationAccess = ::notificationListenerEnabled,
                    vibrate = { ScamNotifications.vibrate(this, true) }
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() { refreshTick.intValue++ }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun screeningRoleHeld(): Boolean =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    private fun requestCallScreeningRole() {
        val manager = getSystemService(RoleManager::class.java)
        if (manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

    private fun notificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(this, ru.ivanivorontsov.nevedis.sms.MessengerNotificationListener::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }
}

class ScreenActions(
    val enableCallScreening: () -> Unit,
    val screeningEnabled: () -> Boolean,
    val requestPermission: (String) -> Unit,
    val granted: (String) -> Boolean,
    val openAccessibility: () -> Unit,
    val openAppDetails: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val notificationAccess: () -> Boolean,
    val vibrate: () -> Unit
)

private val Red = Color(0xFFB42318)
private val Green = Color(0xFF18794E)

@Composable
private fun NeVedisScreen(tick: Int, actions: ScreenActions) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var filter by remember { mutableStateOf(settings.filterSettings) }
    var listenEnabled by remember { mutableStateOf(settings.callListenEnabled) }
    var autoStart by remember { mutableStateOf(settings.autoStartListen) }
    var revokeAfter by remember { mutableStateOf(settings.revokeMicAfterCall) }
    var smsFilter by remember { mutableStateOf(settings.smsFilterEnabled) }
    var messengerFilter by remember { mutableStateOf(settings.messengerFilterEnabled) }
    var modelReady by remember { mutableStateOf(VoskModel.isReady(context)) }
    var modelBusy by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ScamPhraseAnalyzer.Report?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    fun updateFilter(f: CallFilterEngine.Settings) { filter = f; settings.filterSettings = f }

    // tick меняется при возвращении в приложение — статусы разрешений перечитываются.
    val roleHeld = remember(tick) { actions.screeningEnabled() }
    val phoneStateOk = remember(tick) { actions.granted(Manifest.permission.READ_PHONE_STATE) }
    val a11yOn = remember(tick) { GuardAccessibilityService.isEnabled }
    val smsOk = remember(tick) { actions.granted(Manifest.permission.RECEIVE_SMS) }
    val contactsOk = remember(tick) { actions.granted(Manifest.permission.READ_CONTACTS) }
    val listenerOk = remember(tick) { actions.notificationAccess() }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Red, error = Red)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("НЕ ВЕДИСЬ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Локальный помощник против телефонного обмана. Без интернета: всё проверяется на телефоне.", style = MaterialTheme.typography.bodyLarge)

                Section("Защита входящих звонков") {
                    Status(roleHeld, if (roleHeld) "Фильтр звонков включён" else "Фильтр звонков выключен")
                    if (!roleHeld) {
                        Button(onClick = actions.enableCallScreening, modifier = Modifier.fillMaxWidth()) { Text("Включить защиту звонков") }
                        Text("Android попросит назначить приложение сервисом определения и фильтрации звонков.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Уровень защиты", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CallFilterEngine.Protection.SOFT to "Мягкий",
                            CallFilterEngine.Protection.STANDARD to "Обычный",
                            CallFilterEngine.Protection.STRICT to "Строгий"
                        ).forEach { (p, label) ->
                            FilterChip(selected = filter.protection == p, onClick = { updateFilter(filter.copy(protection = p)) }, label = { Text(label) })
                        }
                    }
                    Text(
                        when (filter.protection) {
                            CallFilterEngine.Protection.SOFT -> "Только предупреждения, звонки не трогаются."
                            CallFilterEngine.Protection.STANDARD -> "Предупреждения; очень подозрительные звонки (подмена номера, спутниковые коды) — без звука."
                            CallFilterEngine.Protection.STRICT -> "Очень подозрительные звонки сбрасываются, подозрительные — без звука."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    SwitchRow("Предупреждать о незнакомых номерах", filter.warnUnknown) { updateFilter(filter.copy(warnUnknown = it)) }
                    Text("Скрытые номера", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CallFilterEngine.Action.WARN to "Предупредить",
                            CallFilterEngine.Action.SILENCE to "Без звука",
                            CallFilterEngine.Action.BLOCK to "Сбросить"
                        ).forEach { (a, label) ->
                            FilterChip(selected = filter.hiddenAction == a, onClick = { updateFilter(filter.copy(hiddenAction = a)) }, label = { Text(label) })
                        }
                    }
                    SwitchRow("Сбрасывать звонки из-за границы", filter.blockInternational) { updateFilter(filter.copy(blockInternational = it)) }
                    SwitchRow("Звонки «с номера банка» — без звука (их часто подделывают)", filter.blockSpoofedOrgNumbers) {
                        updateFilter(filter.copy(blockSpoofedOrgNumbers = it))
                    }
                    SwitchRow("Только контакты: остальные без звука (в строгом режиме — сброс)", filter.contactsOnly) {
                        updateFilter(filter.copy(contactsOnly = it))
                        if (it && !contactsOk) actions.requestPermission(Manifest.permission.READ_CONTACTS)
                    }
                    Text(
                        "Также учитываются: несуществующие коды +7, 8-800 и платные 8-809, спутниковые коды, номера Казахстана, " +
                            "провал проверки номера оператором, повторные звонки и серии с похожих номеров, ночные звонки.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Section("Проверка разговора во время звонка") {
                    Text(
                        "Во время звонка телефон распознаёт речь прямо на устройстве (Vosk, без интернета) и громко предупреждает, " +
                            "если звучат фразы мошенников. Звук не записывается, текст стирается после звонка."
                    )
                    Text(
                        "Честно: Android не даёт обычным приложениям звук собеседника. Микрофон слышит ваш голос, а собеседника — " +
                            "только по громкой связи. Включайте динамик, если звонок кажется странным.",
                        color = Red, style = MaterialTheme.typography.bodyMedium
                    )
                    SwitchRow("Предлагать проверку при каждом звонке", listenEnabled) {
                        listenEnabled = it; settings.callListenEnabled = it
                        if (it && !phoneStateOk) actions.requestPermission(Manifest.permission.READ_PHONE_STATE)
                    }
                    if (listenEnabled) {
                        Status(phoneStateOk, if (phoneStateOk) "Состояние звонка: доступно" else "Нужно разрешение «Телефон» (только состояние звонка)")
                        if (!phoneStateOk) OutlinedButton(onClick = { actions.requestPermission(Manifest.permission.READ_PHONE_STATE) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Разрешить состояние звонка")
                        }
                        Status(a11yOn, if (a11yOn) "Спецвозможность включена" else "Спецвозможность «Не ведись» выключена")
                        if (!a11yOn) {
                            Text(
                                "Без неё Android отдаёт приложению тишину вместо микрофона во время звонка. Включите: Спецвозможности → " +
                                    "«Не ведись — проверка разговора». Если пункт серый: О приложении → ⋮ → «Разрешить ограниченные настройки».",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = actions.openAccessibility, modifier = Modifier.weight(1f)) { Text("Спецвозможности") }
                                OutlinedButton(onClick = actions.openAppDetails, modifier = Modifier.weight(1f)) { Text("О приложении") }
                            }
                        }
                        SwitchRow("Запускать сразу, без вопроса (если микрофон уже разрешён)", autoStart) { autoStart = it; settings.autoStartListen = it }
                        SwitchRow("Отзывать разрешение микрофона после звонка (Android 13+)", revokeAfter) { revokeAfter = it; settings.revokeMicAfterCall = it }
                        Text(
                            "Разрешение на микрофон спрашивается во время звонка. Выбирайте «Только в этот раз» — тогда Android сам заберёт его.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = {
                                modelBusy = true
                                thread {
                                    val ok = runCatching { VoskModel.prepare(context) }.isSuccess
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        modelBusy = false; modelReady = ok
                                        Toast.makeText(context, if (ok) "Офлайн-модель готова" else "Не удалось распаковать модель", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !modelBusy && !modelReady,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (modelReady) "Офлайн-модель речи готова ✓" else if (modelBusy) "Распаковываю модель…" else "Подготовить офлайн-модель заранее") }
                    }
                }

                Section("Проверка СМС и сообщений") {
                    SwitchRow("Проверять входящие СМС", smsFilter && smsOk) {
                        smsFilter = it; settings.smsFilterEnabled = it
                        if (it && !smsOk) actions.requestPermission(Manifest.permission.RECEIVE_SMS)
                    }
                    Text(
                        "Ищет поддельные ссылки (двойники сайтов банков и Госуслуг, сокращатели, punycode), «банк» с обычного номера, " +
                            "просьбы перезвонить и фразы из базы. Особо предупреждает, если код пришёл прямо во время разговора. " +
                            "СМС не блокируются и не сохраняются — только предупреждение.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    SwitchRow("Проверять сообщения мессенджеров (Telegram, WhatsApp, VK, MAX, Viber)", messengerFilter && listenerOk) {
                        messengerFilter = it; settings.messengerFilterEnabled = it
                        if (it && !listenerOk) actions.openNotificationAccess()
                    }
                    Text("Нужен доступ к уведомлениям. Проверяется только текст уведомления, на устройстве.", style = MaterialTheme.typography.bodySmall)
                }

                Section("Проверка подозрительной фразы") {
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
                            if (report?.level == ScamPhraseAnalyzer.Level.HIGH) actions.vibrate()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = transcript.isNotBlank()
                    ) { Text("Проверить фразу") }
                    report?.let { RiskReport(it) }
                    if (report != null) {
                        OutlinedButton(
                            onClick = {
                                val text = "Предупреждение от «Не ведись». Уровень риска: ${report!!.level}.\n${report!!.advice}"
                                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                                context.startActivity(Intent.createChooser(intent, "Отправить предупреждение"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Поделиться предупреждением") }
                    }
                    Text(
                        "База: ${ScamPhraseAnalyzer.databasePhraseCount} фраз в ${ScamPhraseAnalyzer.databaseCategoryCount} сценариях. " +
                            "Учитываются словоформы, опечатки, латинские буквы-двойники и сочетания признаков.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                NumberListCard(
                    title = "Доверенные номера",
                    hint = "Звонки с них не проверяются. Хранится только на этом устройстве.",
                    initial = settings.whitelist,
                    onAdd = settings::addToWhitelist,
                    onRemove = settings::removeFromWhitelist
                )
                NumberListCard(
                    title = "Чёрный список",
                    hint = "Звонки с этих номеров сбрасываются. Можно маску: 7495123* — все номера с этого начала.",
                    initial = settings.blacklist,
                    onAdd = settings::addToBlacklist,
                    onRemove = settings::removeFromBlacklist
                )

                OutlinedButton(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth()) { Text("Что приложение может и чего не может") }
                Text(
                    "Важно: отсутствие найденных маркеров не гарантирует безопасность. При сомнении завершите разговор и перезвоните по официальному номеру.",
                    style = MaterialTheme.typography.bodySmall
                )
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
                    "• Звук собеседника в обычном сотовом звонке Android сторонним приложениям не отдаёт. Приложение слышит только микрофон: " +
                        "ваш голос и собеседника по громкой связи.\n" +
                        "• Распознавание — офлайн-модель Vosk, точность ниже, чем у облачных сервисов; шум и тихая речь мешают.\n" +
                        "• Звук не записывается и не сохраняется, текст держится в памяти только до конца звонка. Разрешения на интернет у приложения нет.\n" +
                        "• Фильтр звонков смотрит только на номер. Подделанный номер выглядит как настоящий — поэтому любой звонок «из банка» " +
                        "лучше завершить и перезвонить самому.\n" +
                        "• СМС не блокируются (это может только приложение СМС по умолчанию), показывается предупреждение.\n" +
                        "• Это подсказчик, а не гарантия: возможны ложные срабатывания и пропуски."
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Понятно") } }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Status(ok: Boolean, text: String) {
    Text((if (ok) "✓ " else "✗ ") + text, color = if (ok) Green else Red, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberListCard(
    title: String,
    hint: String,
    initial: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val items = remember { mutableStateListOf<String>().apply { addAll(initial.sorted()) } }
    var input by remember { mutableStateOf("") }
    Section(title) {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Номер телефона") })
        val valid = input.trim().endsWith("*") && input.count(Char::isDigit) >= 3 || normalizePhoneNumber(input).length >= 3
        Button(
            onClick = {
                onAdd(input)
                val shown = if (input.trim().endsWith("*")) input.trim().filter { it.isDigit() || it == '+' } + "*" else normalizePhoneNumber(input)
                if (shown !in items) items.add(shown)
                input = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = valid
        ) { Text("Добавить") }
        if (items.isEmpty()) Text("Пока список пуст.", style = MaterialTheme.typography.bodyMedium)
        items.toList().forEach { number ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(number, modifier = Modifier.weight(1f))
                TextButton(onClick = { items.remove(number); onRemove(number) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить номер") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RiskReport(report: ScamPhraseAnalyzer.Report) {
    val (label, color) = when (report.level) {
        ScamPhraseAnalyzer.Level.HIGH -> "ВЫСОКИЙ РИСК" to Red
        ScamPhraseAnalyzer.Level.CAUTION -> "НУЖНА ОСТОРОЖНОСТЬ" to Color(0xFF9A6700)
        ScamPhraseAnalyzer.Level.LOW -> "ЯВНЫХ МАРКЕРОВ МАЛО" to Green
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = color, fontWeight = FontWeight.Black)
            Text("Индекс совпадений: ${report.score}/100")
            report.findings.forEach { finding -> Text("• ${finding.phrase}: ${finding.reason}", style = MaterialTheme.typography.bodySmall) }
            Text(report.advice, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Suppress("unused")
private fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
