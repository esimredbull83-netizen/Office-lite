package com.example.ui.sheet

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.SpreadsheetEngine
import com.example.ui.doc.SaveStatus
import com.example.ui.theme.JetBrainsMono
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val CELL_WIDTH = 108.dp
private val CELL_HEIGHT = 44.dp
private val ROW_HEADER_WIDTH = 44.dp
private val COL_HEADER_HEIGHT = 34.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetEditorScreen(
    viewModel: SheetEditorViewModel,
    onNavigateBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDriveConsentResult(context, result.data)
    }

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCsvUri(uri)
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) viewModel.exportCsvUri(uri)
    }

    val handleExit: () -> Unit = {
        scope.launch {
            viewModel.flushSaveNow()
            onNavigateBack()
        }
    }

    BackHandler(onBack = handleExit)

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.consentRequest.collectLatest { req ->
            driveConsentLauncher.launch(req)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = handleExit,
                        modifier = Modifier.testTag("sheet_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Column(
                        modifier = Modifier
                            .clickable { showRenameDialog = true }
                            .padding(vertical = 4.dp)
                            .testTag("sheet_title_button")
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (state.saveStatus) {
                                SaveStatus.SAVED -> "Saved offline · ${state.rowCount}×${state.colCount}"
                                SaveStatus.SAVING -> "Saving…"
                                SaveStatus.UNSAVED -> "Editing…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = state.canUndo,
                        modifier = Modifier.testTag("sheet_undo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.canRedo,
                        modifier = Modifier.testTag("sheet_redo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = { viewModel.backupToDrive(context) },
                        enabled = !state.isBackingUp,
                        modifier = Modifier.testTag("sheet_drive_backup_button")
                    ) {
                        if (state.isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.CloudUpload, contentDescription = "Back up to Drive")
                        }
                    }
                    IconButton(
                        onClick = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                        modifier = Modifier.testTag("sheet_import_csv_button")
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "Import CSV")
                    }
                    IconButton(
                        onClick = { exportCsvLauncher.launch(state.title) },
                        modifier = Modifier.testTag("sheet_export_csv_button")
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "Export CSV")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // Formula & Value Input Bar
            FormulaBar(
                cellLabel = state.selectedCellLabel,
                formulaInput = state.formulaInput,
                onFormulaChange = viewModel::onFormulaInputChange,
                onCommit = viewModel::commitFormulaInput,
                onInsertFunction = viewModel::insertQuickFormula
            )

            // 2D Spreadsheet Grid with synchronized sticky headers
            val horizontalScroll = rememberScrollState()
            val verticalScroll = rememberScrollState()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Top Column Headers Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    // Top-left corner cell
                    Box(
                        modifier = Modifier
                            .width(ROW_HEADER_WIDTH)
                            .height(COL_HEADER_HEIGHT)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                        for (c in 0 until state.colCount) {
                            val isSelectedCol = c == state.selectedCol
                            Box(
                                modifier = Modifier
                                    .width(CELL_WIDTH)
                                    .height(COL_HEADER_HEIGHT)
                                    .background(
                                        if (isSelectedCol) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = SpreadsheetEngine.columnLabel(c),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = if (isSelectedCol) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isSelectedCol) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                // Body Rows (Sticky Row Number Column + Scrollable Cells)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(verticalScroll)
                ) {
                    // Sticky Row Headers Column
                    Column(
                        modifier = Modifier
                            .width(ROW_HEADER_WIDTH)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        for (r in 0 until state.rowCount) {
                            val isSelectedRow = r == state.selectedRow
                            Box(
                                modifier = Modifier
                                    .width(ROW_HEADER_WIDTH)
                                    .height(CELL_HEIGHT)
                                    .background(
                                        if (isSelectedRow) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${r + 1}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = if (isSelectedRow) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelectedRow) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }

                    // Scrollable Cell Grid
                    Column(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                        for (r in 0 until state.rowCount) {
                            Row {
                                for (c in 0 until state.colCount) {
                                    val isSelected = r == state.selectedRow && c == state.selectedCol
                                    val rawVal = state.rawGrid.getOrNull(r)?.getOrNull(c).orEmpty()
                                    val displayVal = state.displayGrid.getOrNull(r)?.getOrNull(c).orEmpty()
                                    val isFormula = rawVal.trim().startsWith("=")
                                    val isError = displayVal.startsWith("#")
                                    val isNumeric = !isError && displayVal.toDoubleOrNull() != null

                                    val cellBg = when {
                                        isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                                        isFormula -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                    val borderColor = if (isSelected) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(CELL_WIDTH)
                                            .height(CELL_HEIGHT)
                                            .background(cellBg)
                                            .border(if (isSelected) 2.dp else 0.5.dp, borderColor)
                                            .clickable { viewModel.selectCell(r, c) }
                                            .padding(horizontal = 8.dp)
                                            .testTag("cell_${r}_${c}"),
                                        contentAlignment = if (isNumeric) {
                                            Alignment.CenterEnd
                                        } else {
                                            Alignment.CenterStart
                                        }
                                    ) {
                                        Text(
                                            text = displayVal,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = if (isNumeric || isFormula) JetBrainsMono else MaterialTheme.typography.bodySmall.fontFamily
                                            ),
                                            color = when {
                                                isError -> MaterialTheme.colorScheme.error
                                                isFormula -> MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = if (isNumeric) TextAlign.End else TextAlign.Start
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Grid Manipulation Toolbar
            SheetActionToolbar(
                canRemoveRow = state.rowCount > 1,
                canRemoveCol = state.colCount > 1,
                onAddRow = viewModel::addRow,
                onRemoveRow = viewModel::removeRow,
                onAddCol = viewModel::addColumn,
                onRemoveCol = viewModel::removeColumn
            )
        }
    }

    if (showRenameDialog) {
        var newName by remember(state.title) { mutableStateOf(state.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSheet(newName)
                        showRenameDialog = false
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun FormulaBar(
    cellLabel: String,
    formulaInput: String,
    onFormulaChange: (String) -> Unit,
    onCommit: () -> Unit,
    onInsertFunction: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = cellLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Rounded.Functions,
                    contentDescription = "Formula",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                ) {
                    BasicTextField(
                        value = formulaInput,
                        onValueChange = onFormulaChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = JetBrainsMono,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("sheet_formula_input"),
                        decorationBox = { inner ->
                            if (formulaInput.isEmpty()) {
                                Text(
                                    text = "Value or =SUM(A1:A5)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            }
                            inner()
                        }
                    )
                }

                IconButton(
                    onClick = onCommit,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("sheet_commit_cell_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Apply cell value",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Quick Formula Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("SUM", "AVERAGE", "COUNT", "MIN", "MAX").forEach { fn ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable { onInsertFunction(fn) }
                            .testTag("formula_chip_${fn.lowercase()}")
                    ) {
                        Text(
                            text = "=$fn()",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SheetActionToolbar(
    canRemoveRow: Boolean,
    canRemoveCol: Boolean,
    onAddRow: () -> Unit,
    onRemoveRow: () -> Unit,
    onAddCol: () -> Unit,
    onRemoveCol: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GridActionPill("Row", Icons.Rounded.Add, "add_row_button", true, onAddRow)
                GridActionPill("Row", Icons.Rounded.Remove, "remove_row_button", canRemoveRow, onRemoveRow)
                Spacer(modifier = Modifier.width(4.dp))
                GridActionPill("Col", Icons.Rounded.Add, "add_col_button", true, onAddCol)
                GridActionPill("Col", Icons.Rounded.Remove, "remove_col_button", canRemoveCol, onRemoveCol)
            }
        }
    }
}

@Composable
private fun GridActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                }
            )
        }
    }
}
