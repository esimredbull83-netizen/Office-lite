package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OfficeFileEntity::class], version = 2, exportSchema = false)
abstract class LiteOfficeDatabase : RoomDatabase() {
    abstract fun officeFileDao(): OfficeFileDao

    companion object {
        @Volatile
        private var instance: LiteOfficeDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE office_files ADD COLUMN lastBackedUpAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): LiteOfficeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiteOfficeDatabase::class.java,
                    "lite_office.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
