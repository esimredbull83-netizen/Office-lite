package com.example.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.domain.SpreadsheetEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class WorkspaceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = LiteOfficeDatabase.getInstance(appContext).officeFileDao()
    private val workspaceDir: File = File(appContext.filesDir, "workspace").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("lite_office_prefs", Context.MODE_PRIVATE)

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK_THEME, false))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    val recentFiles: Flow<List<OfficeFileEntity>> = dao.observeAllFiles()

    fun toggleTheme() {
        val next = !_isDarkTheme.value
        _isDarkTheme.value = next
        prefs.edit().putBoolean(KEY_DARK_THEME, next).apply()
    }

    fun getLocalFile(localFilename: String): File = File(workspaceDir, localFilename)

    suspend fun getFileMeta(id: String): OfficeFileEntity? = withContext(Dispatchers.IO) {
        dao.getFileById(id)
    }

    suspend fun createNewDoc(title: String = "Untitled Doc.md"): OfficeFileEntity =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val localFilename = "$id.md"
            val initialText = "# Untitled Document\n\nStart writing here..."
            val file = getLocalFile(localFilename)
            file.writeText(initialText, Charsets.UTF_8)
            val entity = OfficeFileEntity(
                id = id,
                title = title,
                type = OfficeFileType.DOC.name,
                localFilename = localFilename,
                previewSnippet = extractTextPreview(initialText),
                sizeBytes = file.length(),
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertFile(entity)
            entity
        }

    suspend fun createNewSheet(title: String = "Untitled Sheet.csv"): OfficeFileEntity =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val localFilename = "$id.csv"
            val defaultGrid = SpreadsheetEngine.createEmptyGrid(rows = 12, cols = 6)
            val csvText = SpreadsheetEngine.serializeCsv(defaultGrid)
            val file = getLocalFile(localFilename)
            file.writeText(csvText, Charsets.UTF_8)
            val entity = OfficeFileEntity(
                id = id,
                title = title,
                type = OfficeFileType.SHEET.name,
                localFilename = localFilename,
                previewSnippet = "12 rows × 6 cols",
                sizeBytes = file.length(),
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertFile(entity)
            entity
        }

    suspend fun readTextContent(id: String): Pair<OfficeFileEntity?, String> =
        withContext(Dispatchers.IO) {
            val entity = dao.getFileById(id) ?: return@withContext null to ""
            val file = getLocalFile(entity.localFilename)
            val text = if (file.exists()) file.readText(Charsets.UTF_8) else ""
            entity to text
        }

    suspend fun saveTextContent(id: String, content: String, customPreview: String? = null) =
        withContext(Dispatchers.IO) {
            val entity = dao.getFileById(id) ?: return@withContext
            val file = getLocalFile(entity.localFilename)
            file.writeText(content, Charsets.UTF_8)
            val snippet = customPreview ?: extractTextPreview(content)
            dao.upsertFile(
                entity.copy(
                    previewSnippet = snippet,
                    sizeBytes = file.length(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun renameFile(id: String, rawNewTitle: String) = withContext(Dispatchers.IO) {
        val cleaned = rawNewTitle.trim()
        if (cleaned.isEmpty()) return@withContext
        val existing = dao.getFileById(id) ?: return@withContext
        val ext = existing.fileType.defaultExtension
        val finalTitle = if (cleaned.contains('.')) cleaned else "$cleaned.$ext"
        dao.renameFile(id, finalTitle, System.currentTimeMillis())
    }

    suspend fun deleteFile(id: String) = withContext(Dispatchers.IO) {
        val entity = dao.getFileById(id)
        if (entity != null) {
            val file = getLocalFile(entity.localFilename)
            if (file.exists()) file.delete()
            dao.deleteFileById(id)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<OfficeFileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(uri)
            val displayName = resolveDisplayName(uri) ?: "Imported File"
            val fileType = OfficeFileType.fromFilenameOrMime(displayName, mimeType)

            val supportedExtensions = setOf("txt", "md", "markdown", "csv", "pdf")
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext !in supportedExtensions && mimeType?.startsWith("text/") != true && mimeType != "application/pdf") {
                error("Unsupported format. Supported: .txt, .md, .csv, .pdf")
            }

            val id = UUID.randomUUID().toString()
            val storedExt = when {
                ext in supportedExtensions -> ext
                else -> fileType.defaultExtension
            }
            val localFilename = "$id.$storedExt"
            val destFile = getLocalFile(localFilename)

            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: error("Unable to read selected file")

            val preview = when (fileType) {
                OfficeFileType.PDF -> {
                    val pageCount = countPdfPages(destFile)
                    if (pageCount > 0) "$pageCount page${if (pageCount == 1) "" else "s"} · PDF" else "PDF Document"
                }
                OfficeFileType.SHEET -> {
                    val grid = SpreadsheetEngine.parseCsv(destFile.readText(Charsets.UTF_8))
                    val rows = grid.size
                    val cols = grid.maxOfOrNull { it.size } ?: 0
                    "$rows rows × $cols cols"
                }
                OfficeFileType.DOC -> {
                    extractTextPreview(destFile.readText(Charsets.UTF_8))
                }
            }

            val finalTitle = if (displayName.contains('.')) displayName else "$displayName.$storedExt"
            val entity = OfficeFileEntity(
                id = id,
                title = finalTitle,
                type = fileType.name,
                localFilename = localFilename,
                previewSnippet = preview,
                sizeBytes = destFile.length(),
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertFile(entity)
            entity
        }
    }

    suspend fun exportToUri(id: String, destUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = dao.getFileById(id) ?: error("File not found")
            val sourceFile = getLocalFile(entity.localFilename)
            if (!sourceFile.exists()) error("Local file missing")
            appContext.contentResolver.openOutputStream(destUri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: error("Cannot open export destination")
            Unit
        }
    }

    suspend fun readCsvFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: error("Unable to read CSV file")
        }
    }

    suspend fun getBackupEligibleFiles(): List<OfficeFileEntity> = withContext(Dispatchers.IO) {
        dao.getAllFilesOnce().filter {
            it.fileType == OfficeFileType.DOC || it.fileType == OfficeFileType.SHEET
        }
    }

    suspend fun restoreFromDriveBackup(
        preferredId: String?,
        title: String,
        fileType: OfficeFileType,
        content: String
    ): OfficeFileEntity = withContext(Dispatchers.IO) {
        if (fileType != OfficeFileType.DOC && fileType != OfficeFileType.SHEET) {
            error("Only documents and spreadsheets can be restored.")
        }
        val cleanTitle = title.replace("/", "_").replace("\\", "_").replace("..", "").trim()
            .ifBlank { "Restored.${fileType.defaultExtension}" }

        val existing = if (!preferredId.isNullOrBlank()) dao.getFileById(preferredId) else null
        val targetId = existing?.id ?: UUID.randomUUID().toString()
        val ext = when {
            fileType == OfficeFileType.SHEET -> "csv"
            cleanTitle.lowercase().endsWith(".txt") -> "txt"
            else -> "md"
        }
        val localFilename = existing?.localFilename ?: "$targetId.$ext"
        val destFile = getLocalFile(localFilename)

        // Enforce path confinement strictly inside workspaceDir
        val canonicalBase = workspaceDir.canonicalPath + File.separator
        val canonicalDest = destFile.canonicalPath
        if (!canonicalDest.startsWith(canonicalBase)) {
            error("Invalid local storage destination.")
        }

        destFile.writeText(content, Charsets.UTF_8)

        val preview = when (fileType) {
            OfficeFileType.SHEET -> {
                val grid = SpreadsheetEngine.parseCsv(content)
                val rows = grid.size
                val cols = grid.maxOfOrNull { it.size } ?: 0
                "$rows rows × $cols cols"
            }
            OfficeFileType.DOC -> extractTextPreview(content)
            OfficeFileType.PDF -> "PDF Document"
        }

        val entity = OfficeFileEntity(
            id = targetId,
            title = cleanTitle,
            type = fileType.name,
            localFilename = localFilename,
            previewSnippet = preview,
            sizeBytes = destFile.length(),
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertFile(entity)
        entity
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun countPdfPages(file: File): Int {
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        }.getOrDefault(0)
    }

    private fun extractTextPreview(text: String): String {
        val cleaned = text
            .lineSequence()
            .map { it.trim().trimStart('#', '-', '*', ' ') }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return if (cleaned.isEmpty()) "Empty document" else cleaned.take(90)
    }

    companion object {
        private const val KEY_DARK_THEME = "dark_theme"

        @Volatile
        private var instance: WorkspaceRepository? = null

        fun getInstance(context: Context): WorkspaceRepository {
            return instance ?: synchronized(this) {
                instance ?: WorkspaceRepository(context).also { instance = it }
            }
        }
    }
}
