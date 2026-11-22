package com.askchat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.askchat.backup.BackupSync
import com.askchat.data.MessageRow
import com.askchat.rag.OpenAiService
import com.askchat.ui.theme.AskChatTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AskChatTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** WhatsApp → Export chat → share to this app; or highlighted text → "AskChat". */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let(vm::setDraftQuestion)
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        // "Export with media" shares the .txt alongside photos etc. — keep only chat files.
        val chatFiles = uris.filter { uri ->
            val type = contentResolver.getType(uri).orEmpty()
            type.startsWith("text/") || type.contains("zip") ||
                uri.toString().endsWith(".txt", true) || uri.toString().endsWith(".zip", true)
        }.ifEmpty { uris.take(1) }
        vm.importChats(chatFiles, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (state.chats.isEmpty()) 1 else 0) }
    val tabs = listOf("Ask", "Chats", "Settings")
    LaunchedEffect(state.draftQuestion) { if (state.draftQuestion != null) tab = 0 }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> AskScreen(state, vm::ask, vm::consumeDraftQuestion)
                1 -> ChatsScreen(state, vm)
                else -> SettingsScreen(state, vm)
            }
        }
    }
}

@Composable
private fun AskScreen(state: UiState, onAsk: (String) -> Unit, onDraftConsumed: () -> Unit) {
    var question by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    // Pre-fill from highlighted text, cursor at the end, keyboard up — user finishes the question.
    LaunchedEffect(state.draftQuestion) {
        val draft = state.draftQuestion ?: return@LaunchedEffect
        val text = "$draft "
        question = TextFieldValue(text, selection = TextRange(text.length))
        onDraftConsumed()
        runCatching { focus.requestFocus() } // field may not be laid out yet; pre-fill still works
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Ask about your chats") },
                placeholder = { Text("What's the password I sent to Rahul last week?") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAsk(question.text) }),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onAsk(question.text) }, enabled = !state.busy) { Text("Ask") }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.result?.let { r ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(r.answer, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                val p = r.plan
                val parts = buildList {
                    if (p.contacts.isNotEmpty()) add("contacts: ${p.contacts.joinToString()}")
                    if (p.direction != "any") add("sent by: ${p.direction}")
                    if (p.startDate.isNotBlank() || p.endDate.isNotBlank()) add("dates: ${p.startDate} → ${p.endDate}")
                    if (p.keywords.isNotEmpty()) add("keywords: ${p.keywords.joinToString()}")
                }
                Text(
                    "Searched ${parts.joinToString(" · ")}\n${r.messagesSent} matching messages were sent to Claude.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.cited.isNotEmpty()) {
                item { Text("Source messages", fontWeight = FontWeight.SemiBold) }
                items(r.cited, key = { it.id }) { MessageCard(it) }
            }
        }
    }
}

