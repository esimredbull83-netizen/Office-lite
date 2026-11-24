package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OfficeFileEntity::class], version = 1, exportSchema = false)
abstract class LiteOfficeDatabase : RoomDatabase() {
    abstract fun officeFileDao(): OfficeFileDao

    companion object {
        @Volatile
        private var instance: LiteOfficeDatabase? = null

        fun getInstance(context: Context): LiteOfficeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiteOfficeDatabase::class.java,
                    "lite_office.db"
                ).build().also { instance = it }
            }
        }
    }
}
