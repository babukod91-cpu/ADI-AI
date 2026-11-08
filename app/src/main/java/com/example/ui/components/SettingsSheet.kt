package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AiEngineMode
import com.example.data.model.LanguageMode
import com.example.data.model.LlamaState
import com.example.data.repository.EngineId
import com.example.data.repository.EngineStatus
import com.example.service.AdiVoiceAccessibilityService
import com.example.ui.AssistantViewModel
import kotlinx.coroutines.delay

class SettingsActions(
    val openAccessibility: () -> Unit,
    val setFloatingOrb: (Boolean) -> Unit,
    val setWakeWord: (Boolean) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: AssistantViewModel, actions: SettingsActions, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val llama by vm.llama.collectAsStateWithLifecycle()
    val keyPresent by vm.keyPresent.collectAsStateWithLifecycle()
    val voice by vm.voiceStatus.collectAsStateWithLifecycle()
    val statuses by produceState<Map<EngineId, EngineStatus>>(emptyMap(), keyPresent, llama) { value = vm.engineStatuses() }
    var a11y by remember { mutableStateOf(AdiVoiceAccessibilityService.instance != null) }
    LaunchedEffect(Unit) { while (true) { a11y = AdiVoiceAccessibilityService.instance != null; delay(1000) } }
    var keyInput by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Section("AI brain")
            AiEngineMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.Top) {
                    RadioButton(selected = s.engineMode == mode, onClick = { vm.setEngine(mode) })
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("${mode.displayName}  \u00b7  ${mode.badge}", style = MaterialTheme.typography.titleSmall)
                        Text(mode.subtitle, style = MaterialTheme.typography.bodySmall)
                        val line = when (mode) {
                            AiEngineMode.AUTO -> "Gemini: ${statuses[EngineId.GEMINI]?.detail ?: "..."}  |  Llama: ${statuses[EngineId.LLAMA]?.detail ?: "..."}"
                            AiEngineMode.ADI_HYBRID -> statuses[EngineId.HYBRID]?.detail ?: "..."
                            AiEngineMode.LLAMA_3_2_3B -> statuses[EngineId.LLAMA]?.detail ?: "..."
                            AiEngineMode.GEMINI_CLOUD -> statuses[EngineId.GEMINI]?.detail ?: "..."
                        }
                        Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            SwitchRow("Prefer on-device (private) when both are ready", s.preferOnDevice) { v -> vm.update { it.copy(preferOnDevice = v) } }
            Text("If the chosen engine fails, ADI automatically falls back to the next one. Conversation context is kept across engines.", style = MaterialTheme.typography.bodySmall)

            Section("Gemini (cloud)")
            Text(if (keyPresent) "API key saved (encrypted on this device)." else "No API key. Get a free key at aistudio.google.com/apikey.", style = MaterialTheme.typography.bodySmall)
            if (!vm.secureStorageAvailable) Text("Secure storage unavailable on this device - key cannot be saved.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(keyInput, { keyInput = it }, label = { Text("Gemini API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = keyInput.isNotBlank(), onClick = { vm.saveKey(keyInput); keyInput = "" }) { Text("Save key") }
                if (keyPresent) OutlinedButton(onClick = { vm.saveKey(null) }) { Text("Remove key") }
            }
            OutlinedTextField(s.geminiModel, { v -> vm.update { it.copy(geminiModel = v.trim()) } }, label = { Text("Gemini model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Privacy: with Gemini, your messages (and images/screens you share) are sent to Google.", style = MaterialTheme.typography.bodySmall)

            Section("Llama 3.2 3B (offline)")
            val pct = (llama.downloadProgress * 100).toInt()
            Text("State: ${llama.state}" + if (llama.state == LlamaState.DOWNLOADING) "  $pct%" else "", style = MaterialTheme.typography.bodyMedium)
            Text("RAM ${llama.totalRamGb} GB total / ${llama.availableRamGb} GB free  \u00b7  storage ${llama.freeStorageGb} GB free  \u00b7  model ~${llama.modelSizeMb} MB", style = MaterialTheme.typography.bodySmall)
            llama.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (llama.state == LlamaState.DOWNLOADING) LinearProgressIndicator(progress = { llama.downloadProgress }, modifier = Modifier.fillMaxWidth())
            if (!vm.llamaNativeAvailable) Text("This build has no llama.cpp native runtime, so the model cannot run yet (see README, step 3). Download is disabled to avoid wasting 2 GB.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (llama.state) {
                    LlamaState.DOWNLOADING -> OutlinedButton(onClick = { vm.cancelLlama() }) { Text("Pause") }
                    LlamaState.DOWNLOADED, LlamaState.READY -> OutlinedButton(onClick = { vm.deleteLlama() }) { Text("Delete model") }
                    else -> Button(enabled = vm.llamaNativeAvailable && llama.isSupportedOnDevice, onClick = { vm.downloadLlama() }) { Text("Download (Wi-Fi recommended)") }
                }
            }

            Section("Language")
            SwitchRow("Detect language automatically for each message", s.autoLanguage) { v -> vm.update { it.copy(autoLanguage = v) } }
            Box {
                OutlinedButton(onClick = { langMenu = true }) { Text("Default: ${s.language.displayName} (${s.language.nativeName})") }
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    LanguageMode.entries.forEach { l -> DropdownMenuItem(text = { Text("${l.displayName}  \u00b7  ${l.nativeName}") }, onClick = { vm.setLanguage(l); langMenu = false }) }
                }
            }

            Section("Voice")
            SwitchRow("Continuous conversation (listen again after ADI speaks)", s.continuousMode) { v -> vm.update { it.copy(continuousMode = v) } }
            SwitchRow("\"Hey ADI\" wake word (background mic, visible notification)", s.wakeWordEnabled) { v -> vm.update { it.copy(wakeWordEnabled = v) }; actions.setWakeWord(v) }
            OutlinedTextField(s.wakeWord, { v -> vm.update { it.copy(wakeWord = v) } }, label = { Text("Wake phrase") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Speech rate ${"%.2f".format(s.speechRate)}", style = MaterialTheme.typography.labelMedium)
            Slider(s.speechRate, { v -> vm.update { it.copy(speechRate = v) } }, valueRange = 0.5f..1.5f)
            Text("Pitch ${"%.2f".format(s.speechPitch)}", style = MaterialTheme.typography.labelMedium)
            Slider(s.speechPitch, { v -> vm.update { it.copy(speechPitch = v) } }, valueRange = 0.8f..1.5f)
            Text("${voice.engineNote}${if (voice.activeVoiceName.isNotBlank()) "  (${voice.activeVoiceName})" else ""}", style = MaterialTheme.typography.bodySmall)

            Section("Phone control & screen AI")
            Text(if (a11y) "Accessibility service: ON" else "Accessibility service: OFF (needed for Back/Recents/Notifications, swipes, tapping, screen reading)", style = MaterialTheme.typography.bodyMedium)
            Text("ADI uses it only when you give a command. It never reads your screen in the background.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.openAccessibility) { Text("Open Accessibility settings") }
            SwitchRow("Allow ADI to look at my screen when I ask", s.screenConsent) { v -> vm.update { it.copy(screenConsent = v) } }
            SwitchRow("Floating ADI circle over other apps", s.floatingOrb) { v -> vm.update { it.copy(floatingOrb = v) }; actions.setFloatingOrb(v) }

            Section("Appearance")
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (v, label) ->
                    RadioButton(selected = s.themeMode == v, onClick = { vm.update { it.copy(themeMode = v) } }); Text(label)
                    Spacer(Modifier.width(8.dp))
                }
            }

            Section("Privacy")
            Text("Chats are stored only on this phone. Backups are disabled.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear conversation history") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Clear all conversations?") }, text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.c.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
}

@Composable private fun Section(t: String) {
    Spacer(Modifier.height(8.dp)); Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); HorizontalDivider()
}

@Composable private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, onChange)
    }
}
