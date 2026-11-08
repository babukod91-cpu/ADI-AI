package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AssistantMode
import com.example.ui.AssistantViewModel
import com.example.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScreen(vm: AssistantViewModel, handlers: ActivityHandlers, settingsActions: SettingsActions) {
    val c = vm.c
    val mode by c.mode.collectAsStateWithLifecycle()
    val level by c.level.collectAsStateWithLifecycle()
    val partial by c.partial.collectAsStateWithLifecycle()
    val reply by c.lastReply.collectAsStateWithLifecycle()
    val engine by c.engineUsed.collectAsStateWithLifecycle()
    val emotion by c.emotion.collectAsStateWithLifecycle()
    val busy by c.busy.collectAsStateWithLifecycle()
    val pending by c.pending.collectAsStateWithLifecycle()
    val choice by c.contactChoice.collectAsStateWithLifecycle()
    val cam by c.showCameraAi.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { c.sendText(text); text = "" } }

    ControllerEvents(c, handlers)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ADI AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showChat = true }) { Text("\uD83D\uDCAC", fontSize = 20.sp) }
                TextButton(onClick = { showSettings = true }) { Text("\u2699", fontSize = 22.sp) }
            }
            if (engine.isNotBlank()) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("via $engine", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(0.5f))
            Box(Modifier.size(250.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { c.toggleMic() }, contentAlignment = Alignment.Center) {
                OrbVisualizer(mode, level, Modifier.fillMaxSize())
                Text(emotion.emoji, fontSize = 38.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    busy || mode == AssistantMode.THINKING -> "Thinking..."
                    mode == AssistantMode.LISTENING -> "Listening..."
                    mode == AssistantMode.SPEAKING -> "Speaking - tap the orb to stop"
                    mode == AssistantMode.ERROR -> "Something went wrong"
                    settings.wakeWordEnabled -> "Tap the orb or say \"${settings.wakeWord}\""
                    else -> "Tap the orb and speak, or type below"
                }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
            )
            if (partial.isNotBlank()) Text(partial, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(0.4f))
            if (reply.isNotBlank()) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), modifier = Modifier.fillMaxWidth()) {
                Text(reply, Modifier.padding(16.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Ask ADI...") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                if (text.isNotBlank()) Button(onClick = { send() }) { Text("\u27A4") }
                else Button(onClick = { c.toggleMic() }) { Text(if (mode == AssistantMode.LISTENING) "\u23F9" else "\uD83C\uDFA4", fontSize = 18.sp) }
            }
        }
    }

    pending?.let { ConfirmationDialog(it, onConfirm = { c.confirmPending() }, onCancel = { c.cancelPending() }) }
    choice?.let { ContactChoiceDialog(it, onPick = { i -> c.chooseContact(i) }, onCancel = { c.cancelContactChoice() }) }
    if (cam) CameraAiDialog(c, onClose = { c.closeCameraAi() })
    if (showChat) ChatSheet(messages, onClear = { c.clearHistory() }, onDismiss = { showChat = false })
    if (showSettings) SettingsSheet(vm, settingsActions, onDismiss = { showSettings = false })
}
