package com.example.data

enum class OfficeFileType(
    val badge: String,
    val defaultExtension: String,
    val mimeType: String
) {
    DOC(badge = "DOC", defaultExtension = "md", mimeType = "text/markdown"),
    SHEET(badge = "CSV", defaultExtension = "csv", mimeType = "text/csv"),
    PDF(badge = "PDF", defaultExtension = "pdf", mimeType = "application/pdf");

    companion object {
        fun fromFilenameOrMime(filename: String, mimeType: String?): OfficeFileType {
            val lowerName = filename.lowercase()
            val lowerMime = mimeType?.lowercase().orEmpty()
            return when {
                lowerName.endsWith(".pdf") || lowerMime.contains("pdf") -> PDF
                lowerName.endsWith(".csv") || lowerMime.contains("csv") -> SHEET
                else -> DOC
            }
        }
    }
}
