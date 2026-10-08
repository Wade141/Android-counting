package com.example.monthlyexpense

import android.database.sqlite.SQLiteDatabase

internal object DatabaseMigrations {
    fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN is_special INTEGER NOT NULL DEFAULT 0 CHECK(is_special IN (0, 1))")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN classification_origin TEXT NOT NULL DEFAULT 'LEGACY'")
            db.execSQL("ALTER TABLE expenses ADD COLUMN classification_match_json TEXT")
            DatabaseSchema.createCategoryRules(db)
        }
        if (oldVersion < 6) DatabaseSchema.createNotificationEvents(db)
        if (oldVersion < 7) {
            // Versions before 6 create the latest event table above; v6 requires additive columns.
            if (oldVersion >= 6) {
                db.execSQL("ALTER TABLE notification_events ADD COLUMN book_generation TEXT")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN source_generation INTEGER")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN disposition_reason TEXT NOT NULL DEFAULT 'NONE'")
            }
            db.execSQL("UPDATE notification_events SET disposition_reason = 'LEGACY_UNKNOWN' WHERE state = 'IGNORED'")
            DatabaseSchema.createNotificationProtocol(db)
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN source_key TEXT")
            db.execSQL("CREATE UNIQUE INDEX idx_expenses_source ON expenses(source_key)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE expenses ADD COLUMN merchant TEXT")
        }
        if (oldVersion < 4) {
            DatabaseSchema.createCategoryTable(db)
            DatabaseSchema.seedBuiltInCategories(db)
            db.execSQL("ALTER TABLE expenses ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE expenses SET name = note")
            db.execSQL("UPDATE expenses SET note = ''")
            DatabaseSchema.createMonthlyBudgetTable(db)
        }
        if (oldVersion < 5) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_expenses_month_order " +
                    "ON expenses(month_key, archived, spent_at DESC, id DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_expenses_archived_order " +
                    "ON expenses(archived, spent_at DESC, id DESC)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_spent_at ON expenses(spent_at)")
            db.execSQL("DROP INDEX IF EXISTS idx_expenses_month")
        }
    }
}
