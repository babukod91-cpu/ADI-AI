package com.example

import android.Manifest
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AssistantMode
import com.example.service.FloatingOrbService
import com.example.ui.AssistantViewModel
import com.example.ui.components.*
import com.example.ui.screens.MainAssistantScreen
import com.example.ui.theme.AdiTheme
import com.example.voice.WakeWordService

/** Starts/stops the optional background services. Each shows a persistent notification (Android requirement). */
object ServiceControl {
    fun setWake(ctx: Context, on: Boolean) {
        val i = Intent(ctx, WakeWordService::class.java)
        if (on) ContextCompat.startForegroundService(ctx, i) else { ctx.stopService(i); AdiGraph.controller.stopAll() }
    }
    fun setOrb(ctx: Context, on: Boolean) {
        val i = Intent(ctx, FloatingOrbService::class.java)
        if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
    }
}

private fun overlayIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))

class MainActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.c.onMicGranted() else Toast.makeText(this, "Mic permission ke bina voice nahi chalegi - text se poochh sakte hain, ya Settings se allow karein.", Toast.LENGTH_LONG).show()
    }
    private val contacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.c.onContactsResult(it) }
    private val notif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val overlay = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) { vm.update { it.copy(floatingOrb = true) }; ServiceControl.setOrb(this, true) }
        else vm.update { it.copy(floatingOrb = false) }
    }
    private val wakeMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ServiceControl.setWake(this, true) else { vm.update { it.copy(wakeWordEnabled = false) }; Toast.makeText(this, "Hey ADI ke liye mic permission zaroori hai", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val handlers = ActivityHandlers(
            requestMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
            requestContacts = { contacts.launch(Manifest.permission.READ_CONTACTS) },
            openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            moveToBackground = { moveTaskToBack(true) }
        )
        val settingsActions = SettingsActions(
            openAccessibility = handlers.openAccessibility,
            setFloatingOrb = { on ->
                if (!on) ServiceControl.setOrb(this, false)
                else if (Settings.canDrawOverlays(this)) { notifyPermission(); ServiceControl.setOrb(this, true) }
                else { vm.update { it.copy(floatingOrb = false) }; overlay.launch(overlayIntent(this)) }
            },
            setWakeWord = { on ->
                if (!on) ServiceControl.setWake(this, false)
                else { notifyPermission(); if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ServiceControl.setWake(this, true) else wakeMic.launch(Manifest.permission.RECORD_AUDIO) }
            }
        )
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            AdiTheme(s.themeMode) { MainAssistantScreen(vm, handlers, settingsActions) }
        }
    }

    private fun notifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        val s = vm.settings.value
        if (s.floatingOrb && Settings.canDrawOverlays(this)) ServiceControl.setOrb(this, true)
        AdiGraph.llamaManager.refreshStatus()
    }
}

/** Compact assistant card shown when the floating ADI circle is tapped. */
class QuickAssistActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.c.onMicGranted() }
    private val contacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.c.onContactsResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val handlers = ActivityHandlers(
            requestMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
            requestContacts = { contacts.launch(Manifest.permission.READ_CONTACTS) },
            openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            moveToBackground = { },
            closeSelf = { finish() }
        )
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            AdiTheme(s.themeMode) { QuickCard(vm, handlers, onOpenMain = { startActivity(Intent(this, MainActivity::class.java)); finish() }, onClose = { vm.c.stopAll(); finish() }) }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.c.startListening()
    }
}

@Composable
private fun QuickCard(vm: AssistantViewModel, handlers: ActivityHandlers, onOpenMain: () -> Unit, onClose: () -> Unit) {
    val c = vm.c
    val mode by c.mode.collectAsStateWithLifecycle()
    val level by c.level.collectAsStateWithLifecycle()
    val partial by c.partial.collectAsStateWithLifecycle()
    val reply by c.lastReply.collectAsStateWithLifecycle()
    val pending by c.pending.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { c.sendText(text); text = "" } }
    ControllerEvents(c, handlers)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }, contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbVisualizer(mode, level, Modifier.size(110.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { c.toggleMic() })
                Text(if (partial.isNotBlank()) partial else when (mode) {
                    AssistantMode.LISTENING -> "Listening..."; AssistantMode.THINKING -> "Thinking..."; AssistantMode.SPEAKING -> "Speaking..."; else -> "Ask me anything" })
                if (reply.isNotBlank()) Text(reply, Modifier.heightIn(max = 160.dp), style = MaterialTheme.typography.bodyMedium)
                pending?.let { p ->
                    Text("${p.title}: ${p.details}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { c.confirmPending() }) { Text("Yes") }; OutlinedButton(onClick = { c.cancelPending() }) { Text("No") } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Type...") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                    Button(onClick = { if (text.isNotBlank()) send() else c.toggleMic() }) { Text(if (text.isNotBlank()) "\u27A4" else "\uD83C\uDFA4") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.askAboutScreenFromBubble() }) { Text("Explain my screen") }
                    TextButton(onClick = onOpenMain) { Text("Open ADI") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
