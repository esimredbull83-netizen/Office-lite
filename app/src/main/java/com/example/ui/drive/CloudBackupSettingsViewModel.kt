package com.example.ui.drive

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfficeFileEntity
import com.example.data.OfficeFileType
import com.example.data.WorkspaceRepository
import com.example.drive.DriveAuthOutcome
import com.example.drive.DriveBackupCoordinator
import com.example.drive.DriveUserInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CloudBackupSettingsEffect {
    data class LaunchConsent(val request: IntentSenderRequest) : CloudBackupSettingsEffect
    data class ShowMessage(val message: String) : CloudBackupSettingsEffect
}

data class CloudBackupSettingsUiState(
    val isConnected: Boolean = false,
    val userInfo: DriveUserInfo? = null,
    val autoSyncEnabled: Boolean = false,
    val lastBackupAt: Long = 0L,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val activeFileId: String? = null,
    val errorMessage: String? = null
)

class CloudBackupSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository.getInstance(application)
    private val coordinator = DriveBackupCoordinator.getInstance(application)
    private val authManager = coordinator.authManager

    private val _busyState = MutableStateFlow(
        Triple<Boolean, String?, String?>(false, null, null) // isBusy, busyMsg, activeFileId
    )
    private val _errorMessage = MutableStateFlow<String?>(null)

    val eligibleFiles: StateFlow<List<OfficeFileEntity>> = repository.recentFiles
        .map { files ->
            files.filter {
                it.fileType == OfficeFileType.DOC || it.fileType == OfficeFileType.SHEET
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<CloudBackupSettingsUiState> = combine(
        authManager.accessToken,
        authManager.userInfo,
        repository.autoSyncEnabled,
        repository.lastBackupAt,
        _busyState,
        _errorMessage
    ) { values ->
        val token = values[0] as String?
        val user = values[1] as DriveUserInfo?
        val autoSync = values[2] as Boolean
        val lastBackup = values[3] as Long
        @Suppress("UNCHECKED_CAST")
        val busyTriple = values[4] as Triple<Boolean, String?, String?>
        val error = values[5] as String?

        CloudBackupSettingsUiState(
            isConnected = !token.isNullOrBlank(),
            userInfo = user,
            autoSyncEnabled = autoSync,
            lastBackupAt = lastBackup,
            isBusy = busyTriple.first,
            busyMessage = busyTriple.second,
            activeFileId = busyTriple.third,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CloudBackupSettingsUiState(
            isConnected = authManager.isConnected,
            userInfo = authManager.userInfo.value,
            autoSyncEnabled = repository.autoSyncEnabled.value,
            lastBackupAt = repository.lastBackupAt.value
        )
    )

    private val _effects = MutableSharedFlow<CloudBackupSettingsEffect>()
    val effects: SharedFlow<CloudBackupSettingsEffect> = _effects.asSharedFlow()

    private var pendingAction: (suspend (String) -> Unit)? = null

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        if (!enabled) {
            repository.setAutoSyncEnabled(false)
            viewModelScope.launch {
                _effects.emit(CloudBackupSettingsEffect.ShowMessage("Auto-sync paused"))
            }
            return
        }

        if (authManager.isConnected) {
            repository.setAutoSyncEnabled(true)
            viewModelScope.launch {
                _effects.emit(CloudBackupSettingsEffect.ShowMessage("Auto-sync enabled for Docs & Sheets"))
            }
        } else {
            runWithToken(context) { token ->
                coordinator.refreshSessionAndList(token)
                repository.setAutoSyncEnabled(true)
                _effects.emit(CloudBackupSettingsEffect.ShowMessage("Connected & auto-sync enabled"))
            }
        }
    }

    fun connectDrive(context: Context) {
        runWithToken(context) { token ->
            coordinator.refreshSessionAndList(token)
            _effects.emit(CloudBackupSettingsEffect.ShowMessage("Connected to Google Drive"))
        }
    }

    fun disconnectDrive() {
        authManager.disconnect()
        repository.setAutoSyncEnabled(false)
        pendingAction = null
        _errorMessage.value = null
        _busyState.value = Triple(false, null, null)
        viewModelScope.launch {
            _effects.emit(CloudBackupSettingsEffect.ShowMessage("Disconnected from Google Drive"))
        }
    }

    fun backupAllNow(context: Context) {
        runWithToken(context) { token ->
            _busyState.value = Triple(true, "Preparing backup…", null)
            coordinator.backupAllDocsAndSheets(token) { completed, total, name ->
                val msg = if (name.isNotEmpty()) {
                    "Backing up $completed/$total: $name"
                } else {
                    "Finalizing backup…"
                }
                _busyState.value = Triple(true, msg, null)
            }.onSuccess { count ->
                val msg = if (count == 0) {
                    "No documents or spreadsheets to back up"
                } else {
                    "Backed up $count file${if (count == 1) "" else "s"} to Google Drive"
                }
                _effects.emit(CloudBackupSettingsEffect.ShowMessage(msg))
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Backup failed."
            }
            _busyState.value = Triple(false, null, null)
        }
    }

    fun backupSingleFile(context: Context, file: OfficeFileEntity) {
        runWithToken(context) { token ->
            _busyState.value = Triple(true, "Backing up ${file.title}…", file.id)
            coordinator.backupSingleFile(token, file.id)
                .onSuccess {
                    _effects.emit(CloudBackupSettingsEffect.ShowMessage("Backed up \"${file.title}\""))
                }
                .onFailure { err ->
                    _errorMessage.value = err.message ?: "Backup failed."
                }
            _busyState.value = Triple(false, null, null)
        }
    }

    fun onConsentIntentResult(context: Context, data: Intent?) {
        viewModelScope.launch {
            when (val outcome = authManager.handleConsentResult(context, data)) {
                is DriveAuthOutcome.Authorized -> {
                    _errorMessage.value = null
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke(outcome.accessToken)
                }
                is DriveAuthOutcome.Failed -> {
                    pendingAction = null
                    _busyState.value = Triple(false, null, null)
                    _errorMessage.value = outcome.message
                }
                is DriveAuthOutcome.NeedsUserConsent -> {
                    _effects.emit(CloudBackupSettingsEffect.LaunchConsent(outcome.intentSenderRequest))
                }
            }
        }
    }

    private fun runWithToken(context: Context, block: suspend (String) -> Unit) {
        viewModelScope.launch {
            _errorMessage.value = null
            _busyState.value = Triple(true, "Authorizing Google Drive…", null)
            when (val outcome = authManager.requestAuthorization(context)) {
                is DriveAuthOutcome.Authorized -> {
                    block(outcome.accessToken)
                    _busyState.value = Triple(false, null, null)
                }
                is DriveAuthOutcome.NeedsUserConsent -> {
                    pendingAction = block
                    _busyState.value = Triple(false, null, null)
                    _effects.emit(CloudBackupSettingsEffect.LaunchConsent(outcome.intentSenderRequest))
                }
                is DriveAuthOutcome.Failed -> {
                    _busyState.value = Triple(false, null, null)
                    _errorMessage.value = outcome.message
                }
            }
        }
    }
}
