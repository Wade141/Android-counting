package com.example.monthlyexpense.architecture

import com.example.monthlyexpense.presentation.ForegroundUiStore
import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/** Checks compiled types, including signatures and method bodies, not import spelling. */
class ExpenseViewModelBoundaryTest {
    private val root = "com/example/monthlyexpense/"
    private val shared = root + "presentation/contracts/"
    private val settingsApi = root + "notification/api/"
    private val settingsImpl = root + "notification/AutoBookkeepingSettingsController"
    private val backupApi = root + "backup/api/"
    private val backupTypes = setOf("backup/BackupController", "backup/BackupControllerKt",
        "backup/PendingDocumentWrite", "backup/RestoreAdmission").map { root + it }.toSet()
    private val backupValues = setOf("UiEvent", "UiMessage", "BackupExportFormat", "backup/ExpenseBackupContract",
        "backup/BackupRestoreResult", "backup/ExpenseBackupSettings", "data/ForegroundSettingsBaseline",
        "data/ForegroundRestoreTicket", "data/RestoreCommit", "data/RestoreOutcome").map { root + it }.toSet()
    private val eventValues = setOf("UiEvent", "UiMessage").map { root + it }.toSet()
    private val settingsValues = eventValues + setOf("AutoBookkeepingUiState", "data/ExpenseHomeSnapshot",
        "data/ForegroundSettingsBaseline").map { root + it }
    private val settingsInfrastructure = setOf("data/ForegroundPersistenceCoordinator", "data/ForegroundMutationTicket",
        "data/CoordinatedMutation").map { root + it }.toSet()
    private val presentation = root + "presentation/"
    private val uiValues = setOf("ExpenseUiState", "AutoBookkeepingUiState", "HistoryUiState", "ExpensePendingState",
        "CategoryManagementUiState", "CategoryEditorTarget", "CategoryCorrection", "ExpenseOverlay",
        "notification/NotificationListenerConnectionState", "data/HistoryCursor", "search/ExpenseSearchCursor",
        "search/ExpenseSearchUiState", "search/ExpenseSearchFilters", "search/SearchEditSnapshot",
        "search/ExpenseSearchSummary").map { root + it }.toSet()
    private val assemblyTypes = setOf("ForegroundUiStore", "ForegroundEffects", "ExpensePresentation",
        "bindings/BackupUiAdapter", "bindings/HomeSnapshotAdapter", "bindings/RestoreUiAdapter", "bindings/OverlayUiAdapter",
        "data/AutoSettingsRepositoryAdapter").map { presentation + it }.toSet() +
        setOf(root + "data/ExpenseRepositoryContract", root + "data/ForegroundPersistenceCoordinator",
            settingsImpl, backupApi + "BackupNavigation")
    private val assemblyDependencies = assemblyTypes + setOf(root + "ExpenseUiState", presentation + "ExpensePresentationFactory",
        shared + "SliceState", shared + "UiEventSink", settingsApi + "AutoSettingsWritePort")

