package com.example.ui.sheet

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.WorkspaceRepository
import com.example.domain.SpreadsheetEngine
import com.example.domain.UndoRedoStack
import com.example.drive.DriveAuthOutcome
import com.example.drive.DriveBackupCoordinator
import com.example.ui.doc.SaveStatus
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

data class SheetUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val rawGrid: List<List<String>> = emptyList(),
    val displayGrid: List<List<String>> = emptyList(),
    val selectedRow: Int = 0,
    val selectedCol: Int = 0,
    val formulaInput: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val isBackingUp: Boolean = false
) {
    val rowCount: Int get() = rawGrid.size
    val colCount: Int get() = rawGrid.firstOrNull()?.size ?: 0
    val selectedCellLabel: String get() = SpreadsheetEngine.cellName(selectedRow, selectedCol)
}

class SheetEditorViewModel(
    application: Application,
    private val fileId: String
) : AndroidViewModel(application) {

    private val repository = WorkspaceRepository.getInstance(application)
    private val driveCoordinator = DriveBackupCoordinator.getInstance(application)
    private val undoRedo = UndoRedoStack<List<List<String>>>(maxCapacity = 50)

    private val _uiState = MutableStateFlow(SheetUiState())
    val uiState: StateFlow<SheetUiState> = _uiState.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _consentRequest = MutableSharedFlow<IntentSenderRequest>()
    val consentRequest: SharedFlow<IntentSenderRequest> = _consentRequest.asSharedFlow()

    private var autoSaveJob: Job? = null

    init {
        loadSheet()
    }

    private fun loadSheet() {
        viewModelScope.launch {
            val (meta, csvText) = repository.readTextContent(fileId)
            val (raw, display) = withContext(Dispatchers.Default) {
                val parsed = SpreadsheetEngine.parseCsv(csvText)
                val evaluated = SpreadsheetEngine.evaluateAll(parsed)
                parsed to evaluated
            }
            val firstCell = raw.getOrNull(0)?.getOrNull(0).orEmpty()
            _uiState.value = SheetUiState(
                isLoading = false,
                title = meta?.title ?: "Sheet.csv",
                rawGrid = raw,
                displayGrid = display,
                selectedRow = 0,
                selectedCol = 0,
                formulaInput = firstCell,
                saveStatus = SaveStatus.SAVED
            )
        }
    }

    fun selectCell(row: Int, col: Int) {
        val state = _uiState.value
        if (row !in state.rawGrid.indices || col !in (state.rawGrid.firstOrNull()?.indices ?: IntRange.EMPTY)) return

        // Commit any pending edit in the previously selected cell before switching
        val committedGrid = applyCellEditIfChanged(
            state.rawGrid,
            state.selectedRow,
            state.selectedCol,
            state.formulaInput
        )
        val nextFormula = committedGrid.getOrNull(row)?.getOrNull(col).orEmpty()

        if (committedGrid !== state.rawGrid) {
            undoRedo.pushUndo(state.rawGrid)
            val evaluated = SpreadsheetEngine.evaluateAll(committedGrid)
            _uiState.update {
                it.copy(
                    rawGrid = committedGrid,
                    displayGrid = evaluated,
                    selectedRow = row,
                    selectedCol = col,
                    formulaInput = nextFormula,
                    canUndo = undoRedo.canUndo,
                    canRedo = undoRedo.canRedo,
                    saveStatus = SaveStatus.UNSAVED
                )
            }
            scheduleAutoSave()
        } else {
            _uiState.update {
                it.copy(
                    selectedRow = row,
                    selectedCol = col,
                    formulaInput = nextFormula
                )
            }
        }
    }

    fun onFormulaInputChange(value: String) {
        _uiState.update { it.copy(formulaInput = value) }
    }

    fun commitFormulaInput() {
        val state = _uiState.value
        val updated = applyCellEditIfChanged(
            state.rawGrid,
            state.selectedRow,
            state.selectedCol,
            state.formulaInput
        )
        if (updated !== state.rawGrid) {
            commitNewGrid(updated)
        }
    }

    fun insertQuickFormula(funcName: String) {
        val state = _uiState.value
        val r = state.selectedRow
        val c = state.selectedCol
        val colName = SpreadsheetEngine.columnLabel(c)
        val suggested = if (r > 0) {
            "=$funcName(${colName}1:${colName}$r)"
        } else if (c > 0) {
            val endCol = SpreadsheetEngine.columnLabel(c - 1)
            "=$funcName(A${r + 1}:${endCol}${r + 1})"
        } else {
            "=$funcName(A1:A5)"
        }
        _uiState.update { it.copy(formulaInput = suggested) }
    }

    fun addRow() {
        val state = _uiState.value
        val cols = state.colCount.coerceAtLeast(1)
        val updated = state.rawGrid.toMutableList().apply {
            add(List(cols) { "" })
        }
        commitNewGrid(updated)
    }

    fun removeRow() {
        val state = _uiState.value
        if (state.rowCount <= 1) return
        val targetRow = state.selectedRow.coerceIn(0, state.rawGrid.lastIndex)
        val updated = state.rawGrid.toMutableList().apply {
            removeAt(targetRow)
        }
        val nextRow = targetRow.coerceAtMost(updated.lastIndex)
        commitNewGrid(updated, nextRow = nextRow, nextCol = state.selectedCol)
    }

    fun addColumn() {
        val state = _uiState.value
        val updated = state.rawGrid.map { row -> row + "" }
        commitNewGrid(updated)
    }

    fun removeColumn() {
        val state = _uiState.value
        if (state.colCount <= 1) return
        val targetCol = state.selectedCol.coerceIn(0, state.colCount - 1)
        val updated = state.rawGrid.map { row ->
            row.toMutableList().apply { removeAt(targetCol) }
        }
        val nextCol = targetCol.coerceAtMost((updated.firstOrNull()?.size ?: 1) - 1)
        commitNewGrid(updated, nextRow = state.selectedRow, nextCol = nextCol)
    }

    fun undo() {
        val current = _uiState.value.rawGrid
        val prev = undoRedo.undo(current) ?: return
        applyUndoRedoGrid(prev)
    }

    fun redo() {
        val current = _uiState.value.rawGrid
        val next = undoRedo.redo(current) ?: return
        applyUndoRedoGrid(next)
    }

    private fun applyUndoRedoGrid(grid: List<List<String>>) {
        val evaluated = SpreadsheetEngine.evaluateAll(grid)
        val r = _uiState.value.selectedRow.coerceIn(0, grid.lastIndex)
        val c = _uiState.value.selectedCol.coerceIn(0, (grid.firstOrNull()?.lastIndex ?: 0))
        _uiState.update {
            it.copy(
                rawGrid = grid,
                displayGrid = evaluated,
                selectedRow = r,
                selectedCol = c,
                formulaInput = grid[r][c],
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                saveStatus = SaveStatus.UNSAVED
            )
        }
        scheduleAutoSave()
    }

    fun importCsvUri(uri: Uri) {
        viewModelScope.launch {
            repository.readCsvFromUri(uri)
                .onSuccess { csvText ->
                    val parsed = withContext(Dispatchers.Default) {
                        SpreadsheetEngine.parseCsv(csvText)
                    }
                    commitNewGrid(parsed, nextRow = 0, nextCol = 0)
                    _toastMessage.emit("CSV imported")
                }
                .onFailure { err ->
                    _toastMessage.emit(err.message ?: "Failed to import CSV")
                }
        }
    }

    fun exportCsvUri(destUri: Uri) {
        viewModelScope.launch {
            flushSaveNow()
            repository.exportToUri(fileId, destUri)
                .onSuccess { _toastMessage.emit("CSV exported") }
                .onFailure { _toastMessage.emit("Export failed") }
        }
    }

    fun renameSheet(newTitle: String) {
        viewModelScope.launch {
            repository.renameFile(fileId, newTitle)
            val meta = repository.getFileMeta(fileId)
            if (meta != null) {
                _uiState.update { it.copy(title = meta.title) }
            }
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

    private fun commitNewGrid(
        newGrid: List<List<String>>,
        nextRow: Int = _uiState.value.selectedRow,
        nextCol: Int = _uiState.value.selectedCol
    ) {
        val state = _uiState.value
        undoRedo.pushUndo(state.rawGrid)
        val safeRow = nextRow.coerceIn(0, newGrid.lastIndex)
        val safeCol = nextCol.coerceIn(0, (newGrid.firstOrNull()?.lastIndex ?: 0))
        val evaluated = SpreadsheetEngine.evaluateAll(newGrid)
        _uiState.update {
            it.copy(
                rawGrid = newGrid,
                displayGrid = evaluated,
                selectedRow = safeRow,
                selectedCol = safeCol,
                formulaInput = newGrid[safeRow][safeCol],
                canUndo = undoRedo.canUndo,
                canRedo = undoRedo.canRedo,
                saveStatus = SaveStatus.UNSAVED
            )
        }
        scheduleAutoSave()
    }

    private fun applyCellEditIfChanged(
        grid: List<List<String>>,
        row: Int,
        col: Int,
        newValue: String
    ): List<List<String>> {
        val currentVal = grid.getOrNull(row)?.getOrNull(col) ?: return grid
        if (currentVal == newValue) return grid
        return grid.mapIndexed { rIdx, rList ->
            if (rIdx != row) rList else rList.mapIndexed { cIdx, cVal ->
                if (cIdx == col) newValue else cVal
            }
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500L)
            flushSaveNow()
        }
    }

    suspend fun flushSaveNow() {
        autoSaveJob?.cancel()
        val state = _uiState.value
        val finalGrid = applyCellEditIfChanged(
            state.rawGrid,
            state.selectedRow,
            state.selectedCol,
            state.formulaInput
        )
        if (finalGrid !== state.rawGrid) {
            val evaluated = SpreadsheetEngine.evaluateAll(finalGrid)
            _uiState.update {
                it.copy(rawGrid = finalGrid, displayGrid = evaluated)
            }
        }
        _uiState.update { it.copy(saveStatus = SaveStatus.SAVING) }
        val csv = withContext(Dispatchers.Default) {
            SpreadsheetEngine.serializeCsv(finalGrid)
        }
        val preview = "${finalGrid.size} rows × ${finalGrid.firstOrNull()?.size ?: 0} cols"
        repository.saveTextContent(fileId, csv, customPreview = preview)
        _uiState.update { it.copy(saveStatus = SaveStatus.SAVED) }
        driveCoordinator.launchAutoSyncIfEnabled(fileId)
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
            return SheetEditorViewModel(application, fileId) as T
        }
    }
}
