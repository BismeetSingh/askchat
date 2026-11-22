package com.askchat

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.askchat.backup.BackupException
import com.askchat.backup.BackupSync
import com.askchat.backup.Crypt15Decryptor
import com.askchat.data.ChatDatabase
import com.askchat.data.ChatImporter
import com.askchat.data.ChatSummary
import com.askchat.data.Settings
import com.askchat.rag.ClaudeService
import com.askchat.rag.LlmService
import com.askchat.rag.OpenAiService
import com.askchat.rag.RagEngine
import com.askchat.rag.RagResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val chats: List<ChatSummary> = emptyList(),
    val apiKey: String = "",
    val provider: String = "claude",
    val openAiKey: String = "",
    val openAiModel: String = "",
    val selfName: String = "",
    val senderSuggestions: List<String> = emptyList(),
    val busy: Boolean = false,
    val status: String? = null,
    val result: RagResult? = null,
    val error: String? = null,
    /** Text highlighted in another app via "AskChat"; consumed by the Ask screen. */
    val draftQuestion: String? = null,
    val backup: BackupUi = BackupUi(),
)

data class BackupUi(
    val keySaved: Boolean = false,
    val folderChosen: Boolean = false,
    val backupFound: Boolean = false,
    val backupModified: Long = 0,
    val lastSynced: Long = 0,
    /** False if only the Databases folder was granted — documents need the WhatsApp folder. */
    val documentsAccessible: Boolean = false,
    val status: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = ChatDatabase(app)
    private val importer = ChatImporter(app, db)
    private val settings = Settings(app)
    private val rag = RagEngine(db)
    private val backupSync = BackupSync(app, db)

    private val _state = MutableStateFlow(UiState(
            apiKey = settings.apiKey, selfName = settings.selfName,
            provider = settings.provider, openAiKey = settings.openAiKey, openAiModel = settings.openAiModel,
        ))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        // New nightly backup since last sync? Import it automatically on app open.
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { backupSync.hasNewerBackup() }) syncBackup()
        }
    }

    private fun refresh() = viewModelScope.launch {
        val (chats, senders) = withContext(Dispatchers.IO) { db.chats() to db.sendersByChatCount().map { it.first } }
        val backup = withContext(Dispatchers.IO) { backupUi() }
        _state.update { it.copy(chats = chats, senderSuggestions = senders.take(8), backup = backup.copy(status = it.backup.status)) }
    }

    private fun backupUi(): BackupUi {
        val found = backupSync.findBackup()
        return BackupUi(
            keySaved = backupSync.keyStore.hasKey,
            folderChosen = backupSync.folderUri != null,
            backupFound = found != null,
            backupModified = found?.modified ?: 0,
            documentsAccessible = backupSync.documentsAccessible,
            lastSynced = backupSync.lastSyncedBackupModified,
        )
    }

    private fun setBackupStatus(msg: String?) = _state.update { it.copy(backup = it.backup.copy(status = msg)) }

    fun saveBackupKey(hex: String) {
        try {
            backupSync.keyStore.save(Crypt15Decryptor.parseKey(hex))
            setBackupStatus("Key saved (encrypted with Android Keystore).")
        } catch (e: BackupException) {
            setBackupStatus(e.message)
        }
        refresh()
    }

    fun forgetBackupKey() {
        backupSync.keyStore.clear()
        setBackupStatus("Key removed.")
        refresh()
    }

    fun setBackupFolder(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        backupSync.folderUri = uri
        refresh()
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { backupSync.findBackup() }
            setBackupStatus(
                when {
                    found == null -> "No ${BackupSync.BACKUP_NAME} found there. Pick Android/media/com.whatsapp/WhatsApp."
                    !backupSync.documentsAccessible -> "Found backup, but documents need the WhatsApp folder (one level up)."
                    else -> "Found backup. Tap Sync now."
                }
            )
        }
    }

    fun syncBackup() = viewModelScope.launch {
        _state.update { it.copy(busy = true, backup = it.backup.copy(status = "Decrypting and importing backup… this can take a minute.")) }
        if (settings.selfName.isBlank()) settings.selfName = "Me"
        val self = settings.selfName
        val outcome = withContext(Dispatchers.IO) {
            runCatching { backupSync.sync(self) { p -> setBackupStatus(p) } }
        }
        val msg = outcome.fold(
            { r ->
                buildString {
                    append("Imported ${r.messages} messages from ${r.chats} chats")
                    append(", ${r.documents} shared documents")
                    if (r.documentsAccessible) append(" (text read from ${r.documentsWithText})")
                    append('.')
                    if (!r.documentsAccessible && r.documents > 0) {
                        append(" Choose the WhatsApp folder to also search inside documents.")
                    }
                }
            },
            { e -> e.message ?: e.javaClass.simpleName },
        )
        _state.update { it.copy(busy = false, selfName = settings.selfName, backup = it.backup.copy(status = msg)) }
        refresh()
    }

    fun importChats(uris: List<Uri>, subject: String? = null) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _state.update { it.copy(busy = true, status = "Importing…", error = null) }
        val messages = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                runCatching { importer.import(uri, subject.takeIf { uris.size == 1 }) }
                    .onSuccess { r ->
                        messages += "Imported “${r.chatName}” (${r.messageCount} messages)"
                        guessSelfName(r.chatName, r.senders)
                    }
                    .onFailure { e -> messages += "Failed: ${e.message}" }
            }
        }
        _state.update { it.copy(busy = false, status = messages.joinToString("\n"), selfName = settings.selfName) }
        refresh()
    }

    /**
     * Your name isn't marked in exports. In a 1:1 chat named "Rahul" with senders {Rahul, X}, X is you.
     * Across several chats, the sender present in every chat is you.
     */
    private fun guessSelfName(chatName: String, senders: Set<String>) {
        if (settings.selfName.isNotBlank()) return
        if (senders.size == 2) {
            val others = senders.filterNot { it.equals(chatName, ignoreCase = true) }
            if (others.size == 1) {
                settings.selfName = others.first(); return
            }
        }
        val ranked = db.sendersByChatCount()
        if (db.chats().size >= 2 && ranked.isNotEmpty() && ranked.first().second >= 2) {
            settings.selfName = ranked.first().first
        }
    }

    fun setDraftQuestion(text: String) {
        _state.update { it.copy(draftQuestion = text.trim().take(500), error = null) }
    }

    fun consumeDraftQuestion() {
        _state.update { it.copy(draftQuestion = null) }
    }

    fun deleteChat(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.deleteChat(id) }
        refresh()
    }

    fun saveProviderSettings(provider: String, claudeKey: String, openAiKey: String, openAiModel: String) {
        settings.provider = provider
        settings.apiKey = claudeKey
        settings.openAiKey = openAiKey
        settings.openAiModel = openAiModel
        _state.update {
            it.copy(
                provider = settings.provider, apiKey = settings.apiKey,
                openAiKey = settings.openAiKey, openAiModel = settings.openAiModel,
            )
        }
    }

    private fun llmOrNull(s: UiState): LlmService? = when (s.provider) {
        "openai" -> s.openAiKey.takeIf { it.isNotBlank() }
            ?.let { OpenAiService(it, s.openAiModel.ifBlank { OpenAiService.DEFAULT_MODEL }) }
        else -> s.apiKey.takeIf { it.isNotBlank() }?.let { ClaudeService(it) }
    }

    fun setSelfName(name: String) {
        settings.selfName = name
        _state.update { it.copy(selfName = settings.selfName) }
    }

    fun ask(question: String) = viewModelScope.launch {
        val s = _state.value
        when {
            question.isBlank() -> return@launch
            llmOrNull(s) == null -> {
                val which = if (s.provider == "openai") "OpenAI" else "Anthropic"
                _state.update { it.copy(error = "Add your $which API key in Settings first.") }; return@launch
            }
            s.chats.isEmpty() -> { _state.update { it.copy(error = "Import a chat first (Chats tab).") }; return@launch }
        }
        _state.update { it.copy(busy = true, error = null, result = null, status = null) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching { rag.ask(question.trim(), llmOrNull(s)!!, s.selfName) }
        }
        _state.update {
            it.copy(
                busy = false,
                result = outcome.getOrNull(),
                error = outcome.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
            )
        }
    }
}
