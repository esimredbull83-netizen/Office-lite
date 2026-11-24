package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfficeFileDao {
    @Query("SELECT * FROM office_files ORDER BY updatedAt DESC")
    fun observeAllFiles(): Flow<List<OfficeFileEntity>>

    @Query("SELECT * FROM office_files ORDER BY updatedAt DESC")
    suspend fun getAllFilesOnce(): List<OfficeFileEntity>

    @Query("SELECT * FROM office_files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: String): OfficeFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: OfficeFileEntity)

    @Query("UPDATE office_files SET title = :newTitle, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameFile(id: String, newTitle: String, updatedAt: Long)

    @Query("DELETE FROM office_files WHERE id = :id")
    suspend fun deleteFileById(id: String)
}
