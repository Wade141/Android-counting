package com.example.monthlyexpense

import com.example.monthlyexpense.search.*
import com.example.monthlyexpense.data.buildExpenseSearchPredicate

import com.example.monthlyexpense.classification.*
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.data.HistoryPage
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class ExpenseDao internal constructor(
    private val helper: ExpenseDatabaseHelper,
    private val categoryDao: CategoryDao
) {
    fun archivePastMonths(now: LocalDate = LocalDate.now()) {
        archivePastMonths(helper.writableDatabase, now)
    }

    internal fun archivePastMonths(database: SQLiteDatabase, now: LocalDate) {
        database.execSQL(
            "UPDATE expenses SET archived = 1 WHERE month_key < ? AND archived = 0",
            arrayOf(YearMonth.from(now).toString())
        )
    }

    fun todayTotal(date: LocalDate, zoneId: ZoneId): Long =
        todayTotal(helper.readableDatabase, date, zoneId)

    internal fun todayTotal(database: SQLiteDatabase, date: LocalDate, zoneId: ZoneId): Long {
        val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return database.rawQuery(
            "SELECT COALESCE(SUM(amount_cents), 0) FROM expenses WHERE spent_at >= ? AND spent_at < ? AND is_special = 0",
            arrayOf(start.toString(), end.toString())
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
    }

    fun addExpense(
        amountCents: Long,
        categoryKey: String,
        name: String,
        note: String,
        spentAt: Long = System.currentTimeMillis(),
        categoryExplicit: Boolean = true
    ): Boolean {
        val normalizedName = name.trim()
        val normalizedNote = note.trim()
        if (amountCents !in 1..MoneyLimits.MAX_CENTS || normalizedName.isEmpty() || normalizedName.length > 40 || normalizedNote.length > 100) {
            return false
        }
        if (categoryDao.categoryByKey(categoryKey) == null) return false
        return insertExpenseRecord(
            database = helper.writableDatabase,
            amountCents = amountCents,
            categoryKey = categoryKey,
            name = normalizedName,
            note = normalizedNote,
            spentAt = spentAt,
            source = ExpenseSource.MANUAL,
            merchant = null,
            sourceKey = null,
            explicitCategoryKey = categoryKey.takeIf { categoryExplicit }
        )
    }

    fun addAutomaticExpense(
        amountCents: Long,
        categoryKey: String,
        name: String,
        spentAt: Long,
        source: ExpenseSource,
        merchant: String?,
        sourceKey: String,
        notificationPrefix: String? = null,
        legacySourceKey: String? = null
    ): Boolean {
        require(source != ExpenseSource.MANUAL) { "Automatic expense requires an automatic source" }
        require(sourceKey.isNotBlank()) { "Automatic expense requires a source key" }
        require(notificationPrefix == null || notificationPrefix.isNotBlank())
        if (amountCents !in 1..MoneyLimits.MAX_CENTS || categoryDao.categoryByKey(categoryKey) == null) return false
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || normalizedName.length > 40) return false
        val normalizedMerchant = merchant?.normalizeMerchant()
        val database = helper.writableDatabase
        database.beginTransaction()
        return try {
            val sameNotificationUpdate = notificationPrefix != null &&
                hasRecentMatchingNotificationExpense(
                    database = database,
                    amountCents = amountCents,
                    spentAt = spentAt,
                    source = source,
                    notificationPrefix = notificationPrefix,
                    legacySourceKey = legacySourceKey
                )
            val inserted = if (
                sameNotificationUpdate || normalizedMerchant != null && hasRecentMatchingAutomaticExpense(
                    database, amountCents, spentAt, source, normalizedMerchant
                )
            ) false else insertExpenseRecord(
                database = database,
                amountCents = amountCents,
                categoryKey = categoryKey,
                name = normalizedName,
                note = "",
                spentAt = spentAt,
                source = source,
                merchant = normalizedMerchant,
                sourceKey = sourceKey
            )
            database.setTransactionSuccessful()
            inserted
        } finally {
            database.endTransaction()
        }
    }

    private fun hasRecentMatchingNotificationExpense(
        database: SQLiteDatabase,
        amountCents: Long,
        spentAt: Long,
        source: ExpenseSource,
        notificationPrefix: String,
        legacySourceKey: String?
    ): Boolean = database.query(
        "expenses",
        arrayOf("id"),
        "source = ? AND amount_cents = ? AND spent_at BETWEEN ? AND ? " +
            "AND (source_key LIKE ? OR source_key = ?)",
        arrayOf(
            source.name,
            amountCents.toString(),
            (spentAt - FUZZY_DEDUPLICATION_WINDOW_MILLIS).toString(),
            (spentAt + FUZZY_DEDUPLICATION_WINDOW_MILLIS).toString(),
            "$notificationPrefix:%",
            legacySourceKey.orEmpty()
        ),
        null,
        null,
        null,
        "1"
    ).use { it.moveToFirst() }

    private fun hasRecentMatchingAutomaticExpense(
        database: SQLiteDatabase,
        amountCents: Long,
        spentAt: Long,
        source: ExpenseSource,
        merchant: String
    ): Boolean = database.query(
        "expenses",
        arrayOf("id"),
        "source = ? AND amount_cents = ? AND merchant = ? AND spent_at BETWEEN ? AND ?",
        arrayOf(
            source.name,
            amountCents.toString(),
            merchant,
            (spentAt - FUZZY_DEDUPLICATION_WINDOW_MILLIS).toString(),
            (spentAt + FUZZY_DEDUPLICATION_WINDOW_MILLIS).toString()
        ),
        null,
        null,
        null,
        "1"
    ).use { it.moveToFirst() }

    private fun String.normalizeMerchant(): String? = trim()
        .replace(Regex("\\s+"), " ")
        .takeIf(String::isNotEmpty)

    internal fun insertExpenseRecord(
        database: SQLiteDatabase,
        amountCents: Long,
        categoryKey: String,
        name: String,
        note: String,
        spentAt: Long,
        source: ExpenseSource,
        merchant: String?,
        sourceKey: String?,
        explicitCategoryKey: String? = null,
        historyCategoryKey: String? = null
    ): Boolean {
        database.beginTransaction()
        return try {
        val classification = ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), helper.categoryRuleDao.loadRuleSets(database), explicitCategoryKey, historyCategoryKey)
        val date = Instant.ofEpochMilli(spentAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val inserted = database.insertWithOnConflict(
            "expenses",
            null,
            ContentValues().apply {
                put("amount_cents", amountCents)
                put("category", classification.categoryKey)
                put("classification_origin", classification.origin.name)
                put("classification_match_json", ClassificationJson.encodeHits(classification.hits))
                put("name", name)
                put("note", note)
                put("spent_at", spentAt)
                put("month_key", YearMonth.from(date).toString())
                put("archived", if (YearMonth.from(date) < YearMonth.now()) 1 else 0)
                put("source", source.name)
                if (merchant == null) putNull("merchant") else put("merchant", merchant)
                if (sourceKey == null) putNull("source_key") else put("source_key", sourceKey)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        database.setTransactionSuccessful()
        inserted
        } finally { database.endTransaction() }
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("expenses", "id = ?", arrayOf(id.toString()))
    }

    fun findById(id: Long): ExpenseRecord? =
        queryExpenseRecords(helper.readableDatabase, "e.id = ?", arrayOf(id.toString()), 1).singleOrNull()

    fun updateExpense(
        id: Long,
        name: String,
        note: String,
        categoryKey: String,
        date: LocalDate? = null,
        time: java.time.LocalTime? = null,
        categoryExplicit: Boolean = false,
        isSpecial: Boolean? = null
    ): Boolean {
        if (date != null && date.isAfter(LocalDate.now())) return false
        val normalizedName = name.trim()
        val normalizedNote = note.trim()
        if (normalizedName.isEmpty() || normalizedName.length > 40 || normalizedNote.length > 100) {
            return false
        }
        if (categoryDao.categoryByKey(categoryKey) == null) return false
        val oldCategory = helper.readableDatabase.rawQuery("SELECT category FROM expenses WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else return false }
        val updatedDateTime = if (date != null || time != null) {
            val original = helper.readableDatabase.rawQuery(
                "SELECT spent_at FROM expenses WHERE id = ?", arrayOf(id.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return false }
            val zone = ZoneId.systemDefault()
            val originalDateTime = Instant.ofEpochMilli(original).atZone(zone)
            (date ?: originalDateTime.toLocalDate())
                .atTime(time ?: originalDateTime.toLocalTime()).atZone(zone)
        } else null
        return helper.writableDatabase.update(
            "expenses",
            ContentValues().apply {
                put("name", normalizedName)
                put("note", normalizedNote)
                put("category", categoryKey)
                if (isSpecial != null) put("is_special", if (isSpecial) 1 else 0)
                if (categoryExplicit || oldCategory != categoryKey) {
                    put("classification_origin", ClassificationOrigin.MANUAL.name)
                    putNull("classification_match_json")
                }
                if (updatedDateTime != null) {
                    put("spent_at", updatedDateTime.toInstant().toEpochMilli())
                    put("month_key", YearMonth.from(updatedDateTime).toString())
                    put("archived", if (YearMonth.from(updatedDateTime) < YearMonth.now()) 1 else 0)
                }
            },
            "id = ?",
            arrayOf(id.toString())
        ) == 1
    }

    fun currentMonthRecords(now: LocalDate = LocalDate.now()): List<ExpenseRecord> =
        currentMonthRecords(helper.readableDatabase, now)

    internal fun currentMonthRecords(
        database: SQLiteDatabase,
        now: LocalDate
    ): List<ExpenseRecord> = queryExpenseRecords(
        database,
        "e.month_key = ? AND e.archived = 0",
        arrayOf(YearMonth.from(now).toString())
    )

    fun searchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int = 50): ExpenseSearchPage {
        require(limit in 1..50) { "Search page limit must be between 1 and 50" }
        val predicate = buildExpenseSearchPredicate(query)
        val selection = predicate.selection + if (cursor == null) "" else
            " AND (e.spent_at < ? OR (e.spent_at = ? AND e.id < ?))"
        val args = predicate.args + if (cursor == null) emptyList() else
            listOf(cursor.spentAt.toString(), cursor.spentAt.toString(), cursor.id.toString())
        val db = helper.readableDatabase
        db.beginTransactionNonExclusive()
        return try {
            val records = queryExpenseRecords(db, selection, args.toTypedArray(), limit + 1)
            val more = records.size > limit
            val items = records.take(limit)
            val summary = if (cursor != null) null else db.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(e.amount_cents), 0) FROM expenses e WHERE ${predicate.selection}",
                predicate.args.toTypedArray()
            ).use { it.moveToFirst(); ExpenseSearchSummary(it.getLong(0), it.getLong(1)) }
            db.setTransactionSuccessful()
            ExpenseSearchPage(items, if (more) items.last().let { ExpenseSearchCursor(it.spentAt, it.id) } else null, more, summary)
        } finally { db.endTransaction() }
    }

    fun historyPage(
        cursor: HistoryCursor?,
        limit: Int = HISTORY_PAGE_SIZE,
        month: YearMonth? = null
    ): HistoryPage {
        require(limit > 0) { "History page limit must be positive" }
        val selection: String
        val args: Array<String>
        if (cursor == null) {
            selection = "e.archived = 1"
            args = emptyArray()
        } else {
            selection = "e.archived = 1 AND " +
                "(e.spent_at < ? OR (e.spent_at = ? AND e.id < ?))"
            args = arrayOf(cursor.spentAt.toString(), cursor.spentAt.toString(), cursor.id.toString())
        }
        val monthSelection = if (month == null) selection else "$selection AND e.month_key = ?"
        val monthArgs = if (month == null) args else args + month.toString()
        val records = queryExpenseRecords(helper.readableDatabase, monthSelection, monthArgs, limit + 1)
        val hasMore = records.size > limit
        val items = if (hasMore) records.take(limit) else records
        val nextCursor = if (hasMore) {
            items.last().let { HistoryCursor(spentAt = it.spentAt, id = it.id) }
        } else {
            null
        }
        val months = helper.readableDatabase.rawQuery(
            "SELECT month_key, SUM(amount_cents), COUNT(*) FROM expenses " +
                "WHERE archived = 1 GROUP BY month_key ORDER BY month_key DESC",
            emptyArray()
        ).use { result ->
            buildList {
                while (result.moveToNext()) {
                    add(com.example.monthlyexpense.data.HistoryMonth(
                        YearMonth.parse(result.getString(0)), result.getLong(1), result.getInt(2)
                    ))
                }
            }
        }
        return HistoryPage(items = items, nextCursor = nextCursor, hasMore = hasMore, months = months)
    }

    private fun queryExpenseRecords(
        database: SQLiteDatabase,
        selection: String,
        args: Array<String>,
        limit: Int? = null
    ): List<ExpenseRecord> {
        val fallback = categoryDao.categoryByKey(BuiltInCategoryKeys.OTHER, database)!!
        val limitClause = if (limit == null) "" else " LIMIT ?"
        val queryArgs = if (limit == null) args else args + limit.toString()
        return database.rawQuery(
            """SELECT e.id, e.amount_cents, e.name, e.note, e.spent_at, e.source, e.merchant,
                c.category_key, c.name, c.color_argb, c.built_in, c.sort_order, e.classification_origin, e.classification_match_json, e.is_special
                FROM expenses e LEFT JOIN categories c ON c.category_key = e.category
                WHERE $selection ORDER BY e.spent_at DESC, e.id DESC$limitClause""".trimIndent(),
            queryArgs
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val category = if (cursor.isNull(7)) fallback else ExpenseCategory(
                        key = cursor.getString(7),
                        name = cursor.getString(8),
                        colorArgb = cursor.getLong(9),
                        builtIn = cursor.getInt(10) != 0,
                        sortOrder = cursor.getInt(11)
                    )
                    add(
                        ExpenseRecord(
                            id = cursor.getLong(0),
                            amountCents = cursor.getLong(1),
                            name = cursor.getString(2),
                            note = cursor.getString(3),
                            spentAt = cursor.getLong(4),
                            source = ExpenseSource.valueOf(cursor.getString(5)),
                            merchant = if (cursor.isNull(6)) null else cursor.getString(6),
                            category = category,
                            classificationOrigin = ClassificationOrigin.valueOf(cursor.getString(12)),
                            classificationHits = ClassificationJson.decodeHits(if (cursor.isNull(13)) null else cursor.getString(13)),
                            isSpecial = cursor.getInt(14) != 0
                        )
                    )
                }
            }
        }
    }

    private companion object {
        const val FUZZY_DEDUPLICATION_WINDOW_MILLIS = 3_000L
    }
}
