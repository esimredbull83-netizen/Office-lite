package com.example.ui.doc

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.WorkspaceRepository
import com.example.domain.DocumentFormatter
import com.example.domain.TextStats
import com.example.domain.UndoRedoStack
import com.example.drive.DriveAuthOutcome
import com.example.drive.DriveBackupCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SaveStatus {
    SAVED,
    SAVING,
    UNSAVED
}

data class DocEditorUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val textFieldValue: TextFieldValue = TextFieldValue(""),
    val stats: TextStats = TextStats(0, 0, 1),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val isPreviewMode: Boolean = false,
    val showFindReplace: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val matchCase: Boolean = false,
    val matchRanges: List<IntRange> = emptyList(),
    val activeMatchIndex: Int = -1,
    val isBackingUp: Boolean = false
)

class DocEditorViewModel(
    application: Application,
    private val fileId: String
) : AndroidViewModel(application) {

    private val repository = WorkspaceRepository.getInstance(application)
    private val driveCoordinator = DriveBackupCoordinator.getInstance(application)
    private val undoRedo = UndoRedoStack<TextFieldValue>(maxCapacity = 80)

    private val _uiState = MutableStateFlow(DocEditorUiState())
    val uiState: StateFlow<DocEditorUiState> = _uiState.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _consentRequest = MutableSharedFlow<IntentSenderRequest>()
    val consentRequest: SharedFlow<IntentSenderRequest> = _consentRequest.asSharedFlow()

    private var autoSaveJob: Job? = null
    private var lastSnapshotTime: Long = 0L

    init {
        loadDocument()
    }

    private fun loadDocument() {
        viewModelScope.launch {
            val (meta, content) = repository.readTextContent(fileId)
            val tfv = TextFieldValue(content, TextRange(content.length))
            _uiState.value = DocEditorUiState(
                isLoading = false,
                title = meta?.title ?: "Document.md",
                textFieldValue = tfv,
                stats = DocumentFormatter.computeStats(content),
                saveStatus = SaveStatus.SAVED
            )
        }
    }

    fun onValueChange(newValue: TextFieldValue) {
        val current = _uiState.value.textFieldValue
        val textChanged = newValue.text != current.text
        if (textChanged) {
            val now = System.currentTimeMillis()
            val lengthDelta = kotlin.math.abs(newValue.text.length - current.text.length)
            // Coalesce rapid single-character typing into snapshots on pause (> 700ms) or big edits
            if (now - lastSnapshotTime > 700L || lengthDelta > 4) {
                undoRedo.pushUndo(current)
            }
            lastSnapshotTime = now
            scheduleAutoSave()
        }

        val ranges = if (_uiState.value.showFindReplace && _uiState.value.findQuery.isNotEmpty()) {
            DocumentFormatter.findMatchRanges(
                newValue.text,
                _uiState.value.findQuery,
                _uiState.value.matchCase
            )
        } else {
            emptyList()
        }

        _uiState.update { state ->
            state.copy(
                textFieldValue = newValue,
                stats = if (textChanged) DocumentFormatter.computeStats(newValue.text) else state.stats,
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                saveStatus = if (textChanged) SaveStatus.UNSAVED else state.saveStatus,
                matchRanges = ranges,
                activeMatchIndex = if (ranges.isEmpty()) -1 else state.activeMatchIndex.coerceIn(0, ranges.lastIndex)
            )
        }
    }

    fun applyBold() = applyTransform { DocumentFormatter.toggleInlineWrap(it, "**", "**", "bold") }
    fun applyItalic() = applyTransform { DocumentFormatter.toggleInlineWrap(it, "*", "*", "italic") }
    fun applyUnderline() = applyTransform { DocumentFormatter.toggleInlineWrap(it, "<u>", "</u>", "underlined") }
    fun applyHeading() = applyTransform { DocumentFormatter.cycleHeading(it) }
    fun applyBulletList() = applyTransform { DocumentFormatter.toggleLinePrefix(it, bullet = true) }
    fun applyNumberedList() = applyTransform { DocumentFormatter.toggleLinePrefix(it, bullet = false) }

    private fun applyTransform(transform: (TextFieldValue) -> TextFieldValue) {
        val current = _uiState.value.textFieldValue
        undoRedo.pushUndo(current)
        lastSnapshotTime = System.currentTimeMillis()
        val updated = transform(current)
        _uiState.update { state ->
            state.copy(
                textFieldValue = updated,
                stats = DocumentFormatter.computeStats(updated.text),
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                saveStatus = SaveStatus.UNSAVED,
                isPreviewMode = false
            )
        }
        scheduleAutoSave()
    }

    fun undo() {
        val prev = undoRedo.undo(_uiState.value.textFieldValue) ?: return
        _uiState.update { state ->
            state.copy(
                textFieldValue = prev,
                stats = DocumentFormatter.computeStats(prev.text),
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                saveStatus = SaveStatus.UNSAVED
            )
        }
        scheduleAutoSave()
    }

    fun redo() {
        val next = undoRedo.redo(_uiState.value.textFieldValue) ?: return
        _uiState.update { state ->
            state.copy(
                textFieldValue = next,
                stats = DocumentFormatter.computeStats(next.text),
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                saveStatus = SaveStatus.UNSAVED
            )
        }
        scheduleAutoSave()
    }

    fun togglePreviewMode() {
        _uiState.update { it.copy(isPreviewMode = !it.isPreviewMode) }
    }

    fun toggleFindReplace() {
        _uiState.update { state ->
            val nextOpen = !state.showFindReplace
            val ranges = if (nextOpen && state.findQuery.isNotEmpty()) {
                DocumentFormatter.findMatchRanges(state.textFieldValue.text, state.findQuery, state.matchCase)
            } else {
                emptyList()
            }
            state.copy(
                showFindReplace = nextOpen,
                matchRanges = ranges,
                activeMatchIndex = if (ranges.isNotEmpty()) 0 else -1
            )
        }
    }

    fun updateFindQuery(query: String) {
        val state = _uiState.value
        val ranges = DocumentFormatter.findMatchRanges(state.textFieldValue.text, query, state.matchCase)
        val firstRange = ranges.firstOrNull()
        val updatedTfv = if (firstRange != null) {
            state.textFieldValue.copy(selection = TextRange(firstRange.first, firstRange.last + 1))
        } else {
            state.textFieldValue
        }
        _uiState.update {
            it.copy(
                findQuery = query,
                matchRanges = ranges,
                activeMatchIndex = if (ranges.isNotEmpty()) 0 else -1,
                textFieldValue = updatedTfv
            )
        }
    }

    fun updateReplaceQuery(query: String) {
        _uiState.update { it.copy(replaceQuery = query) }
    }

    fun toggleMatchCase() {
        val state = _uiState.value
        val nextCase = !state.matchCase
        val ranges = DocumentFormatter.findMatchRanges(state.textFieldValue.text, state.findQuery, nextCase)
        _uiState.update {
            it.copy(
                matchCase = nextCase,
                matchRanges = ranges,
                activeMatchIndex = if (ranges.isNotEmpty()) 0 else -1
            )
        }
    }

    fun jumpToNextMatch(forward: Boolean) {
        val state = _uiState.value
        val ranges = state.matchRanges
        if (ranges.isEmpty()) return
        val nextIdx = if (forward) {
            (state.activeMatchIndex + 1) % ranges.size
        } else {
            if (state.activeMatchIndex <= 0) ranges.lastIndex else state.activeMatchIndex - 1
        }
        val range = ranges[nextIdx]
        _uiState.update {
            it.copy(
                activeMatchIndex = nextIdx,
                isPreviewMode = false,
                textFieldValue = it.textFieldValue.copy(
                    selection = TextRange(range.first, range.last + 1)
                )
            )
        }
    }

    fun replaceCurrentMatch() {
        val state = _uiState.value
        val ranges = state.matchRanges
        val idx = state.activeMatchIndex
        if (ranges.isEmpty() || idx !in ranges.indices) return

        val currentTfv = state.textFieldValue
        undoRedo.pushUndo(currentTfv)
        val targetRange = ranges[idx]
        val newText = DocumentFormatter.replaceSingle(currentTfv.text, targetRange, state.replaceQuery)
        val newCursor = targetRange.first + state.replaceQuery.length
        val newRanges = DocumentFormatter.findMatchRanges(newText, state.findQuery, state.matchCase)
        val nextIdx = if (newRanges.isEmpty()) -1 else idx.coerceIn(0, newRanges.lastIndex)
        val sel = if (nextIdx in newRanges.indices) {
            TextRange(newRanges[nextIdx].first, newRanges[nextIdx].last + 1)
        } else {
            TextRange(newCursor)
        }

        _uiState.update {
            it.copy(
                textFieldValue = TextFieldValue(newText, sel),
                stats = DocumentFormatter.computeStats(newText),
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                matchRanges = newRanges,
                activeMatchIndex = nextIdx,
                saveStatus = SaveStatus.UNSAVED
            )
        }
        scheduleAutoSave()
    }

    fun replaceAllMatches() {
        val state = _uiState.value
        if (state.findQuery.isEmpty()) return
        val currentTfv = state.textFieldValue
        val (newText, count) = DocumentFormatter.replaceAll(
            currentTfv.text,
            state.findQuery,
            state.replaceQuery,
            state.matchCase
        )
        if (count == 0) return

        undoRedo.pushUndo(currentTfv)
        _uiState.update {
            it.copy(
                textFieldValue = TextFieldValue(newText, TextRange(0)),
                stats = DocumentFormatter.computeStats(newText),
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                matchRanges = emptyList(),
                activeMatchIndex = -1,
                saveStatus = SaveStatus.UNSAVED
            )
        }
        scheduleAutoSave()
        viewModelScope.launch {
            _toastMessage.emit("Replaced $count occurrence${if (count == 1) "" else "s"}")
        }
    }

    fun renameDocument(newTitle: String) {
        viewModelScope.launch {
            repository.renameFile(fileId, newTitle)
            val meta = repository.getFileMeta(fileId)
            if (meta != null) {
                _uiState.update { it.copy(title = meta.title) }
            }
        }
    }

    fun exportToUri(destUri: Uri) {
        viewModelScope.launch {
            flushSaveNow()
            repository.exportToUri(fileId, destUri)
                .onSuccess { _toastMessage.emit("Document exported") }
                .onFailure { _toastMessage.emit("Export failed") }
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(550L)
            flushSaveNow()
        }
    }

    suspend fun flushSaveNow() {
        autoSaveJob?.cancel()
        _uiState.update { it.copy(saveStatus = SaveStatus.SAVING) }
        repository.saveTextContent(fileId, _uiState.value.textFieldValue.text)
        _uiState.update { it.copy(saveStatus = SaveStatus.SAVED) }
    }

    fun saveAndNotify() {
        viewModelScope.launch {
            flushSaveNow()
            _toastMessage.emit("Saved locally")
        }
    }

    fun backupToDrive(context: Context) {
        viewModelScope.launch {
            flushSaveNow()
            _uiState.update { it.copy(isBackingUp = true) }
            when (val outcome = driveCoordinator.authManager.requestAuthorization(context)) {
                is DriveAuthOutcome.Authorized -> uploadWithToken(outcome.accessToken)
                is DriveAuthOutcome.NeedsUserConsent -> {
                    _uiState.update { it.copy(isBackingUp = false) }
                    _consentRequest.emit(outcome.intentSenderRequest)
                }
                is DriveAuthOutcome.Failed -> {
                    _uiState.update { it.copy(isBackingUp = false) }
                    _toastMessage.emit(outcome.message)
                }
            }
        }
    }

    fun onDriveConsentResult(context: Context, data: Intent?) {
        viewModelScope.launch {
            when (val outcome = driveCoordinator.authManager.handleConsentResult(context, data)) {
                is DriveAuthOutcome.Authorized -> {
                    _uiState.update { it.copy(isBackingUp = true) }
                    uploadWithToken(outcome.accessToken)
                }
                is DriveAuthOutcome.Failed -> _toastMessage.emit(outcome.message)
                is DriveAuthOutcome.NeedsUserConsent -> _consentRequest.emit(outcome.intentSenderRequest)
            }
        }
    }

    private suspend fun uploadWithToken(token: String) {
        driveCoordinator.backupSingleFile(token, fileId)
            .onSuccess { _toastMessage.emit("Backed up \"${_uiState.value.title}\" to Drive") }
            .onFailure { err -> _toastMessage.emit(err.message ?: "Drive backup failed") }
        _uiState.update { it.copy(isBackingUp = false) }
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
    }

    class Factory(
        private val application: Application,
        private val fileId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocEditorViewModel(application, fileId) as T
        }
    }
}
