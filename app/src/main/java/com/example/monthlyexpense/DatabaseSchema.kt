package com.example.monthlyexpense

import android.database.sqlite.SQLiteDatabase

internal object DatabaseSchema {
    const val NAME = "expenses.db"
    const val VERSION = 9

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                category TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                spent_at INTEGER NOT NULL,
                month_key TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                merchant TEXT,
                source_key TEXT UNIQUE,
                classification_origin TEXT NOT NULL DEFAULT 'LEGACY',
                classification_match_json TEXT,
                is_special INTEGER NOT NULL DEFAULT 0 CHECK(is_special IN (0, 1))
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_expenses_month_order " +
                "ON expenses(month_key, archived, spent_at DESC, id DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_expenses_archived_order " +
                "ON expenses(archived, spent_at DESC, id DESC)"
        )
        db.execSQL("CREATE INDEX idx_expenses_spent_at ON expenses(spent_at)")
        createCategoryTable(db)
        seedBuiltInCategories(db)
        createMonthlyBudgetTable(db)
        createNotificationEvents(db)
        createNotificationProtocol(db)
        createCategoryRules(db)
    }

    fun createCategoryRules(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE category_rule_sets (category_key TEXT PRIMARY KEY, enabled INTEGER NOT NULL, match_field TEXT NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE category_keyword_rules (id TEXT PRIMARY KEY, category_key TEXT NOT NULL, keyword TEXT NOT NULL, normalized_keyword TEXT NOT NULL, match_mode TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_category_keywords_category ON category_keyword_rules(category_key)")
    }

    fun createNotificationEvents(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS notification_events (
            event_id TEXT PRIMARY KEY,
            notification_identity TEXT NOT NULL,
            source TEXT NOT NULL,
            observed_at INTEGER NOT NULL,
            action TEXT NOT NULL,
            state TEXT NOT NULL,
            amount_cents INTEGER,
            amounts TEXT NOT NULL,
            merchant TEXT,
            kind TEXT NOT NULL,
            reasons TEXT NOT NULL,
            rule_version TEXT NOT NULL,
            legacy_event_id TEXT,
            legacy_notification_key TEXT,
            transaction_ref TEXT,
            occurred_at INTEGER NOT NULL,
            time_origin TEXT NOT NULL,
            expense_id INTEGER,
            book_generation TEXT,
            source_generation INTEGER,
            disposition_reason TEXT NOT NULL DEFAULT 'NONE'
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notification_events_state ON notification_events(state, observed_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notification_events_identity ON notification_events(notification_identity, observed_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notification_events_transaction ON notification_events(transaction_ref)")
    }

    fun createNotificationProtocol(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ledger_metadata (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), book_generation TEXT NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO ledger_metadata(singleton, book_generation) VALUES(1, ?)", arrayOf(java.util.UUID.randomUUID().toString()))
        db.execSQL("""CREATE TABLE IF NOT EXISTS restore_operation (
            restore_id TEXT PRIMARY KEY, book_generation TEXT NOT NULL, target_settings_json TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('DB_COMMITTED','COMPLETED')), created_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS replay_item_receipts (
            operation_id TEXT NOT NULL, item_id TEXT NOT NULL, event_id TEXT NOT NULL,
            book_generation TEXT NOT NULL, result TEXT NOT NULL, completed_at INTEGER NOT NULL,
            PRIMARY KEY(operation_id, item_id), UNIQUE(operation_id, event_id)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS quarantine_confirmation_receipts (
            book_generation TEXT NOT NULL, event_id TEXT NOT NULL, result TEXT NOT NULL,
            completed_at INTEGER NOT NULL, PRIMARY KEY(book_generation, event_id)
        )""")
    }

    fun createCategoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS categories (
                category_key TEXT PRIMARY KEY,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                color_argb INTEGER NOT NULL,
                built_in INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    fun seedBuiltInCategories(db: SQLiteDatabase) {
        val categories = listOf(
            arrayOf(BuiltInCategoryKeys.FOOD, "饮食", 0xFFFF8A65L, 0),
            arrayOf(BuiltInCategoryKeys.ENTERTAINMENT, "娱乐", 0xFF7E8CE0L, 1),
            arrayOf(BuiltInCategoryKeys.TRAVEL, "出行", 0xFF42A5F5L, 2),
            arrayOf(BuiltInCategoryKeys.SHOPPING, "购物", 0xFFAB47BCL, 3),
            arrayOf(BuiltInCategoryKeys.BILLS, "缴费", 0xFFFFB74DL, 4),
            arrayOf(BuiltInCategoryKeys.OTHER, "其他", 0xFF58BFA6L, 5)
        )
        categories.forEach { category ->
            db.execSQL(
                "INSERT OR IGNORE INTO categories(category_key, name, color_argb, built_in, sort_order) VALUES(?, ?, ?, 1, ?)",
                category
            )
        }
    }

    fun createMonthlyBudgetTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS monthly_budgets (
                month_key TEXT PRIMARY KEY,
                amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0)
            )""".trimIndent()
        )
    }
}
