package nocom.rian.clipboardmanager.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {

        db.execSQL("""
            ALTER TABLE clipboardmanager
            ADD COLUMN window_label TEXT
        """.trimIndent())
    
        db.execSQL("""
            ALTER TABLE clipboardmanager
            ADD COLUMN activity_name TEXT
        """.trimIndent())
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clipboardmanager ADD COLUMN previous_app_name TEXT")
        db.execSQL("ALTER TABLE clipboardmanager ADD COLUMN previous_package_name TEXT")
        db.execSQL("ALTER TABLE clipboardmanager ADD COLUMN previous_window_label TEXT")
        db.execSQL("ALTER TABLE clipboardmanager ADD COLUMN previous_activity_name TEXT")
    }
}