@Composable
private fun MessageCard(m: MessageRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${m.sender} · ${m.chatName} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.ts))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChatsScreen(state: UiState, vm: AppViewModel) {
    val onDelete: (Long) -> Unit = vm::deleteChat
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importChats(uris, null)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackupCard(state, vm) }
        item {
            Text("Or import single chats", style = MaterialTheme.typography.titleMedium)
            Text(
                "In WhatsApp open a chat → ⋮ → More → Export chat → Without media → share to AskChat. " +
                    "Re-exporting a chat replaces the old copy. Or pick exported .txt/.zip files:",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { picker.launch(arrayOf("text/plain", "application/zip")) }, enabled = !state.busy) {
                Text("Pick export files")
            }
        }
        state.status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        items(state.chats, key = { it.id }) { chat ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(chat.name, fontWeight = FontWeight.SemiBold)
                        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                        Text(
                            "${chat.messageCount} messages · ${fmt.format(Date(chat.firstTs))} – ${fmt.format(Date(chat.lastTs))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onDelete(chat.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var provider by remember(state.provider) { mutableStateOf(state.provider) }
    var claudeKey by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var openAiKey by remember(state.openAiKey) { mutableStateOf(state.openAiKey) }
    var openAiModel by remember(state.openAiModel) { mutableStateOf(state.openAiModel) }
    var name by remember(state.selfName) { mutableStateOf(state.selfName) }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI provider", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = provider == "claude", onClick = { provider = "claude" }, label = { Text("Claude") })
            FilterChip(selected = provider == "openai", onClick = { provider = "openai" }, label = { Text("OpenAI") })
        }
        if (provider == "openai") {
            OutlinedTextField(
                value = openAiKey, onValueChange = { openAiKey = it },
                label = { Text("OpenAI API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = openAiModel, onValueChange = { openAiModel = it },
                label = { Text("OpenAI model") },
                placeholder = { Text(OpenAiService.DEFAULT_MODEL) },
                supportingText = { Text("Leave empty for ${OpenAiService.DEFAULT_MODEL}.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = claudeKey, onValueChange = { claudeKey = it },
                label = { Text("Anthropic API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Your name in chats") },
            supportingText = { Text("Used to tell messages you sent from ones you received.") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (state.senderSuggestions.isNotEmpty()) {
            Text("Senders in your chats:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.senderSuggestions.take(3).forEach { s -> AssistChip(onClick = { name = s }, label = { Text(s) }) }
            }
        }
        Button(onClick = {
            vm.saveProviderSettings(provider, claudeKey, openAiKey, openAiModel)
            vm.setSelfName(name)
        }) { Text("Save") }
        Text(
            "Privacy: chats are stored only on this phone. For each question, the AI provider gets the question, " +
                "your chat names, and only the handful of messages that matched locally. App data is excluded from backups.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupCard(state: UiState, vm: AppViewModel) {
    val b = state.backup
    var key by rememberSaveable { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setBackupFolder)
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WhatsApp backup — all chats", style = MaterialTheme.typography.titleMedium)
            Text(
                "Needs WhatsApp → Settings → Chats → Chat backup → End-to-end encrypted backup → " +
                    "\"Use 64-digit encryption key instead\". Imports all chats plus shared documents (PDF, Word, Excel, " +
                    "PowerPoint, text). Re-imports automatically when a newer nightly backup exists.",
                style = MaterialTheme.typography.bodySmall,
            )

            // 1. Key
            if (b.keySaved) {
                Row {
                    Text("1. ✓ 64-digit key saved", Modifier.weight(1f))
                    TextButton(onClick = vm::forgetBackupKey) { Text("Forget") }
                }
            } else {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("1. 64-digit backup key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.saveBackupKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            }

            // 2. Folder
            Row {
                Text(
                    if (b.backupFound && !b.documentsAccessible) "2. ✓ Backup found — re-choose the WhatsApp folder to include documents"
                    else if (b.backupFound) "2. ✓ Backup + documents found (${fmt.format(Date(b.backupModified))})"
                    else if (b.folderChosen) "2. Folder chosen, but no backup file in it"
                    else "2. Allow access to WhatsApp's folder (Android/media/com.whatsapp/WhatsApp)",
                    Modifier.weight(1f),
                )
            }
            TextButton(onClick = { folderPicker.launch(BackupSync.INITIAL_FOLDER) }) {
                Text(if (b.folderChosen) "Change folder" else "Choose WhatsApp folder")
            }

            // 3. Contacts (optional)
            TextButton(onClick = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) }) {
                Text("3. Allow contacts (optional — shows names instead of numbers)")
            }

            // 4. Sync
            Button(onClick = vm::syncBackup, enabled = !state.busy && b.keySaved && b.backupFound) { Text("Sync now") }
            if (b.lastSynced > 0) {
                Text("Last imported backup: ${fmt.format(Date(b.lastSynced))}", style = MaterialTheme.typography.bodySmall)
            }
            b.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
