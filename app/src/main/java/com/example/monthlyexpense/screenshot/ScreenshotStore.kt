package com.example.monthlyexpense.screenshot

import com.example.monthlyexpense.classification.*
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScreenshotExpenseInput(
    val taskId: String,
    val imageHash: String?,
    val amountCents: Long,
    val name: String,
    val note: String,
    val categoryKey: String,
    val spentAt: Long,
    val merchant: String? = null,
    val categoryExplicit: Boolean = true
)

data class DuplicateExpense(
    val id: Long,
    val amountCents: Long,
    val name: String,
    val spentAt: Long,
    val reason: String
)

sealed interface ScreenshotSaveResult {
    data class Saved(val id: Long) : ScreenshotSaveResult
    data class AlreadySaved(val id: Long) : ScreenshotSaveResult
    data class Duplicate(val matches: List<DuplicateExpense>) : ScreenshotSaveResult
    data class Invalid(val message: String) : ScreenshotSaveResult
    data object Stale : ScreenshotSaveResult
}

interface ScreenshotStore {
    suspend fun loadCategories(): List<ExpenseCategory>
    suspend fun suggestClassification(merchant: String?, name: String): ResolvedCategory =
        ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), emptyList(), historyCategoryKey = suggestCategory(merchant ?: name))
    suspend fun suggestCategory(merchant: String): String?
    /** Call only after explicit confirmation of a successful RMB expense. */
    suspend fun save(input: ScreenshotExpenseInput, allowDuplicate: Boolean): ScreenshotSaveResult
}

