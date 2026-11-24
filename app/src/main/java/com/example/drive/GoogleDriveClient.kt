package com.example.drive

import com.example.data.OfficeFileType
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DriveUserInfo(
    val displayName: String,
    val emailAddress: String
)

data class DriveBackupFile(
    val driveFileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedTime: String,
    val liteOfficeId: String?,
    val fileType: OfficeFileType
)

class DriveUnauthorizedException(message: String) : IOException(message)
class DriveApiException(message: String) : IOException(message)

class GoogleDriveClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetchDriveUser(accessToken: String): DriveUserInfo? = withContext(Dispatchers.IO) {
        val url = "$DRIVE_API_BASE/about".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "user(displayName,emailAddress)")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        executeJson(request) { json ->
            val userObj = json.optJSONObject("user") ?: return@executeJson null
            val email = userObj.optString("emailAddress", "")
            val name = userObj.optString("displayName", email)
            if (email.isBlank() && name.isBlank()) null
            else DriveUserInfo(displayName = name.ifBlank { email }, emailAddress = email)
        }
    }

    suspend fun ensureBackupFolderId(accessToken: String): String = withContext(Dispatchers.IO) {
        val escapedFolder = escapeDriveQueryLiteral(BACKUP_FOLDER_NAME)
        val query = "name = '$escapedFolder' and mimeType = '$FOLDER_MIME' and trashed = false"
        val searchUrl = "$DRIVE_API_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "1")
            .build()

        val searchReq = Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val existingId = executeJson(searchReq) { json ->
            val files = json.optJSONArray("files") ?: JSONArray()
            if (files.length() > 0) files.getJSONObject(0).optString("id", "").takeIf { it.isNotBlank() } else null
        }
        if (existingId != null) return@withContext existingId

        // Create folder
        val metaJson = JSONObject()
            .put("name", BACKUP_FOLDER_NAME)
            .put("mimeType", FOLDER_MIME)
        val createReq = Request.Builder()
            .url("$DRIVE_API_BASE/files?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeJson(createReq) { json ->
            json.optString("id", "").ifBlank {
                throw DriveApiException("Google Drive did not return a folder ID.")
            }
        }
    }

    suspend fun listBackups(accessToken: String, folderId: String): List<DriveBackupFile> =
        withContext(Dispatchers.IO) {
            validateDriveId(folderId)
            val escapedParent = escapeDriveQueryLiteral(folderId)
            val query = "'$escapedParent' in parents and mimeType != '$FOLDER_MIME' and trashed = false"
            val url = "$DRIVE_API_BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("orderBy", "modifiedTime desc")
                .addQueryParameter("pageSize", "100")
                .addQueryParameter(
                    "fields",
                    "files(id,name,mimeType,size,modifiedTime,appProperties)"
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            executeJson(request) { json ->
                val filesArray = json.optJSONArray("files") ?: JSONArray()
                buildList {
                    for (i in 0 until filesArray.length()) {
                        val obj = filesArray.getJSONObject(i)
                        val id = obj.optString("id", "")
                        val name = obj.optString("name", "")
                        val mime = obj.optString("mimeType", "text/plain")
                        if (id.isBlank() || name.isBlank()) continue

                        val appProps = obj.optJSONObject("appProperties")
                        val localId = appProps?.optString("liteOfficeId", "")?.takeIf { it.isNotBlank() }
                        val resolvedType = OfficeFileType.fromFilenameOrMime(name, mime)
                        // Only surface Document and Spreadsheet backups
                        if (resolvedType == OfficeFileType.DOC || resolvedType == OfficeFileType.SHEET) {
                            add(
                                DriveBackupFile(
                                    driveFileId = id,
                                    name = name,
                                    mimeType = mime,
                                    sizeBytes = obj.optString("size", "0").toLongOrNull() ?: 0L,
                                    modifiedTime = obj.optString("modifiedTime", ""),
                                    liteOfficeId = localId,
                                    fileType = resolvedType
                                )
                            )
                        }
                    }
                }
            }
        }

    suspend fun uploadOrUpdateBackup(
        accessToken: String,
        folderId: String,
        localId: String,
        title: String,
        fileType: OfficeFileType,
        content: String
    ): DriveBackupFile = withContext(Dispatchers.IO) {
        if (fileType == OfficeFileType.PDF) {
            throw DriveApiException("Only documents and spreadsheets can be backed up.")
        }
        validateDriveId(folderId)

        val existingDriveId = findExistingFileId(accessToken, folderId, localId, title)
        val mimeType = when (fileType) {
            OfficeFileType.SHEET -> "text/csv"
            OfficeFileType.DOC -> if (title.lowercase().endsWith(".txt")) "text/plain" else "text/markdown"
            OfficeFileType.PDF -> "application/pdf"
        }

        val metadata = JSONObject().apply {
            put("name", title)
            put("mimeType", mimeType)
            if (existingDriveId == null) {
                put("parents", JSONArray().put(folderId))
            }
            put(
                "appProperties",
                JSONObject()
                    .put("liteOfficeId", localId)
                    .put("liteOfficeType", fileType.name)
            )
        }

        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()

        val targetUrl = if (existingDriveId != null) {
            validateDriveId(existingDriveId)
            "$DRIVE_UPLOAD_BASE/files".toHttpUrl().newBuilder()
                .addPathSegment(existingDriveId)
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", "id,name,mimeType,size,modifiedTime,appProperties")
                .build()
        } else {
            "$DRIVE_UPLOAD_BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", "id,name,mimeType,size,modifiedTime,appProperties")
                .build()
        }

        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $accessToken")

        val request = if (existingDriveId != null) {
            requestBuilder.patch(multipartBody).build()
        } else {
            requestBuilder.post(multipartBody).build()
        }

        executeJson(request) { obj ->
            DriveBackupFile(
                driveFileId = obj.optString("id", existingDriveId.orEmpty()),
                name = obj.optString("name", title),
                mimeType = obj.optString("mimeType", mimeType),
                sizeBytes = obj.optString("size", content.length.toString()).toLongOrNull() ?: 0L,
                modifiedTime = obj.optString("modifiedTime", ""),
                liteOfficeId = localId,
                fileType = fileType
            )
        }
    }

    suspend fun downloadTextContent(accessToken: String, driveFileId: String): String =
        withContext(Dispatchers.IO) {
            validateDriveId(driveFileId)
            val url = "$DRIVE_API_BASE/files".toHttpUrl().newBuilder()
                .addPathSegment(driveFileId)
                .addQueryParameter("alt", "media")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful) {
                    checkResponseErrors(response.code, body?.string())
                }
                val bytes = body?.bytes() ?: ByteArray(0)
                if (bytes.size > MAX_RESTORE_BYTES) {
                    throw DriveApiException("Backup file exceeds the 10 MB limit.")
                }
                bytes.toString(Charsets.UTF_8)
            }
        }

    suspend fun deleteBackup(accessToken: String, driveFileId: String) =
        withContext(Dispatchers.IO) {
            validateDriveId(driveFileId)
            val url = "$DRIVE_API_BASE/files".toHttpUrl().newBuilder()
                .addPathSegment(driveFileId)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    checkResponseErrors(response.code, response.body?.string())
                }
            }
        }

    private fun findExistingFileId(
        accessToken: String,
        folderId: String,
        localId: String,
        title: String
    ): String? {
        val escapedParent = escapeDriveQueryLiteral(folderId)
        val escapedLocalId = escapeDriveQueryLiteral(localId)
        val queryById =
            "'$escapedParent' in parents and appProperties has { key='liteOfficeId' and value='$escapedLocalId' } and trashed = false"
        val idMatch = queryFirstFileId(accessToken, queryById)
        if (idMatch != null) return idMatch

        val escapedTitle = escapeDriveQueryLiteral(title)
        val queryByName = "'$escapedParent' in parents and name = '$escapedTitle' and trashed = false"
        return queryFirstFileId(accessToken, queryByName)
    }

    private fun queryFirstFileId(accessToken: String, query: String): String? {
        val url = "$DRIVE_API_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return executeJson(request) { json ->
            val arr = json.optJSONArray("files") ?: JSONArray()
            if (arr.length() > 0) arr.getJSONObject(0).optString("id", "").takeIf { it.isNotBlank() } else null
        }
    }

    private inline fun <T> executeJson(request: Request, parse: (JSONObject) -> T): T {
        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                checkResponseErrors(response.code, rawBody)
            }
            val json = if (rawBody.isBlank()) JSONObject() else JSONObject(rawBody)
            return parse(json)
        }
    }

    private fun checkResponseErrors(code: Int, rawBody: String?) {
        val apiMessage = runCatching {
            JSONObject(rawBody.orEmpty()).optJSONObject("error")?.optString("message", "")
        }.getOrNull().orEmpty()

        if (code == 401) {
            throw DriveUnauthorizedException("Google Drive session expired. Please reconnect.")
        }
        val msg = apiMessage.ifBlank { "Google Drive request failed (HTTP $code)." }
        throw DriveApiException(msg)
    }

    private fun validateDriveId(id: String) {
        if (!DRIVE_ID_PATTERN.matches(id)) {
            throw DriveApiException("Invalid Google Drive file identifier.")
        }
    }

    private fun escapeDriveQueryLiteral(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val BACKUP_FOLDER_NAME = "Lite Office Backups"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val MAX_RESTORE_BYTES = 10 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DRIVE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    }
}