    private val historyApi = root + "history/api/"
    private val refreshApi = root + "refresh/api/"
    private val historyTypes = setOf("history/ExpenseHistoryController", "history/ExpenseHistoryControllerKt",
        "history/HistoryLoadAdmission").map { root + it }.toSet()
    private val refreshTypes = setOf("refresh/HomeRefreshController", "refresh/HomeRefreshControllerKt",
        "refresh/RefreshRead", "refresh/RefreshReadWithSettingGenerations", "refresh/RefreshRequestMerger",
        "refresh/RefreshRequestMergerKt", "refresh/QueuedRefresh", "refresh/RefreshRequest", "refresh/RefreshResult",
        "refresh/RefreshScope").map { root + it }.toSet()
    private val historyValues = setOf("HistoryUiState", "ExpenseRecord", "data/HistoryCursor", "data/HistoryPage",
        "data/HistoryMonth", "refresh/RefreshScope").map { root + it }.toSet()
    private val refreshValues = eventValues + setOf("HistoryUiState", "AutoBookkeepingUiState", "data/DataDomain",
        "data/ExpenseHomeSnapshot", "data/ForegroundSettingsBaseline", "notification/NotificationListenerConnectionState",
        "refresh/RefreshRequest", "refresh/RefreshResult", "refresh/RefreshScope").map { root + it }
    private val t3Assembly = setOf("presentation/bindings/HistoryUiAdapter", "presentation/bindings/HomeDisplayAdapter",
        "presentation/data/HomeRepositoryAdapter", "presentation/data/HistoryRepositoryAdapter",
        "history/ExpenseHistoryController", "refresh/HomeRefreshController", "history/api/HistoryReadPort",
        "history/api/HistoryNavigation", "history/api/HistoryRefreshPort", "refresh/api/HomeReadPort",
        "refresh/api/HomeDisplayPort", "refresh/api/HomeSnapshotCommit", "refresh/api/MutationRefreshPort",
        "refresh/api/RestoreRefreshPort", "notification/api/SettingsSnapshotPort").map { root + it }.toSet()
    private val searchApi = root + "search/api/"
    private val searchImpl = root + "search/ExpenseSearchController"
    private val searchValues = setOf("search/ExpenseSearchUiState", "search/ExpenseSearchFilters", "search/ExpenseSearchQuery",
        "search/ExpenseSearchCursor", "search/ExpenseSearchPage", "search/ExpenseSearchSummary", "search/SearchEditSnapshot",
        "search/SearchDatePreset", "search/SearchValidationResult").map { root + it }.toSet()
    private val searchTypes = searchValues + setOf(searchImpl, root + "search/ExpenseSearchQueryKt")
    private val t4Assembly = setOf("presentation/bindings/SearchUiAdapter", "presentation/data/SearchRepositoryAdapter",
        "search/ExpenseSearchController", "search/api/SearchReadPort", "search/api/SearchNavigation",
        "search/api/SearchEditorNavigation").map { root + it }.toSet()
    private val editorApi = root + "entry/api/"
    private val editorTypes = setOf("entry/ExpenseEditorController", "entry/ExpenseEditorControllerKt").map { root + it }.toSet()
    private val editorValues = setOf("ExpenseRecord", "ExpenseCategory", "NewExpenseInput", "ExpenseEditInput",
        "CategoryCorrection").map { root + it }.toSet()
    private val editorPorts = setOf("ExpenseEditorState", "ExpenseWritePort", "ExpenseEditContext", "ExpenseEditContextReader",
        "ExpenseHistoryMembership", "EditorNavigation", "EditorCommitPort", "SavedEditTarget", "ExpenseMutationEffects")
        .map { editorApi + it }.toSet()
    private val runner = presentation + "ForegroundMutationRunner"
    private val t5Assembly = editorTypes + editorPorts + setOf(runner, shared + "ReadState",
        presentation + "bindings/EditorUiAdapter", presentation + "data/ExpenseRepositoryAdapter")
    private val categoryApi = root + "categories/api/"
    private val budgetApi = root + "budget/api/"
    private val categoryTypes = setOf("categories/CategoryManagementController", "categories/CategoryManagementControllerKt").map { root + it }.toSet()
    private val categoryValues = setOf("CategoryManagementUiState", "CategoryEditorTarget", "CategoryInput", "ExpenseCategory",
        "categories/CategoryMutationResult", "categories/CategoryDeleteResult").map { root + it }.toSet()
    private val categoryPorts = setOf(categoryApi + "CategoryOperationState", categoryApi + "CategoryWritePort")
    private val budgetTypes = setOf(root + "budget/BudgetController")
    private val budgetPorts = setOf(budgetApi + "BudgetOperationState", budgetApi + "BudgetWritePort", budgetApi + "BudgetNavigation")
    private val t6Assembly = categoryTypes + budgetTypes + categoryPorts + budgetPorts + setOf(
        presentation + "bindings/BudgetUiAdapter", presentation + "data/CategoryRepositoryAdapter", presentation + "data/BudgetRepositoryAdapter")

    private val bindingPorts = mapOf(
        "HomeSnapshotAdapter" to refreshApi + "HomeSnapshotCommit",
        "HistoryUiAdapter" to historyApi + "HistoryNavigation",
        "HomeDisplayAdapter" to refreshApi + "HomeDisplayPort",
        "SearchUiAdapter" to searchApi + "SearchNavigation",
    )
    private val restoreBindingTypes = setOf(presentation + "bindings/RestoreUiAdapter", presentation + "ForegroundUiStore",
        root + "data/ForegroundSettingsBaseline", backupApi + "RestoreUiBoundary", backupApi + "BackupSettingsCommit",
        backupApi + "BackupOperationState", editorApi + "ExpenseEditorState", categoryApi + "CategoryOperationState",
        budgetApi + "BudgetOperationState", searchApi + "SearchInvalidation", refreshApi + "RefreshInvalidation")
    private val t7Assembly = setOf(root + "backup/BackupController", root + "backup/ExpenseBackupContract",
        backupApi + "BackupOperationState", backupApi + "RestoreUiBoundary", backupApi + "BackupSettingsCommit",
        searchApi + "SearchInvalidation", refreshApi + "RefreshInvalidation", root + "data/ForegroundSettingsBaseline",
        root + "HistoryUiState", root + "AutoBookkeepingUiState")

