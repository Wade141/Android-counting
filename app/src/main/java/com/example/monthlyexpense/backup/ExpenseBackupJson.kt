package com.example.monthlyexpense.backup

import com.example.monthlyexpense.classification.*
import org.json.JSONArray
import org.json.JSONObject

object ExpenseBackupJson {
    const val FORMAT = "expense_report"
    const val VERSION = 3

    fun encode(document: ExpenseBackupDocument): String {
        require(ExpenseBackupValidator.isValid(document)) { "Invalid expense backup data" }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", document.exportedAt)
            .put("database", document.database.toJson())
            .put("settings", document.settings.toJson())
            .toString(2)
    }

    fun decode(json: String): ExpenseBackupDocument = try {
        val root = StrictJson.parseObject(json).requireKeys(
            "format", "version", "exportedAt", "database", "settings"
        )
        if (root.requiredString("format") != FORMAT) invalid("Unsupported backup format")
        val version = root.requiredInt("version")
        if (version !in 1..VERSION) invalid("Unsupported backup version")
        val document = ExpenseBackupDocument(
            exportedAt = root.requiredLong("exportedAt"),
            database = root.requiredObject("database").toDatabase(version),
            settings = root.requiredObject("settings").toSettings()
        )
        if (!ExpenseBackupValidator.isValid(document)) invalid("Backup data is invalid")
        document
    } catch (error: InvalidExpenseBackupException) {
        throw error
    } catch (error: Exception) {
        throw InvalidExpenseBackupException("Backup data is invalid", error)
    }

    private fun ExpenseBackupDatabase.toJson() = JSONObject()
        .put("categoryRules", ClassificationJson.encodeRules(categoryRules))
        .put("categories", JSONArray().apply { categories.forEach { put(it.toJson()) } })
        .put("expenses", JSONArray().apply { expenses.forEach { put(it.toJson()) } })
        .put("monthlyBudgets", JSONArray().apply { monthlyBudgets.forEach { put(it.toJson()) } })

    private fun BackupCategory.toJson() = JSONObject()
        .put("key", key).put("name", name).put("colorArgb", colorArgb)
        .put("builtIn", builtIn).put("sortOrder", sortOrder)

    private fun BackupExpense.toJson() = JSONObject()
        .put("id", id).put("amountCents", amountCents).put("categoryKey", categoryKey)
        .put("name", name).put("note", note).put("spentAt", spentAt)
        .put("monthKey", monthKey).put("archived", archived).put("source", source)
        .put("merchant", merchant ?: JSONObject.NULL)
        .put("sourceKey", sourceKey ?: JSONObject.NULL)
        .put("classificationOrigin", classificationOrigin.name)
        .put("isSpecial", isSpecial)
        .put("classificationHits", ClassificationJson.encodeHits(classificationHits)?.let(::JSONArray) ?: JSONArray())

    private fun BackupMonthlyBudget.toJson() = JSONObject()
        .put("monthKey", monthKey).put("amountCents", amountCents)

    private fun ExpenseBackupSettings.toJson() = JSONObject()
        .put("dailyBudgetCents", dailyBudgetCents)
        .put("autoBookkeepingEnabled", autoBookkeepingEnabled)
        .put("weChatEnabled", weChatEnabled)
        .put("alipayEnabled", alipayEnabled)

    private fun JSONObject.toDatabase(version: Int): ExpenseBackupDatabase {
        requireKeys(*(listOf("categories", "expenses", "monthlyBudgets") + if (version >= 2) listOf("categoryRules") else emptyList()).toTypedArray())
        return ExpenseBackupDatabase(
            categories = requiredArray("categories").objects { it.toCategory() },
            expenses = requiredArray("expenses").objects { it.toExpense(version) },
            categoryRules = if (version >= 2) ClassificationJson.decodeRules(requiredArray("categoryRules")) else emptyList(),
            monthlyBudgets = requiredArray("monthlyBudgets").objects { it.toMonthlyBudget() }
        )
    }

    private fun JSONObject.toCategory(): BackupCategory {
        requireKeys("key", "name", "colorArgb", "builtIn", "sortOrder")
        return BackupCategory(
            requiredString("key"), requiredString("name"), requiredLong("colorArgb"),
            requiredBoolean("builtIn"), requiredInt("sortOrder")
        )
    }

    private fun JSONObject.toExpense(version: Int): BackupExpense {
        requireKeys(*(listOf("id", "amountCents", "categoryKey", "name", "note", "spentAt", "monthKey",
            "archived", "source", "merchant", "sourceKey") +
            (if (version >= 2) listOf("classificationOrigin", "classificationHits") else emptyList()) +
            (if (version >= 3) listOf("isSpecial") else emptyList())).toTypedArray())
        return BackupExpense(
            id = requiredLong("id"),
            amountCents = requiredLong("amountCents"),
            categoryKey = requiredString("categoryKey"),
            name = requiredString("name"),
            note = requiredString("note"),
            spentAt = requiredLong("spentAt"),
            monthKey = requiredString("monthKey"),
            archived = requiredBoolean("archived"),
            source = requiredString("source"),
            merchant = nullableString("merchant"),
            sourceKey = nullableString("sourceKey"),
            classificationOrigin = if (version >= 2) ClassificationOrigin.valueOf(requiredString("classificationOrigin")) else ClassificationOrigin.LEGACY,
            classificationHits = if (version >= 2) ClassificationJson.decodeHits(requiredArray("classificationHits").toString()) else emptyList(),
            isSpecial = if (version >= 3) requiredBoolean("isSpecial") else false
        )
    }

    private fun JSONObject.toMonthlyBudget(): BackupMonthlyBudget {
        requireKeys("monthKey", "amountCents")
        return BackupMonthlyBudget(requiredString("monthKey"), requiredLong("amountCents"))
    }

    private fun JSONObject.toSettings(): ExpenseBackupSettings {
        requireKeys("dailyBudgetCents", "autoBookkeepingEnabled", "weChatEnabled", "alipayEnabled")
        return ExpenseBackupSettings(
            requiredLong("dailyBudgetCents"), requiredBoolean("autoBookkeepingEnabled"),
            requiredBoolean("weChatEnabled"), requiredBoolean("alipayEnabled")
        )
    }

    private fun JSONObject.requireKeys(vararg expected: String): JSONObject {
        val actual = keys().asSequence().toSet()
        if (actual != expected.toSet()) invalid("Unexpected or missing backup fields")
        return this
    }

    private fun JSONObject.requiredObject(key: String): JSONObject = opt(key) as? JSONObject
        ?: invalid("$key must be an object")

    private fun JSONObject.requiredArray(key: String): JSONArray = opt(key) as? JSONArray
        ?: invalid("$key must be an array")

    private fun JSONObject.requiredString(key: String): String = opt(key) as? String
        ?: invalid("$key must be a string")

    private fun JSONObject.nullableString(key: String): String? = when (val value = opt(key)) {
        JSONObject.NULL -> null
        is String -> value
        else -> invalid("$key must be a string or null")
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean = opt(key) as? Boolean
        ?: invalid("$key must be a boolean")

    private fun JSONObject.requiredLong(key: String): Long = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalid("$key must be an integer")
    }

    private fun JSONObject.requiredInt(key: String): Int {
        val value = requiredLong(key)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) invalid("$key is outside integer range")
        return value.toInt()
    }

    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> = buildList {
        for (index in 0 until length()) {
            add(transform(opt(index) as? JSONObject ?: invalid("Array item must be an object")))
        }
    }

    private fun invalid(message: String): Nothing = throw InvalidExpenseBackupException(message)
}
