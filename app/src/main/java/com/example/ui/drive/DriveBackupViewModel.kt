package com.example.ui.drive

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfficeFileEntity
import com.example.data.WorkspaceRepository
import com.example.drive.DriveAuthOutcome
import com.example.drive.DriveBackupCoordinator
import com.example.drive.DriveBackupFile
import com.example.drive.DriveUserInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DriveSheetTab(val label: String) {
    BACKUP("Back Up"),
    RESTORE("Restore")
}

sealed interface DriveSheetEffect {
    data class LaunchConsent(val request: IntentSenderRequest) : DriveSheetEffect
    data class ShowMessage(val message: String) : DriveSheetEffect
    data class RestoredAndOpen(val file: OfficeFileEntity) : DriveSheetEffect
}

data class DriveBackupUiState(
    val selectedTab: DriveSheetTab = DriveSheetTab.BACKUP,
    val isConnected: Boolean = false,
    val userInfo: DriveUserInfo? = null,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val activeItemId: String? = null,
    val remoteBackups: List<DriveBackupFile> = emptyList(),
    val errorMessage: String? = null
)

class DriveBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository.getInstance(application)
    private val coordinator = DriveBackupCoordinator.getInstance(application)
    private val authManager = coordinator.authManager

    private val _uiState = MutableStateFlow(
        DriveBackupUiState(
            isConnected = authManager.isConnected,
            userInfo = authManager.userInfo.value
        )
    )
    val uiState: StateFlow<DriveBackupUiState> = _uiState.asStateFlow()

    val localEligibleFiles: StateFlow<List<OfficeFileEntity>> = repository.recentFiles
        .map { list ->
            list.filter {
                it.fileType == com.example.data.OfficeFileType.DOC ||
                    it.fileType == com.example.data.OfficeFileType.SHEET
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _effects = MutableSharedFlow<DriveSheetEffect>()
    val effects: SharedFlow<DriveSheetEffect> = _effects.asSharedFlow()

    private var pendingAuthorizedAction: (suspend (String) -> Unit)? = null

    fun selectTab(tab: DriveSheetTab) {
        _uiState.update { it.copy(selectedTab = tab, errorMessage = null) }
        if (tab == DriveSheetTab.RESTORE && authManager.isConnected && _uiState.value.remoteBackups.isEmpty()) {
            refreshBackups(getApplication())
        }
    }

    fun syncConnectionState(context: Context) {
        val token = authManager.accessToken.value
        _uiState.update {
            it.copy(
                isConnected = !token.isNullOrBlank(),
                userInfo = authManager.userInfo.value
            )
        }
        if (!token.isNullOrBlank() && _uiState.value.remoteBackups.isEmpty()) {
            refreshBackups(context)
        }
    }

    fun connectDrive(context: Context) {
        runWithDriveToken(context) { token ->
            fetchBackupsWithToken(token)
            _effects.emit(DriveSheetEffect.ShowMessage("Connected to Google Drive"))
        }
    }

    fun disconnectDrive() {
        authManager.disconnect()
        pendingAuthorizedAction = null
        _uiState.update {
            it.copy(
                isConnected = false,
                userInfo = null,
                remoteBackups = emptyList(),
                errorMessage = null,
                busyMessage = null,
                isBusy = false
            )
        }
    }

    fun refreshBackups(context: Context) {
        runWithDriveToken(context) { token ->
            fetchBackupsWithToken(token)
        }
    }

    fun backupSingleFile(context: Context, file: OfficeFileEntity) {
        runWithDriveToken(context) { token ->
            _uiState.update {
                it.copy(
                    isBusy = true,
                    activeItemId = file.id,
                    busyMessage = "Backing up ${file.title}…",
                    errorMessage = null
                )
            }
            coordinator.backupSingleFile(token, file.id)
                .onSuccess {
                    fetchBackupsWithToken(token)
                    _effects.emit(DriveSheetEffect.ShowMessage("Backed up \"${file.title}\" to Drive"))
                }
                .onFailure { err ->
                    handleDriveError(err)
                }
            _uiState.update { it.copy(isBusy = false, activeItemId = null, busyMessage = null) }
        }
    }

    fun backupAllFiles(context: Context) {
        runWithDriveToken(context) { token ->
            _uiState.update {
                it.copy(isBusy = true, busyMessage = "Preparing backup…", errorMessage = null)
            }
            coordinator.backupAllDocsAndSheets(token) { completed, total, name ->
                _uiState.update {
                    it.copy(busyMessage = if (name.isNotEmpty()) "Backing up $completed/$total: $name" else "Finalizing…")
                }
            }.onSuccess { count ->
                fetchBackupsWithToken(token)
                val msg = if (count == 0) {
                    "No documents or spreadsheets to back up"
                } else {
                    "Backed up $count file${if (count == 1) "" else "s"} to Google Drive"
                }
                _effects.emit(DriveSheetEffect.ShowMessage(msg))
            }.onFailure { err ->
                handleDriveError(err)
            }
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
        }
    }

    fun restoreBackup(context: Context, backup: DriveBackupFile, openAfterRestore: Boolean = false) {
        runWithDriveToken(context) { token ->
            _uiState.update {
                it.copy(
                    isBusy = true,
                    activeItemId = backup.driveFileId,
                    busyMessage = "Restoring ${backup.name}…",
                    errorMessage = null
                )
            }
            coordinator.restoreBackup(token, backup)
                .onSuccess { restored ->
                    _effects.emit(DriveSheetEffect.ShowMessage("Restored \"${restored.title}\" locally"))
                    if (openAfterRestore) {
                        _effects.emit(DriveSheetEffect.RestoredAndOpen(restored))
                    }
                }
                .onFailure { err ->
                    handleDriveError(err)
                }
            _uiState.update { it.copy(isBusy = false, activeItemId = null, busyMessage = null) }
        }
    }

    fun deleteRemoteBackup(context: Context, backup: DriveBackupFile) {
        runWithDriveToken(context) { token ->
            _uiState.update {
                it.copy(isBusy = true, activeItemId = backup.driveFileId, errorMessage = null)
            }
            coordinator.deleteBackup(token, backup.driveFileId)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            remoteBackups = state.remoteBackups.filterNot { it.driveFileId == backup.driveFileId }
                        )
                    }
                    _effects.emit(DriveSheetEffect.ShowMessage("Removed \"${backup.name}\" from Drive"))
                }
                .onFailure { err ->
                    handleDriveError(err)
                }
            _uiState.update { it.copy(isBusy = false, activeItemId = null) }
        }
    }

    fun onConsentIntentResult(context: Context, data: Intent?) {
        viewModelScope.launch {
            when (val outcome = authManager.handleConsentResult(context, data)) {
                is DriveAuthOutcome.Authorized -> {
                    _uiState.update { it.copy(isConnected = true, errorMessage = null) }
                    val action = pendingAuthorizedAction
                    pendingAuthorizedAction = null
                    action?.invoke(outcome.accessToken)
                }
                is DriveAuthOutcome.Failed -> {
                    pendingAuthorizedAction = null
                    _uiState.update {
                        it.copy(isBusy = false, busyMessage = null, errorMessage = outcome.message)
                    }
                }
                is DriveAuthOutcome.NeedsUserConsent -> {
                    _effects.emit(DriveSheetEffect.LaunchConsent(outcome.intentSenderRequest))
                }
            }
        }
    }

    private fun runWithDriveToken(context: Context, action: suspend (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val outcome = authManager.requestAuthorization(context)) {
                is DriveAuthOutcome.Authorized -> {
                    _uiState.update { it.copy(isConnected = true) }
                    action(outcome.accessToken)
                    _uiState.update { it.copy(isBusy = false, busyMessage = null) }
                }
                is DriveAuthOutcome.NeedsUserConsent -> {
                    pendingAuthorizedAction = action
                    _uiState.update { it.copy(isBusy = false, busyMessage = null) }
                    _effects.emit(DriveSheetEffect.LaunchConsent(outcome.intentSenderRequest))
                }
                is DriveAuthOutcome.Failed -> {
                    _uiState.update {
                        it.copy(isBusy = false, busyMessage = null, errorMessage = outcome.message)
                    }
                }
            }
        }
    }

    private suspend fun fetchBackupsWithToken(token: String) {
        _uiState.update { it.copy(isBusy = true, busyMessage = "Syncing Drive list…", errorMessage = null) }
        coordinator.refreshSessionAndList(token)
            .onSuccess { (user, backups) ->
                _uiState.update {
                    it.copy(
                        isConnected = true,
                        userInfo = user ?: it.userInfo,
                        remoteBackups = backups,
                        isBusy = false,
                        busyMessage = null
                    )
                }
            }
            .onFailure { err ->
                handleDriveError(err)
                _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            }
    }

    private fun handleDriveError(err: Throwable) {
        _uiState.update {
            it.copy(
                isConnected = authManager.isConnected,
                errorMessage = err.message ?: "Google Drive operation failed."
            )
        }
    }
}