    private fun purePresentationType(owner: String) = owner.startsWith(presentation + "bindings/") ||
        owner.substringBefore('$') == presentation + "ForegroundUiStore"

    private fun matchesType(name: String, allowed: Set<String>) = name.substringBefore('$') in allowed

    private fun violations(owner: String, dependencies: Set<String>): Set<String> = dependencies.filter { dep ->
        if (dep == owner || dep.startsWith("$owner$")) false
        // Injected by the existing Compose compiler into ordinary Kotlin classes.
        else if (dep == "androidx/compose/runtime/internal/StabilityInferred") false
        else if (dep.startsWith("android/") || dep.startsWith("androidx/") || dep.startsWith("java/sql/")) true
        else if (dep.startsWith("java/io/") || dep.startsWith("java/net/") || dep.startsWith("java/nio/file/")) true
        else if (owner.substringBefore('$') in setOf(presentation + "bindings/HistoryUiAdapter", presentation + "bindings/HomeDisplayAdapter", presentation + "bindings/SearchUiAdapter", presentation + "bindings/EditorUiAdapter") &&
            dep == "kotlinx/coroutines/flow/StateFlow") false
        else if (purePresentationType(owner) && dep.startsWith("kotlinx/coroutines/") &&
            !(owner.substringBefore('$') == presentation + "ForegroundUiStore" && dep.startsWith("kotlinx/coroutines/flow/"))) true
        else if (!dep.startsWith(root)) false
        else when {
            owner.startsWith(categoryApi) -> !matchesType(dep, categoryPorts + categoryValues)
            owner.startsWith(budgetApi) -> !matchesType(dep, budgetPorts + setOf(root + "BudgetKind"))
            matchesType(owner, categoryTypes) -> !matchesType(dep, categoryTypes + categoryPorts + categoryValues + settingsInfrastructure +
                setOf(runner, root + "UiMessage", root + "data/DataDomain", root + "refresh/RefreshScope")) && !dep.startsWith(shared)
            matchesType(owner, budgetTypes) -> !matchesType(dep, budgetTypes + budgetPorts + settingsInfrastructure +
                setOf(runner, root + "UiMessage", root + "BudgetKind", root + "MoneyLimits", root + "data/DataDomain", root + "refresh/RefreshScope")) && !dep.startsWith(shared)
            owner.startsWith(editorApi) -> !matchesType(dep, editorPorts + editorValues)
            matchesType(owner, editorTypes) -> !matchesType(dep, editorTypes + editorPorts + editorValues + eventValues + settingsInfrastructure +
                setOf(runner, root + "MoneyLimits", root + "data/DataDomain", root + "refresh/RefreshScope")) && !dep.startsWith(shared)
            owner.substringBefore('$') == runner -> !matchesType(dep, eventValues + settingsInfrastructure +
                setOf(runner, root + "data/DataDomain", root + "refresh/RefreshScope", root + "refresh/RefreshResult", refreshApi + "MutationRefreshPort")) && !dep.startsWith(shared)
            owner.startsWith(searchApi) -> !dep.startsWith(searchApi) && !matchesType(dep, searchValues)
            matchesType(owner, searchTypes) -> !matchesType(dep, searchTypes + settingsInfrastructure +
                setOf(root + "ExpenseRecord", root + "ExpenseSource", root + "MoneyLimits", root + "data/ForegroundReadTicket", root + "data/DataDomain")) &&
                !dep.startsWith(searchApi) && !dep.startsWith(shared)
            owner.startsWith(historyApi) -> !dep.startsWith(historyApi) && !matchesType(dep, historyValues)
            owner.startsWith(refreshApi) -> !dep.startsWith(refreshApi) && !matchesType(dep, refreshValues)
            matchesType(owner, historyTypes) -> !matchesType(dep, historyTypes + historyValues + settingsInfrastructure) &&
                !dep.startsWith(historyApi) && !dep.startsWith(shared)
            matchesType(owner, refreshTypes) -> !matchesType(dep, refreshTypes + refreshValues + settingsInfrastructure +
                setOf(root + "data/ForegroundReadTicket", root + "notification/NotificationMetrics", root + "notification/PipelineMetric")) &&
                !dep.startsWith(refreshApi) && !matchesType(dep, setOf(historyApi + "HistoryRefreshPort", historyApi + "HistoryRefreshPlan",
                    historyApi + "HistoryPageRead", settingsApi + "SettingsSnapshotPort", settingsApi + "SettingGenerations")) && !dep.startsWith(shared)
            owner.startsWith(shared) -> !dep.startsWith(shared) && !matchesType(dep, eventValues)
            owner.startsWith(settingsApi) -> !dep.startsWith(settingsApi) && !dep.startsWith(shared) && !matchesType(dep, settingsValues)
            owner.startsWith(backupApi) -> !dep.startsWith(backupApi) && !dep.startsWith(shared) && dep != root + "data/ForegroundSettingsBaseline"
            matchesType(owner, backupTypes) -> !matchesType(dep, backupTypes + backupValues + settingsInfrastructure +
                setOf(refreshApi + "RestoreRefreshPort")) && !dep.startsWith(backupApi) && !dep.startsWith(shared)
            owner == settingsImpl || owner.startsWith("$settingsImpl$") ->
                !(dep == settingsImpl || dep.startsWith("$settingsImpl$") || dep.startsWith(settingsApi) ||
                    dep.startsWith(shared) || matchesType(dep, settingsValues + settingsInfrastructure))
            owner.substringBefore('$') == presentation + "ForegroundUiStore" ->
                !matchesType(dep, uiValues + setOf(presentation + "ForegroundUiStore", root + "ExpenseCategory", editorApi + "ExpenseEditorState",
                    categoryApi + "CategoryOperationState", budgetApi + "BudgetOperationState", backupApi + "BackupOperationState")) && !dep.startsWith(shared)
            owner.substringBefore('$') == presentation + "ForegroundEffects" ->
                !matchesType(dep, eventValues + (presentation + "ForegroundEffects")) && dep != shared + "UiEventSink"
            owner.substringBefore('$') == presentation + "bindings/EditorUiAdapter" ->
                !matchesType(dep, uiValues + setOf(presentation + "ForegroundUiStore", presentation + "bindings/EditorUiAdapter",
                    root + "ExpenseRecord") + (editorPorts - setOf(editorApi + "ExpenseWritePort", editorApi + "ExpenseEditorState")))
            owner.substringBefore('$') == presentation + "bindings/BudgetUiAdapter" ->
                !matchesType(dep, uiValues + setOf(presentation + "ForegroundUiStore",
                    root + "BudgetKind", budgetApi + "BudgetNavigation", presentation + "bindings/BudgetUiAdapter"))
            owner.substringBefore('$') == presentation + "bindings/RestoreUiAdapter" ->
                !matchesType(dep, uiValues + restoreBindingTypes)
            owner.startsWith(presentation + "bindings/") ->
                !matchesType(dep, uiValues + setOf(presentation + "ForegroundUiStore", root + "data/ExpenseHomeSnapshot",
                    backupApi + "BackupNavigation", owner.substringBefore('$')) +
                    listOfNotNull(bindingPorts[owner.substringBefore('$').substringAfterLast('/')]) +
                    if (owner.substringBefore('$') == presentation + "bindings/SearchUiAdapter") setOf(searchApi + "SearchEditorNavigation", root + "ExpenseRecord") else emptySet())
            owner.substringBefore('$') == presentation + "data/AutoSettingsRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", settingsApi + "AutoSettingsWritePort"))
            owner.substringBefore('$') == presentation + "data/HomeRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", root + "data/ExpenseHomeSnapshot", root + "data/DataDomain", refreshApi + "HomeReadPort"))
            owner.substringBefore('$') == presentation + "data/HistoryRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", root + "data/HistoryPage", root + "data/HistoryCursor", historyApi + "HistoryReadPort"))
            owner.substringBefore('$') == presentation + "data/SearchRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", searchApi + "SearchReadPort",
                    root + "search/ExpenseSearchQuery", root + "search/ExpenseSearchCursor", root + "search/ExpenseSearchPage"))
            owner.substringBefore('$') == presentation + "data/ExpenseRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", root + "ExpenseEditInput",
                    root + "NewExpenseInput", editorApi + "ExpenseWritePort"))
            owner.substringBefore('$') == presentation + "data/CategoryRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", categoryApi + "CategoryWritePort",
                    root + "categories/CategoryMutationResult", root + "categories/CategoryDeleteResult"))
            owner.substringBefore('$') == presentation + "data/BudgetRepositoryAdapter" ->
                !matchesType(dep, setOf(root + "data/ExpenseRepositoryContract", budgetApi + "BudgetWritePort"))
            owner.substringBefore('$') in setOf(presentation + "ExpensePresentationFactory", presentation + "ExpensePresentation") ->
                !matchesType(dep, assemblyDependencies + t3Assembly + t4Assembly + t5Assembly + t6Assembly + t7Assembly)
            else -> error("No boundary rule for $owner")
        }
    }.toSet()

    @Test fun migratedProductionTypesRespectTheirBoundaries() {
        val all = productionClasses()
        val checked = all.filterKeys { name -> name.startsWith(presentation) || name.startsWith(editorApi) || matchesType(name, editorTypes) || name.startsWith(settingsApi) ||
            name.startsWith(categoryApi) || name.startsWith(budgetApi) || matchesType(name, categoryTypes + budgetTypes) ||
            name.startsWith(searchApi) || matchesType(name, searchTypes) || name.startsWith(historyApi) || name.startsWith(refreshApi) || matchesType(name, historyTypes + refreshTypes) ||
            name.startsWith(backupApi) || matchesType(name, backupTypes) || name == settingsImpl || name.startsWith("$settingsImpl$") }
        assertTrue("Shared contracts were not inspected", checked.keys.any { it.startsWith(shared) })
        assertTrue("Backup navigation contract was not inspected", checked.keys.any { it.startsWith(backupApi) })
        assertTrue("Settings implementation was not inspected", checked.containsKey(settingsImpl))
        assertTrue("Settings contracts were not inspected", checked.keys.any { it.startsWith(settingsApi) })
        val required = (assemblyTypes + t3Assembly + t4Assembly + t5Assembly + t6Assembly).filter { it.startsWith(presentation) ||
            it.startsWith(categoryApi) || it.startsWith(budgetApi) || it in categoryTypes || it in budgetTypes || it.startsWith(editorApi) || it in editorTypes } +
            setOf(presentation + "ExpensePresentationFactory", root + "history/ExpenseHistoryController", root + "refresh/HomeRefreshController", searchImpl) +
            backupTypes + setOf(backupApi + "BackupOperationState", backupApi + "RestoreUiBoundary", backupApi + "BackupSettingsCommit")
        assertTrue("Composition/store/adapters missing from inspection: ${required - checked.keys}", checked.keys.containsAll(required))
        val failures = checked.flatMap { (owner, bytes) -> violations(owner, dependencies(bytes)).map { "$owner -> $it" } }
        assertEquals("Forbidden production type dependencies", emptyList<String>(), failures)
    }

    @Test fun legalPortsPassButFeatureCannotDependOnAnotherImplementationOrRootState() {
        assertTrue(violations(settingsImpl, setOf(settingsApi + "AutoSettingsWritePort", shared + "SliceState",
            root + "data/ForegroundPersistenceCoordinator")).isEmpty())
        assertEquals(setOf(root + "search/ExpenseSearchController", root + "ExpenseUiState"),
            violations(settingsImpl, setOf(root + "search/ExpenseSearchController", root + "ExpenseUiState")))
    }

    @Test fun apiCannotDependOnImplementationAndSharedCannotDependOnFeature() {
        assertEquals(setOf(settingsImpl), violations(settingsApi + "SettingsSnapshotPort", setOf(settingsImpl)))
        assertEquals(setOf(settingsApi + "SettingsSnapshotPort"),
            violations(shared + "SliceState", setOf(settingsApi + "SettingsSnapshotPort")))
    }

    @Test fun compositionAndAdaptersCannotAcquireBusinessOrPersistenceCapabilities() {
        val cases = mapOf(
            root + "presentation/ForegroundUiStore" to root + "data/ExpenseRepositoryContract",
            root + "presentation/ForegroundEffects" to root + "ExpenseUiState",
            root + "presentation/bindings/HomeSnapshotAdapter" to root + "data/ExpenseRepositoryContract",
            root + "presentation/bindings/RestoreUiAdapter" to root + "ExpenseDatabaseHelper",
            root + "presentation/bindings/BackupUiAdapter" to root + "data/ForegroundPersistenceCoordinator",
            root + "presentation/data/AutoSettingsRepositoryAdapter" to root + "ExpenseUiState",
            root + "presentation/ExpensePresentationFactory" to root + "ExpenseDatabaseHelper",
        )
        cases.forEach { (owner, forbidden) -> assertEquals(setOf(forbidden), violations(owner, setOf(forbidden))) }
        assertEquals(setOf("kotlinx/coroutines/BuildersKt"), violations(
            root + "presentation/ForegroundUiStore", setOf("kotlinx/coroutines/BuildersKt")))
        assertEquals(setOf("java/net/Socket"), violations(
            root + "presentation/bindings/HomeSnapshotAdapter", setOf("java/net/Socket")))
    }

    @Test fun bytecodeInspectionDetectsAliasesFullyQualifiedNamesAndGenericSignatures() {
        val alias = dependencies(classBytes(AliasedRootReference::class.java))
        val qualified = dependencies(classBytes(QualifiedMethodReference::class.java))
        val generic = dependencies(classBytes(GenericRootReference::class.java))
        for (deps in listOf(alias, qualified, generic)) {
            assertTrue(deps.toString(), root + "ExpenseUiState" in violations(settingsImpl, deps))
        }
    }

    @Test fun inheritedDependencyIsVisibleAndCommentsAndStringsAreNotTypes() {
        val graph = listOf(InheritedRootReference::class.java, AliasedRootReference::class.java)
            .associate { it.name.replace('.', '/') to dependencies(classBytes(it)) }
        fun closure(name: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
            if (!seen.add(name)) return emptySet()
            return graph[name].orEmpty().flatMap { listOf(it) + closure(it, seen) }.toSet()
        }
        assertTrue(root + "ExpenseUiState" in closure(InheritedRootReference::class.java.name.replace('.', '/')))
        assertFalse(root + "ExpenseUiState" in dependencies(classBytes(StringOnlyReference::class.java)))
    }

    @Test fun homeAndHistoryDependOnContractsInsteadOfImplementations() {
        val home = root + "refresh/HomeRefreshController"
        val history = root + "history/ExpenseHistoryController"
        assertTrue(violations(home, setOf(root + "history/api/HistoryRefreshPort", settingsApi + "SettingsSnapshotPort")).isEmpty())
        assertEquals(setOf(history, settingsImpl, root + "ExpenseUiState"),
            violations(home, setOf(history, settingsImpl, root + "ExpenseUiState")))
        val tooWide = setOf(root + "history/api/HistoryReadPort", root + "history/api/HistoryNavigation", settingsApi + "AutoSettingsWritePort")
        assertEquals(tooWide, violations(home, tooWide))
        assertEquals(setOf(home, root + "refresh/api/RefreshCommands"),
            violations(history, setOf(home, root + "refresh/api/RefreshCommands")))
        assertEquals(setOf(home), violations(root + "refresh/api/RestoreRefreshPort", setOf(home)))
        assertEquals(setOf(history), violations(root + "history/api/HistoryRefreshPort", setOf(history)))
    }

    @Test fun restoreRefreshHasOneProductionCallInsideRestoreCallback() {
        assertEquals(emptyList<String>(), restoreCallViolations(productionClasses(), root + "backup/BackupController"))
        val restoreMethods = com.example.monthlyexpense.refresh.api.RestoreRefreshPort::class.java.declaredMethods.map { it.name }
        assertEquals(listOf("refreshWithinRestore"), restoreMethods)
    }

    @Test fun searchOnlyUsesItsSliceReadAndNavigationCapabilities() {
        val search = root + "search/ExpenseSearchController"
        val api = root + "search/api/"
        assertTrue(violations(search, setOf(shared + "SliceState", api + "SearchReadPort", api + "SearchEditorNavigation")).isEmpty())
        val forbidden = setOf(root + "ExpenseUiState", root + "ExpenseOverlay", root + "data/ExpenseRepositoryContract",
            root + "ExpenseViewModel", root + "AppContainer", root + "refresh/HomeRefreshController", root + "history/ExpenseHistoryController",
            root + "presentation/ForegroundUiStore", root + "presentation/bindings/SearchUiAdapter", refreshApi + "RefreshCommands")
        assertEquals(forbidden, violations(search, forbidden))
        assertEquals(setOf(search), violations(api + "SearchReadPort", setOf(search)))
        assertEquals(setOf(api + "SearchReadPort"), violations(shared + "SliceState", setOf(api + "SearchReadPort")))
        assertEquals(setOf(root + "data/ExpenseRepositoryContract"), violations(presentation + "bindings/SearchUiAdapter",
            setOf(root + "data/ExpenseRepositoryContract")))
        assertEquals(setOf(root + "ExpenseUiState"), violations(presentation + "data/SearchRepositoryAdapter", setOf(root + "ExpenseUiState")))
    }

    @Test fun editorAndRunnerCannotReachRootStateOrOtherFeatureImplementations() {
        val editor = root + "entry/ExpenseEditorController"
        val runner = presentation + "ForegroundMutationRunner"
        val forbidden = setOf(root + "ExpenseUiState", root + "ExpensePendingState", root + "ExpenseOverlay",
            root + "ExpenseViewModel", root + "AppContainer", root + "data/ExpenseRepositoryContract",
            presentation + "ForegroundUiStore", presentation + "bindings/EditorUiAdapter", searchImpl,
            root + "history/ExpenseHistoryController", root + "refresh/HomeRefreshController")
        assertEquals(forbidden, violations(editor, forbidden))
        assertEquals(forbidden, violations(runner, forbidden))
        assertEquals(setOf(editor), violations(root + "entry/api/ExpenseWritePort", setOf(editor)))
        assertEquals(setOf(root + "entry/api/ExpenseEditorState"), violations(runner, setOf(root + "entry/api/ExpenseEditorState")))
        assertEquals(setOf(refreshApi + "RestoreRefreshPort", refreshApi + "RefreshCommands"),
            violations(runner, setOf(refreshApi + "RestoreRefreshPort", refreshApi + "RefreshCommands")))
    }

    @Test fun categoryBudgetAndRunnerCannotAcquireEachOthersBusinessCapabilities() {
        val category = root + "categories/CategoryManagementController"
        val budget = root + "budget/BudgetController"
        val categoryPort = root + "categories/api/CategoryWritePort"
        val budgetPort = root + "budget/api/BudgetWritePort"
        val forbidden = setOf(root + "ExpenseUiState", root + "ExpensePendingState", root + "ExpenseOverlay",
            root + "ExpenseViewModel", root + "AppContainer", root + "data/ExpenseRepositoryContract",
            presentation + "ForegroundUiStore", root + "entry/ExpenseEditorController", root + "refresh/HomeRefreshController")
        assertEquals(forbidden + budget + budgetPort, violations(category, forbidden + budget + budgetPort))
        assertEquals(forbidden + category + categoryPort, violations(budget, forbidden + category + categoryPort))
        assertEquals(setOf(category), violations(categoryPort, setOf(category)))
        assertEquals(setOf(budget), violations(budgetPort, setOf(budget)))
        val featureStates = setOf(root + "categories/api/CategoryOperationState", root + "budget/api/BudgetOperationState")
        assertEquals(featureStates, violations(runner, featureStates))
        assertEquals(featureStates, violations(shared + "MutationSlot", featureStates))
    }

    @Test fun hostHasNoLegacyEntityMutationSkeletonOrDirectEntityPersistenceCalls() {
        val forbiddenMethods = setOf("launchForegroundMutation", "updatePendingIfCurrent", "updateStateIfCurrent",
            "refreshAfterCommittedMutation", "entityMutationTicket")
        val violations = mutableListOf<String>()
        productionClasses().filterKeys { it.substringBefore('$') == root + "ExpenseViewModel" }.forEach { (owner, bytes) ->
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    if (name in forbiddenMethods) violations += "$owner.$name"
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, target: String, name: String, descriptor: String, isInterface: Boolean) {
                            if ((target == root + "data/ExpenseRepositoryContract" && name in setOf("addExpense", "updateExpense", "deleteExpense",
                                "addCustomCategory", "updateCategory", "deleteCustomCategory", "setMonthlyBudget", "setDailyBudget")) ||
                                (target == root + "data/ForegroundPersistenceCoordinator" && name.startsWith("runMutation"))) violations += "$owner -> $target.$name"
                        }
                    }
                }
            }, 0)
        }
        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test fun backupCannotAcquireOrdinaryRefreshOrConcreteFeatureCapabilities() {
        val backup = root + "backup/BackupController"
        val forbidden = setOf(root + "ExpenseUiState", root + "ExpensePendingState", root + "ExpenseOverlay",
            root + "ExpenseViewModel", root + "AppContainer", root + "data/ExpenseRepositoryContract",
            root + "refresh/HomeRefreshController", root + "history/ExpenseHistoryController", searchImpl, settingsImpl,
            root + "entry/ExpenseEditorController", root + "categories/CategoryManagementController", root + "budget/BudgetController",
            presentation + "ForegroundUiStore", presentation + "bindings/RestoreUiAdapter", refreshApi + "RefreshCommands",
            refreshApi + "MutationRefreshPort", settingsApi + "SettingsSnapshotPort")
        assertEquals(forbidden, violations(backup, forbidden))
        assertTrue(violations(backup, setOf(backupApi + "RestoreUiBoundary", backupApi + "BackupSettingsCommit",
            refreshApi + "RestoreRefreshPort", root + "backup/ExpenseBackupContract")).isEmpty())
        assertEquals(setOf(backup), violations(backupApi + "RestoreUiBoundary", setOf(backup)))
        assertEquals(setOf(backupApi + "BackupOperationState"), violations(shared + "SliceState", setOf(backupApi + "BackupOperationState")))
    }

    @Test fun restoreRuleAcceptsOnlyTheActualRefreshCallback() {
        val testClasses = classesAt(LegalRestoreCaller::class.java)
        fun violationsFor(type: Class<*>): List<String> {
            val host = type.name.replace('.', '/')
            val fixture = testClasses.filterKeys { it == host || it.startsWith("$host$") }
            assertTrue("Fixture not inspected: $host", fixture.isNotEmpty())
            return restoreCallViolations(fixture, host)
        }
        assertEquals(emptyList<String>(), violationsFor(LegalRestoreCaller::class.java))
        val missed = listOf(ConcreteEscapeRestoreCaller::class.java, WrongCoroutineRestoreCaller::class.java,
            NestedCoroutineRestoreCaller::class.java, WrongArgumentRestoreCaller::class.java)
            .filter { violationsFor(it).isEmpty() }.map { it.simpleName }
        assertEquals("Invalid calls missed by restore rule", emptyList<String>(), missed)
    }

    @Test fun rootMutableFlowIsPrivateAndBindingsHaveNoCasException() {
        val exposed = ForegroundUiStore::class.java.declaredMethods.filter {
            !it.isSynthetic && !java.lang.reflect.Modifier.isPrivate(it.modifiers) &&
                it.returnType == kotlinx.coroutines.flow.MutableStateFlow::class.java
        }.map { it.name }
        assertEquals(emptyList<String>(), exposed)
        val forbidden = setOf("kotlinx/coroutines/flow/MutableStateFlow")
        assertEquals(forbidden, violations(presentation + "bindings/BackupUiAdapter", forbidden))
        val host = root + "ExpenseViewModel"
        val rootWrites = mutableListOf<String>()
        productionClasses().forEach { (caller, bytes) ->
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            if (caller.substringBefore('$') == host &&
                                (owner == root + "ExpenseUiState" && name.startsWith("copy") ||
                                    owner == presentation + "ForegroundUiStore" && name.startsWith("commit") ||
                                    owner == "kotlinx/coroutines/flow/MutableStateFlow")) rootWrites += "$caller -> $owner.$name"
                            if (caller.substringBefore('$') != presentation + "ForegroundUiStore" &&
                                owner == presentation + "ForegroundUiStore" &&
                                descriptor.endsWith("Lkotlinx/coroutines/flow/MutableStateFlow;")) rootWrites += "$caller -> $owner.$name"
                        }
                    }
            }, 0)
        }
        assertEquals("Root writes must stay in store/bindings, including synthetic accessors", emptyList<String>(), rootWrites)
    }

    private fun dependencies(bytes: ByteArray): Set<String> {
        val result = mutableSetOf<String>()
        ClassReader(bytes).accept(ClassRemapper(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {}
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor =
                object : FieldVisitor(Opcodes.ASM9) {}
        }, object : Remapper() {
            override fun map(internalName: String): String {
                result += internalName
                return internalName
            }
        }), 0)
        return result
    }

    private fun classBytes(type: Class<*>): ByteArray = requireNotNull(type.getResourceAsStream(
        "/" + type.name.replace('.', '/') + ".class")).use { it.readBytes() }

    private fun productionClasses() = classesAt(ForegroundUiStore::class.java)

    private fun classesAt(type: Class<*>): Map<String, ByteArray> {
        val location = File(requireNotNull(type.protectionDomain?.codeSource).location.toURI())
        if (location.isDirectory) return location.walkTopDown().filter { it.isFile && it.extension == "class" }
            .associate { it.relativeTo(location).invariantSeparatorsPath.removeSuffix(".class") to it.readBytes() }
        return JarFile(location).use { jar -> jar.entries().asSequence().filter { it.name.endsWith(".class") }
            .associate { entry -> entry.name.removeSuffix(".class") to jar.getInputStream(entry).use { it.readBytes() } } }
    }
}
