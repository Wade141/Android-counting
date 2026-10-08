package com.example.monthlyexpense.backup

import com.example.monthlyexpense.BackupDao
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import java.time.ZoneId

interface ExpenseBackupContract {
    fun exportJson(exportedAt: Long = System.currentTimeMillis()): String

    fun exportCsv(zoneId: ZoneId = ZoneId.systemDefault()): String

    fun validateJson(json: String): Boolean

    fun restoreJsonWithSettings(json: String): ExpenseBackupSettings?

    fun restoreDetailed(json: String): BackupRestoreResult = restoreJsonWithSettings(json)
        ?.let { BackupRestoreResult.Completed(it) } ?: BackupRestoreResult.NotCommitted
}

sealed interface BackupRestoreResult {
    data object NotCommitted : BackupRestoreResult
    data object SettingsPending : BackupRestoreResult
    data class Completed(val settings: ExpenseBackupSettings) : BackupRestoreResult
}

interface BackupRestoreLifecycle {
    fun begin(restoreId: String)
    fun applySources(settings: ExpenseBackupSettings)
    fun finish(restoreId: String, committed: Boolean)
}

class ExpenseBackupManager(
    private val database: BackupDao,
    private val budgetSettings: BudgetSettings,
    private val autoSettings: AutoBookkeepingSettings,
    private val lifecycle: BackupRestoreLifecycle? = null,
    private val settingsWriter: ((ExpenseBackupSettings) -> Boolean)? = null
) : ExpenseBackupContract {
    override fun exportJson(exportedAt: Long): String = ExpenseBackupJson.encode(
        ExpenseBackupDocument(
            exportedAt = exportedAt,
            database = database.exportBackupDatabase(),
            settings = ExpenseBackupSettings(
                dailyBudgetCents = budgetSettings.dailyBudgetCents,
                autoBookkeepingEnabled = autoSettings.isEnabled,
                weChatEnabled = autoSettings.isWeChatEnabled,
                alipayEnabled = autoSettings.isAlipayEnabled
            )
        )
    )

    override fun exportCsv(zoneId: ZoneId): String {
        val backup = database.exportBackupDatabase()
        return ExpenseCsvExporter.encode(
            expenses = backup.expenses,
            categoryNames = backup.categories.associate { it.key to it.name },
            zoneId = zoneId
        )
    }

    override fun validateJson(json: String): Boolean = runCatching {
        ExpenseBackupJson.decode(json)
    }.isSuccess

    fun restoreJson(json: String): Boolean = restoreJsonWithSettings(json) != null

    override fun restoreJsonWithSettings(json: String): ExpenseBackupSettings? =
        (restoreDetailed(json) as? BackupRestoreResult.Completed)?.settings

    override fun restoreDetailed(json: String): BackupRestoreResult {
        if (database.notificationProtocol.pendingRestore() != null) return BackupRestoreResult.SettingsPending
        val document = try {
            ExpenseBackupJson.decode(json)
        } catch (_: InvalidExpenseBackupException) {
            return BackupRestoreResult.NotCommitted
        }
        val id = java.util.UUID.randomUUID().toString()
        val previousGeneration = database.notificationProtocol.bookGeneration()
        val settings = document.settings
        val payload = org.json.JSONObject().put("budget", settings.dailyBudgetCents)
            .put("enabled", settings.autoBookkeepingEnabled).put("wechat", settings.weChatEnabled)
            .put("alipay", settings.alipayEnabled).toString()
        val committed = try {
            lifecycle?.begin(id)
            database.restoreBackupDatabase(document.database, id, java.util.UUID.randomUUID().toString(), payload)
        } catch (error: RuntimeException) {
            // An exception during transaction completion is not proof of rollback. Consult the
            // marker before releasing the durable gate or telling the UI the old book survived.
            if (database.notificationProtocol.pendingRestore()?.restoreId == id) return continuePendingRestore()
            if (database.notificationProtocol.bookGeneration() != previousGeneration) throw error
            lifecycle?.finish(id, false)
            return BackupRestoreResult.NotCommitted
        }
        if (!committed) {
            lifecycle?.finish(id, false)
            return BackupRestoreResult.NotCommitted
        }
        return continuePendingRestore()
    }

    fun continuePendingRestore(): BackupRestoreResult {
        val operation = database.notificationProtocol.pendingRestore() ?: run {
            database.notificationProtocol.latestRestore()?.let { lifecycle?.finish(it.restoreId, true) }
            return BackupRestoreResult.Completed(ExpenseBackupSettings(budgetSettings.dailyBudgetCents,
                autoSettings.isEnabled, autoSettings.isWeChatEnabled, autoSettings.isAlipayEnabled))
        }
        return try {
            lifecycle?.begin(operation.restoreId)
            val json = org.json.JSONObject(operation.targetSettingsJson)
            val target = ExpenseBackupSettings(json.getLong("budget"), json.getBoolean("enabled"),
                json.getBoolean("wechat"), json.getBoolean("alipay"))
            lifecycle?.applySources(target)
            val saved = settingsWriter?.invoke(target) ?: (budgetSettings.restoreFromBackup(target.dailyBudgetCents) &&
                autoSettings.restoreFromBackup(target.autoBookkeepingEnabled, target.weChatEnabled, target.alipayEnabled))
            if (!saved || !database.notificationProtocol.completeRestore(operation.restoreId)) BackupRestoreResult.SettingsPending
            else {
                lifecycle?.finish(operation.restoreId, true)
                BackupRestoreResult.Completed(target)
            }
        } catch (_: RuntimeException) { BackupRestoreResult.SettingsPending }
    }
}
