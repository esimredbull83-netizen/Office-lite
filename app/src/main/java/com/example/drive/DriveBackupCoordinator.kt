package com.example.drive

import android.content.Context
import com.example.data.OfficeFileEntity
import com.example.data.OfficeFileType
import com.example.data.WorkspaceRepository

class DriveBackupCoordinator(
    private val repository: WorkspaceRepository,
    private val driveClient: GoogleDriveClient = GoogleDriveClient(),
    val authManager: DriveAuthManager = DriveAuthManager.getInstance()
) {

    suspend fun refreshSessionAndList(token: String): Result<Pair<DriveUserInfo?, List<DriveBackupFile>>> {
        return runCatching {
            val user = authManager.userInfo.value ?: runCatching {
                driveClient.fetchDriveUser(token)
            }.getOrNull()?.also { authManager.setUserInfo(it) }

            val folderId = getOrFetchFolderId(token)
            val backups = driveClient.listBackups(token, folderId)
            user to backups
        }.recoverCatching { err ->
            if (err is DriveUnauthorizedException) authManager.invalidateTokenOnly()
            throw err
        }
    }

    suspend fun backupSingleFile(token: String, fileId: String): Result<DriveBackupFile> {
        return runCatching {
            val (meta, content) = repository.readTextContent(fileId)
            val entity = meta ?: error("File not found locally.")
            if (entity.fileType != OfficeFileType.DOC && entity.fileType != OfficeFileType.SHEET) {
                error("Only documents and spreadsheets can be backed up to Drive.")
            }
            val folderId = getOrFetchFolderId(token)
            val uploaded = driveClient.uploadOrUpdateBackup(
                accessToken = token,
                folderId = folderId,
                localId = entity.id,
                title = entity.title,
                fileType = entity.fileType,
                content = content
            )
            uploaded
        }.recoverCatching { err ->
            if (err is DriveUnauthorizedException) authManager.invalidateTokenOnly()
            throw err
        }
    }

    suspend fun backupAllDocsAndSheets(
        token: String,
        onProgress: (completed: Int, total: Int, currentName: String) -> Unit
    ): Result<Int> {
        return runCatching {
            val eligible = repository.getBackupEligibleFiles()
            if (eligible.isEmpty()) return@runCatching 0
            val folderId = getOrFetchFolderId(token)

            var uploadedCount = 0
            eligible.forEachIndexed { idx, entity ->
                onProgress(idx, eligible.size, entity.title)
                val (_, content) = repository.readTextContent(entity.id)
                driveClient.uploadOrUpdateBackup(
                    accessToken = token,
                    folderId = folderId,
                    localId = entity.id,
                    title = entity.title,
                    fileType = entity.fileType,
                    content = content
                )
                uploadedCount++
            }
            onProgress(eligible.size, eligible.size, "")
            uploadedCount
        }.recoverCatching { err ->
            if (err is DriveUnauthorizedException) authManager.invalidateTokenOnly()
            throw err
        }
    }

    suspend fun restoreBackup(token: String, backup: DriveBackupFile): Result<OfficeFileEntity> {
        return runCatching {
            val content = driveClient.downloadTextContent(token, backup.driveFileId)
            repository.restoreFromDriveBackup(
                preferredId = backup.liteOfficeId,
                title = backup.name,
                fileType = backup.fileType,
                content = content
            )
        }.recoverCatching { err ->
            if (err is DriveUnauthorizedException) authManager.invalidateTokenOnly()
            throw err
        }
    }

    suspend fun deleteBackup(token: String, driveFileId: String): Result<Unit> {
        return runCatching {
            driveClient.deleteBackup(token, driveFileId)
        }.recoverCatching { err ->
            if (err is DriveUnauthorizedException) authManager.invalidateTokenOnly()
            throw err
        }
    }

    private suspend fun getOrFetchFolderId(token: String): String {
        authManager.getCachedFolderId()?.let { return it }
        return driveClient.ensureBackupFolderId(token).also {
            authManager.setCachedFolderId(it)
        }
    }

    companion object {
        @Volatile
        private var instance: DriveBackupCoordinator? = null

        fun getInstance(context: Context): DriveBackupCoordinator {
            return instance ?: synchronized(this) {
                instance ?: DriveBackupCoordinator(
                    repository = WorkspaceRepository.getInstance(context)
                ).also { instance = it }
            }
        }
    }
}