class ScreenshotExpenseStore(
    private val database: ExpenseDatabaseHelper,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onSaved: () -> Unit = {}
) : ScreenshotStore {
    // A store constructed during restoration must never acquire a fresh draft epoch later.
    private val draftTicket = coordinator.mutationTicket()

    override suspend fun loadCategories(): List<ExpenseCategory> = withContext(ioDispatcher) {
        val ticket = draftTicket ?: return@withContext emptyList()
        when (val result = coordinator.runRead(ticket) { database.categoryDao.categories() }) {
            is CoordinatedMutation.Executed -> result.value
            CoordinatedMutation.Rejected -> emptyList()
        }
    }

    override suspend fun suggestCategory(merchant: String): String? = withContext(ioDispatcher) {
        val ticket = draftTicket ?: return@withContext null
        val normalized = normalizeName(merchant)
        if (normalized.isEmpty()) return@withContext null
        val result = coordinator.runRead(ticket) {
            // Only persisted expenses participate; OCR drafts never reach this table.
            database.readableDatabase.rawQuery(
                "SELECT e.category, e.name, e.merchant FROM expenses e " +
                    "JOIN categories c ON c.category_key = e.category " +
                    "ORDER BY e.spent_at DESC, e.id DESC", null
            ).use { cursor ->
                var category: String? = null
                while (cursor.moveToNext()) {
                    if (normalizeName(cursor.getString(1)) == normalized ||
                        (!cursor.isNull(2) && normalizeName(cursor.getString(2)) == normalized)) {
                        category = cursor.getString(0)
                        break
                    }
                }
                category
            }
        }
        when (result) {
            is CoordinatedMutation.Executed -> result.value
            CoordinatedMutation.Rejected -> null
        }
    }

    override suspend fun suggestClassification(merchant: String?, name: String): ResolvedCategory = withContext(ioDispatcher) {
        val fallback = ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), emptyList())
        val ticket = draftTicket ?: return@withContext fallback
        when (val result = coordinator.runRead(ticket) {
            val db = database.readableDatabase
            db.beginTransactionNonExclusive()
            try {
                val rules = database.categoryRuleDao.loadRuleSets(db)
                val match = ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), rules)
                (if (match.origin == ClassificationOrigin.DEFAULT) ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), rules,
                    historyCategoryKey = historyCategory(db, merchant ?: name)) else match).also { db.setTransactionSuccessful() }
            } finally { db.endTransaction() }
        }) {
            is CoordinatedMutation.Executed -> result.value
            CoordinatedMutation.Rejected -> fallback
        }
    }

    private fun historyCategory(db: SQLiteDatabase, text: String): String? {
        val normalized = normalizeName(text)
        if (normalized.isEmpty()) return null
        return db.rawQuery("SELECT e.category, e.name, e.merchant FROM expenses e JOIN categories c ON c.category_key = e.category ORDER BY e.spent_at DESC, e.id DESC", null).use { cursor ->
            var category: String? = null
            while (cursor.moveToNext()) {
                if (normalizeName(cursor.getString(1)) == normalized || (!cursor.isNull(2) && normalizeName(cursor.getString(2)) == normalized)) {
                    category = cursor.getString(0); break
                }
            }
            category
        }
    }

    override suspend fun save(input: ScreenshotExpenseInput, allowDuplicate: Boolean): ScreenshotSaveResult =
        withContext(ioDispatcher) {
            val ticket = draftTicket ?: return@withContext ScreenshotSaveResult.Stale
            when (val result = coordinator.runMutation(ticket) {
                // beginRestore can invalidate a ticket after mutex admission. Hold its state
                // guard through the synchronous transaction so restore and commit have one order.
                when (val committed = coordinator.commitIfCurrent(ticket) {
                    saveTransaction(input, allowDuplicate).also { if (it is ScreenshotSaveResult.Saved) onSaved() }
                }) {
                    is CoordinatedMutation.Executed -> committed.value
                    CoordinatedMutation.Rejected -> ScreenshotSaveResult.Stale
                }
            }) {
                is CoordinatedMutation.Executed -> result.value
                CoordinatedMutation.Rejected -> ScreenshotSaveResult.Stale
            }
        }

    private fun saveTransaction(input: ScreenshotExpenseInput, allowDuplicate: Boolean): ScreenshotSaveResult {
        val uuid = runCatching { UUID.fromString(input.taskId).toString() }.getOrNull()
        if (uuid == null || !uuid.equals(input.taskId, ignoreCase = true)) {
            return ScreenshotSaveResult.Invalid("任务标识无效")
        }
        val hash = input.imageHash?.lowercase(Locale.ROOT)
        if (hash != null && !hash.matches(Regex("[0-9a-f]{64}"))) {
            return ScreenshotSaveResult.Invalid("图片摘要无效")
        }
        val sourceKey = "ocr:${hash ?: "none"}:$uuid"
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            val result = saveInTransaction(db, input, uuid, hash, sourceKey, allowDuplicate)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    private fun saveInTransaction(
        db: SQLiteDatabase,
        input: ScreenshotExpenseInput,
        uuid: String,
        hash: String?,
        sourceKey: String,
        allowDuplicate: Boolean
    ): ScreenshotSaveResult {
        // Exact unique key is the fast path; the suffix also protects a task whose hash changed.
        val existing = idBySourceKey(db, sourceKey) ?: db.rawQuery(
            "SELECT id FROM expenses WHERE source_key LIKE ? ORDER BY id LIMIT 1",
            arrayOf("ocr:%:$uuid")
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null) return ScreenshotSaveResult.AlreadySaved(existing)

        val name = input.name.trim()
        val note = input.note.trim()
        if (input.amountCents !in 1..MoneyLimits.MAX_CENTS || name.isEmpty() || name.length > 40 || note.length > 100) {
            return ScreenshotSaveResult.Invalid("金额、名称或备注无效")
        }
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(input.spentAt).atZone(zone).toLocalDate()
        if (date.year !in 1..9999) return ScreenshotSaveResult.Invalid("日期无效")
        if (database.categoryDao.categoryByKey(input.categoryKey, db) == null) {
            return ScreenshotSaveResult.Invalid("分类已不存在，请重新选择")
        }
        if (!allowDuplicate) {
            val matches = linkedMapOf<Long, DuplicateExpense>()
            fun collect(selection: String, args: Array<String>, reason: String, sameName: Boolean) {
                db.query("expenses", arrayOf("id", "amount_cents", "name", "spent_at"),
                    selection, args, null, null, "spent_at DESC, id DESC").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (sameName && normalizeName(cursor.getString(2)) != normalizeName(name)) continue
                        val id = cursor.getLong(0)
                        matches.putIfAbsent(id, DuplicateExpense(id, cursor.getLong(1), cursor.getString(2), cursor.getLong(3), reason))
                    }
                }
            }
            if (hash != null) collect("source_key LIKE ?", arrayOf("ocr:$hash:%"), "相同截图", false)
            collect("amount_cents = ? AND spent_at >= ? AND spent_at < ?", arrayOf(
                input.amountCents.toString(), date.atStartOfDay(zone).toInstant().toEpochMilli().toString(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()
            ), "同日同金额同名称", true)
            if (matches.isNotEmpty()) return ScreenshotSaveResult.Duplicate(matches.values.toList())
        }
        val inserted = database.expenseDao.insertExpenseRecord(db, input.amountCents, input.categoryKey,
            name, note, input.spentAt, ExpenseSource.SCREENSHOT_OCR, input.merchant, sourceKey,
            explicitCategoryKey = input.categoryKey.takeIf { input.categoryExplicit },
            historyCategoryKey = if (input.categoryExplicit || CategoryRuleMatcher.match(ClassificationInput(input.merchant, name), database.categoryRuleDao.loadRuleSets(db)) != CategoryMatch.NoMatch) null
                else historyCategory(db, input.merchant ?: name))
        val id = idBySourceKey(db, sourceKey)
        return when {
            inserted && id != null -> ScreenshotSaveResult.Saved(id)
            id != null -> ScreenshotSaveResult.AlreadySaved(id)
            else -> ScreenshotSaveResult.Invalid("账目未能保存")
        }
    }

    private fun idBySourceKey(db: SQLiteDatabase, key: String): Long? = db.rawQuery(
        "SELECT id FROM expenses WHERE source_key = ?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun normalizeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("[\\s\\p{Z}]+"), " ").lowercase(Locale.ROOT)
}
