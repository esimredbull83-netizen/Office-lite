package com.example.ui.drive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.OfficeFileEntity
import com.example.data.OfficeFileType
import com.example.drive.DriveBackupFile
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBackupSheet(
    viewModel: DriveBackupViewModel,
    onDismiss: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    onOpenRestoredFile: (OfficeFileEntity) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localFiles by viewModel.localEligibleFiles.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentIntentResult(context, result.data)
    }

    LaunchedEffect(Unit) {
        viewModel.syncConnectionState(context)
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is DriveSheetEffect.LaunchConsent -> consentLauncher.launch(effect.request)
                is DriveSheetEffect.ShowMessage -> onShowSnackbar(effect.message)
                is DriveSheetEffect.RestoredAndOpen -> {
                    onDismiss()
                    onOpenRestoredFile(effect.file)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.drive_backup_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Manual backup & restore for Docs & Sheets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.isConnected) {
                    IconButton(
                        onClick = { viewModel.refreshBackups(context) },
                        enabled = !state.isBusy,
                        modifier = Modifier.testTag("drive_refresh_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Drive list")
                    }
                }
            }

            // Connection Status Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (state.isConnected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isConnected) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = if (state.isConnected) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isConnected) {
                                state.userInfo?.displayName?.ifBlank { "Google Drive Connected" }
                                    ?: "Google Drive Connected"
                            } else {
                                "Not connected"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (state.isConnected) {
                                state.userInfo?.emailAddress?.ifBlank { "Folder: Lite Office Backups" }
                                    ?: "Folder: Lite Office Backups"
                            } else {
                                "Uses drive.file access for backups you create"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (state.isConnected) {
                        TextButton(
                            onClick = { viewModel.disconnectDrive() },
                            enabled = !state.isBusy,
                            modifier = Modifier.testTag("disconnect_drive_button")
                        ) {
                            Text(stringResource(R.string.drive_disconnect))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.connectDrive(context) },
                            enabled = !state.isBusy,
                            modifier = Modifier.testTag("connect_drive_button")
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }

            if (state.isBusy) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.busyMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Segmented Tabs: Back Up | Restore
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DriveSheetTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = DriveSheetTab.entries.size
                        ),
                        modifier = Modifier.testTag("drive_tab_${tab.name.lowercase()}")
                    ) {
                        Text(
                            text = when (tab) {
                                DriveSheetTab.BACKUP -> "${tab.label} (${localFiles.size})"
                                DriveSheetTab.RESTORE -> "${tab.label} (${state.remoteBackups.size})"
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when (state.selectedTab) {
                DriveSheetTab.BACKUP -> {
                    if (localFiles.isEmpty()) {
                        EmptyDriveSection(
                            title = "No documents or spreadsheets yet",
                            subtitle = "Create a Doc or Sheet on the Home screen to back it up manually to Google Drive."
                        )
                    } else {
                        Button(
                            onClick = { viewModel.backupAllFiles(context) },
                            enabled = !state.isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_all_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${stringResource(R.string.drive_backup_all)} (${localFiles.size})")
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(localFiles, key = { it.id }) { file ->
                                val isBackedUp = state.remoteBackups.any {
                                    it.liteOfficeId == file.id || it.name == file.title
                                }
                                LocalBackupRow(
                                    file = file,
                                    isBackedUp = isBackedUp,
                                    isWorking = state.activeItemId == file.id,
                                    enabled = !state.isBusy,
                                    onBackup = { viewModel.backupSingleFile(context, file) }
                                )
                            }
                        }
                    }
                }

                DriveSheetTab.RESTORE -> {
                    if (!state.isConnected) {
                        EmptyDriveSection(
                            title = "Connect Google Drive to view backups",
                            subtitle = "Tap Connect above to browse and restore documents and spreadsheets from \"Lite Office Backups\"."
                        )
                    } else if (state.remoteBackups.isEmpty() && !state.isBusy) {
                        EmptyDriveSection(
                            title = "No Drive backups found",
                            subtitle = "Switch to Back Up to upload your documents and spreadsheets to \"Lite Office Backups\"."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.remoteBackups, key = { it.driveFileId }) { backup ->
                                RemoteBackupRow(
                                    backup = backup,
                                    isWorking = state.activeItemId == backup.driveFileId,
                                    enabled = !state.isBusy,
                                    onRestore = {
                                        viewModel.restoreBackup(context, backup, openAfterRestore = true)
                                    },
                                    onDelete = {
                                        viewModel.deleteRemoteBackup(context, backup)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalBackupRow(
    file: OfficeFileEntity,
    isBackedUp: Boolean,
    isWorking: Boolean,
    enabled: Boolean,
    onBackup: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (file.fileType == OfficeFileType.SHEET) {
                    Icons.Outlined.TableChart
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
                tint = if (file.fileType == OfficeFileType.SHEET) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = file.fileType.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isBackedUp) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Backed up",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "In Drive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(
                    onClick = onBackup,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Back Up", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun RemoteBackupRow(
    backup: DriveBackupFile,
    isWorking: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (backup.fileType == OfficeFileType.SHEET) {
                    Icons.Outlined.TableChart
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
                tint = if (backup.fileType == OfficeFileType.SHEET) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val dateLabel = backup.modifiedTime.take(10).ifBlank { "Drive backup" }
                Text(
                    text = "${backup.fileType.badge} · $dateLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(22.dp),
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = onRestore,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.drive_restore_action), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = onDelete,
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete Drive backup",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDriveSection(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
