package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AssistantController
import com.example.core.UiEvent
import com.example.data.local.ConversationEntity
import com.example.data.model.ContactChoice
import com.example.data.model.PendingConfirmation
import com.example.data.model.VisionScanResult

/** Actions only an Activity can perform (permission launchers, task moves). */
class ActivityHandlers(
    val requestMic: () -> Unit,
    val requestContacts: () -> Unit,
    val openAccessibility: () -> Unit,
    val moveToBackground: () -> Unit,
    val closeSelf: () -> Unit = {}
)

/** Collects controller events and shows the consent / rationale dialogs. Shared by the main screen and the quick card. */
@Composable
fun ControllerEvents(c: AssistantController, h: ActivityHandlers) {
    val ctx = LocalContext.current
    var micRationale by remember { mutableStateOf(false) }
    var screenConsent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        c.events.collect { e ->
            when (e) {
                UiEvent.RequestMic -> micRationale = true
                UiEvent.RequestContacts -> h.requestContacts()
                UiEvent.OpenAccessibilitySettings -> h.openAccessibility()
                UiEvent.AskScreenConsent -> screenConsent = true
                UiEvent.MoveToBackground -> h.moveToBackground()
                UiEvent.CloseQuickAssist -> h.closeSelf()
                is UiEvent.Toast -> android.widget.Toast.makeText(ctx, e.msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (micRationale) AlertDialog(
        onDismissRequest = { micRationale = false },
        title = { Text("Microphone access") },
        text = { Text("ADI listens only while you tap the orb (or if you turn on \"Hey ADI\" - then a visible notification stays on). Audio is processed by Android's speech recogniser; ADI does not record or store it.") },
        confirmButton = { TextButton(onClick = { micRationale = false; h.requestMic() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { micRationale = false }) { Text("Not now") } }
    )
    if (screenConsent) AlertDialog(
        onDismissRequest = { screenConsent = false },
        title = { Text("Let ADI look at your screen?") },
        text = { Text("Only when you ask (\"explain this screen\"), ADI reads the visible text and takes one screenshot. With Gemini on, that screenshot and text are sent to Google. Banking/password apps are skipped and secure screens are blocked by Android. You can turn this off in Settings.") },
        confirmButton = { TextButton(onClick = { screenConsent = false; c.grantScreenConsent() }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { screenConsent = false }) { Text("Deny") } }
    )
}

@Composable
fun ConfirmationDialog(p: PendingConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel, title = { Text(p.title) }, text = { Text(p.details) },
    confirmButton = { Button(onClick = onConfirm) { Text("Yes, do it") } },
    dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
)

@Composable
fun ContactChoiceDialog(c: ContactChoice, onPick: (Int) -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel, title = { Text("Which \"${c.query}\"?") },
    text = { Column { c.matches.forEachIndexed { i, m -> TextButton(onClick = { onPick(i) }, modifier = Modifier.fillMaxWidth()) { Text("${i + 1}. ${m.name}  (${m.number})") } } } },
    confirmButton = {}, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(messages: List<ConversationEntity>, onClear: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear history") }
            }
            val state = rememberLazyListState()
            LaunchedEffect(messages.size) { if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex) }
            if (messages.isEmpty()) Text("No messages yet.", Modifier.padding(24.dp))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(messages, key = { it.id }) { m ->
                    val mine = m.sender == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(shape = RoundedCornerShape(16.dp), color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 300.dp),
                                color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun CameraAiDialog(c: AssistantController, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val result by c.visionResult.collectAsStateWithLifecycle()
    val busy by c.visionBusy.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> if (b != null) c.analyzeImage(b, question) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) decodeUri(ctx, u)?.let { c.analyzeImage(it, question) } }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Camera AI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it }, label = { Text("Question (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { take.launch(null) }) { Text("Take photo") }
                    OutlinedButton(onClick = { pick.launch("image/*") }) { Text("Choose image") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.let { VisionResultView(it) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun VisionResultView(r: VisionScanResult) {
    val clip = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        r.thumbnailBitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp))) }
        Text(r.title, style = MaterialTheme.typography.titleMedium)
        if (r.category.isNotBlank()) Text(r.category, style = MaterialTheme.typography.labelMedium)
        if (r.confidence > 0f) Text("Confidence: ${(r.confidence * 100).toInt()}% (model estimate)", style = MaterialTheme.typography.labelSmall)
        SelectionContainer { Text(r.detailedExplanation) }
        r.keyAttributes.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
        if (r.extractedText.isNotBlank()) {
            Text("Text found:", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(r.extractedText, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { clip.setText(AnnotatedString(r.extractedText)) }) { Text("Copy text") }
        }
        Text("Source: ${r.source}", style = MaterialTheme.typography.labelSmall)
    }
}

private fun decodeUri(ctx: Context, uri: Uri): Bitmap? = try {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
} catch (e: Exception) { null }
