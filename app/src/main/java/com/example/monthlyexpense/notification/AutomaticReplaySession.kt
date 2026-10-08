package com.example.monthlyexpense.notification

import android.content.SharedPreferences
import android.os.SystemClock

class AutomaticReplaySession(private val prefs: SharedPreferences,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val now: () -> Long = System::currentTimeMillis) {
    fun allowed(): Boolean {
        val until = prefs.getLong("next_allowed", 0)
        return now() >= until
    }

    @Synchronized fun open(id: String): ReplayBudget? {
        val previousId = prefs.getString("session_id", null)
        val same = previousId == id
        val resumingYield = !same && prefs.getString("outcome", null) == "yielded" && !prefs.getBoolean("finished", false)
        if (same || resumingYield) {
            if (prefs.getBoolean("finished", false)) return null
            val age = now() - prefs.getLong("created", now())
            if (age < 0 || age > 600_000L || remainingMillis() <= 0) { finish(requireNotNull(previousId), false); return null }
            if (resumingYield && !prefs.edit().putString("session_id", id).putString("outcome", "running").commit()) return null
        } else {
            if (!allowed()) return null
            if (!prefs.edit().putString("session_id", id).putLong("created", now()).putLong("created_elapsed", elapsed())
                    .putString("outcome", "running").putBoolean("finished", false)
                    .putInt("reads", 0).putBoolean("recovery_used", false).putInt("post_reads", 0).commit()) return null
        }
        return ReplayBudget(ReplayBudgetState(prefs.getInt("reads", 0), prefs.getBoolean("recovery_used", false),
            prefs.getInt("post_reads", 0))) { state ->
            prefs.getString("session_id", null) == id && !prefs.getBoolean("finished", false) && prefs.edit().putInt("reads", state.reads)
                .putBoolean("recovery_used", state.recoveryUsed).putInt("post_reads", state.readsAfterRecovery).commit()
        }
    }

    fun remainingMillis(): Long {
        val wallAge = now() - prefs.getLong("created", now())
        val elapsedAge = elapsed() - prefs.getLong("created_elapsed", elapsed())
        if (wallAge < 0 || elapsedAge < 0) return 0L
        return (150_000L - maxOf(wallAge, elapsedAge)).coerceAtLeast(0L)
    }

    @Synchronized fun completeForManual() {
        // A manual result may arrive before the automatic worker persists its yield.
        // Close the current unfinished session as a terminal fence for any late worker write.
        if (prefs.contains("session_id") && !prefs.getBoolean("finished", false)) {
            prefs.edit().putBoolean("finished", true).putString("outcome", "manual_completed")
                .putLong("next_allowed", 0L).commit()
        }
    }

    @Synchronized fun finish(id: String, successful: Boolean, yielded: Boolean = false) {
        if (prefs.getString("session_id", null) != id) return
        if (prefs.getString("outcome", null) == "manual_completed") return
        prefs.edit().putBoolean("finished", !yielded).putString("outcome", if (yielded) "yielded" else if (successful) "completed" else "incomplete")
            .putLong("next_allowed", if (successful || yielded) 0 else now() + 300_000L).commit()
    }
}
