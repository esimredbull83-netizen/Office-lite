package com.example.ui.doc

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.DocumentFormatter
import com.example.ui.theme.JetBrainsMono
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocEditorScreen(
    viewModel: DocEditorViewModel,
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) viewModel.exportToUri(uri)
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
                        modifier = Modifier.testTag("doc_back_button")
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
                            .testTag("doc_title_button")
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (state.saveStatus) {
                                SaveStatus.SAVED -> "Saved offline"
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
                        modifier = Modifier.testTag("doc_undo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.canRedo,
                        modifier = Modifier.testTag("doc_redo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = { viewModel.toggleFindReplace() },
                        modifier = Modifier.testTag("doc_find_replace_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FindReplace,
                            contentDescription = "Find and replace",
                            tint = if (state.showFindReplace) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    IconButton(
                        onClick = { viewModel.togglePreviewMode() },
                        modifier = Modifier.testTag("doc_preview_toggle")
                    ) {
                        Icon(
                            imageVector = if (state.isPreviewMode) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                            contentDescription = if (state.isPreviewMode) "Edit mode" else "Formatted preview"
                        )
                    }
                    IconButton(
                        onClick = { viewModel.saveAndNotify() },
                        modifier = Modifier.testTag("doc_save_button")
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "Save")
                    }
                    IconButton(
                        onClick = { viewModel.backupToDrive(context) },
                        enabled = !state.isBackingUp,
                        modifier = Modifier.testTag("doc_drive_backup_button")
                    ) {
                        if (state.isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.CloudUpload, contentDescription = "Back up to Drive")
                        }
                    }
                    IconButton(
                        onClick = { exportLauncher.launch(state.title) },
                        modifier = Modifier.testTag("doc_export_button")
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "Export document")
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
            // Find & Replace Bar
            AnimatedVisibility(visible = state.showFindReplace) {
                FindReplaceBar(
                    findQuery = state.findQuery,
                    replaceQuery = state.replaceQuery,
                    matchCount = state.matchRanges.size,
                    activeIndex = state.activeMatchIndex,
                    matchCase = state.matchCase,
                    onFindChange = viewModel::updateFindQuery,
                    onReplaceChange = viewModel::updateReplaceQuery,
                    onToggleCase = viewModel::toggleMatchCase,
                    onPrev = { viewModel.jumpToNextMatch(forward = false) },
                    onNext = { viewModel.jumpToNextMatch(forward = true) },
                    onReplaceOne = viewModel::replaceCurrentMatch,
                    onReplaceAll = viewModel::replaceAllMatches,
                    onClose = viewModel::toggleFindReplace
                )
            }

            // Document Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 780.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    val scrollState = rememberScrollState()
                    if (state.isPreviewMode) {
                        val accent = MaterialTheme.colorScheme.primary
                        val muted = MaterialTheme.colorScheme.onSurfaceVariant
                        val richText = remember(state.textFieldValue.text, accent, muted) {
                            DocumentFormatter.renderRichPreview(
                                state.textFieldValue.text,
                                accentColor = accent,
                                mutedColor = muted
                            )
                        }
                        Text(
                            text = richText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(18.dp)
                        )
                    } else {
                        BasicTextField(
                            value = state.textFieldValue,
                            onValueChange = viewModel::onValueChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(18.dp)
                                .testTag("doc_editor_field"),
                            decorationBox = { innerTextField ->
                                if (state.textFieldValue.text.isEmpty()) {
                                    Text(
                                        text = "Start writing notes or Markdown…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }
            }

            // Compact Formatting Toolbar + Live Word/Character Counter
            FormattingToolbar(
                words = state.stats.words,
                chars = state.stats.chars,
                onBold = viewModel::applyBold,
                onItalic = viewModel::applyItalic,
                onUnderline = viewModel::applyUnderline,
                onHeading = viewModel::applyHeading,
                onBullet = viewModel::applyBulletList,
                onNumbered = viewModel::applyNumberedList
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
                        viewModel.renameDocument(newName)
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
private fun FindReplaceBar(
    findQuery: String,
    replaceQuery: String,
    matchCount: Int,
    activeIndex: Int,
    matchCase: Boolean,
    onFindChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = onFindChange,
                    placeholder = { Text(stringResource(R.string.find_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("find_query_input")
                )
                Text(
                    text = if (matchCount == 0) "0/0" else "${activeIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (matchCase) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .clickable(onClick = onToggleCase)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Aa",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close find bar")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = replaceQuery,
                    onValueChange = onReplaceChange,
                    placeholder = { Text(stringResource(R.string.replace_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("replace_query_input")
                )
                TextButton(
                    onClick = onReplaceOne,
                    enabled = matchCount > 0,
                    modifier = Modifier.testTag("replace_one_button")
                ) {
                    Text("Replace")
                }
                TextButton(
                    onClick = onReplaceAll,
                    enabled = matchCount > 0,
                    modifier = Modifier.testTag("replace_all_button")
                ) {
                    Text("All")
                }
            }
        }
    }
}

@Composable
private fun FormattingToolbar(
    words: Int,
    chars: Int,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onHeading: () -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatToolButton(Icons.Rounded.FormatBold, "Bold", "format_bold", onBold)
                    FormatToolButton(Icons.Rounded.FormatItalic, "Italic", "format_italic", onItalic)
                    FormatToolButton(Icons.Rounded.FormatUnderlined, "Underline", "format_underline", onUnderline)
                    FormatToolButton(Icons.Rounded.Title, "Heading", "format_heading", onHeading)
                    FormatToolButton(Icons.AutoMirrored.Rounded.FormatListBulleted, "Bullet list", "format_bullet", onBullet)
                    FormatToolButton(Icons.Rounded.FormatListNumbered, "Numbered list", "format_numbered", onNumbered)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("doc_word_count_badge")
                ) {
                    Text(
                        text = "$words w · $chars c",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatToolButton(
    icon: ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp)
        )
    }
}
