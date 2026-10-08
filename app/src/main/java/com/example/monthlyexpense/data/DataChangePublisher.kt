package com.example.monthlyexpense.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DataDomain { LEDGER, REVIEW, SETTINGS }

data class DataRevisions(val ledger: Long = 0, val review: Long = 0, val settings: Long = 0) {
    fun changedSince(previous: DataRevisions): Set<DataDomain> = buildSet {
        if (ledger != previous.ledger) add(DataDomain.LEDGER)
        if (review != previous.review) add(DataDomain.REVIEW)
        if (settings != previous.settings) add(DataDomain.SETTINGS)
    }
}

/** In-process invalidation only. Durable events and first/resume reads handle process death. */
class DataChangePublisher {
    private val mutable = MutableStateFlow(DataRevisions())
    val revisions = mutable.asStateFlow()
    private val home = MutableStateFlow(DataRevisions())
    val homeRefreshes = home.asStateFlow()

    fun publish(domains: Set<DataDomain>) {
        increment(mutable, domains)
        increment(home, domains - DataDomain.REVIEW)
    }

    /** Foreground ViewModel already owns the refresh and its completion/error waiter. */
    fun publishHandledByHome(domains: Set<DataDomain>) = increment(mutable, domains)

    private fun increment(target: MutableStateFlow<DataRevisions>, domains: Set<DataDomain>) {
        if (domains.isEmpty()) return
        target.update { old -> old.copy(
            ledger = old.ledger + if (DataDomain.LEDGER in domains) 1 else 0,
            review = old.review + if (DataDomain.REVIEW in domains) 1 else 0,
            settings = old.settings + if (DataDomain.SETTINGS in domains) 1 else 0
        ) }
    }
}
