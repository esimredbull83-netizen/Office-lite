package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "office_files")
data class OfficeFileEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String,
    val localFilename: String,
    val previewSnippet: String,
    val sizeBytes: Long,
    val updatedAt: Long,
    val lastBackedUpAt: Long = 0L
) {
    val fileType: OfficeFileType
        get() = runCatching { OfficeFileType.valueOf(type) }.getOrDefault(OfficeFileType.DOC)
}
