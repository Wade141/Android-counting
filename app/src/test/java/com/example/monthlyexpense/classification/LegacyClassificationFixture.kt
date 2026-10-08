package com.example.monthlyexpense.classification

import android.database.sqlite.SQLiteDatabase

/** Downgraded fixtures must remove v8 columns/tables, not only change user_version. */
internal fun removeClassificationFromLegacyFixture(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE expenses_before_keywords (
        id INTEGER PRIMARY KEY AUTOINCREMENT, amount_cents INTEGER NOT NULL, category TEXT NOT NULL,
        name TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', spent_at INTEGER NOT NULL,
        month_key TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
        source TEXT NOT NULL DEFAULT 'MANUAL', merchant TEXT, source_key TEXT UNIQUE)""")
    db.execSQL("INSERT INTO expenses_before_keywords SELECT id, amount_cents, category, name, note, spent_at, month_key, archived, source, merchant, source_key FROM expenses")
    db.execSQL("DROP TABLE expenses")
    db.execSQL("ALTER TABLE expenses_before_keywords RENAME TO expenses")
    db.execSQL("CREATE INDEX idx_expenses_month_order ON expenses(month_key, archived, spent_at DESC, id DESC)")
    db.execSQL("CREATE INDEX idx_expenses_archived_order ON expenses(archived, spent_at DESC, id DESC)")
    db.execSQL("CREATE INDEX idx_expenses_spent_at ON expenses(spent_at)")
    db.execSQL("DROP TABLE category_keyword_rules")
    db.execSQL("DROP TABLE category_rule_sets")
}
