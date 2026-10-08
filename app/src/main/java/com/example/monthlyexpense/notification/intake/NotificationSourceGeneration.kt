package com.example.monthlyexpense.notification.intake

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.PaymentSource
import org.json.JSONArray
import kotlin.math.abs

enum class SourcePolicyState { OFF, ARMING, ON }
data class SourcePolicy(val source: PaymentSource, val selected: Boolean, val generation: Long,
    val state: SourcePolicyState, val enabledAt: IntakeClock, val failure: String? = null)
data class SourcePolicySnapshot(val initialized: Boolean = false, val globalEnabled: Boolean = false,
    val sources: Map<PaymentSource, SourcePolicy> = emptyMap()) {
    fun policy(source: PaymentSource): SourcePolicy? = sources[source]
}

/** Mutations run under the ledger coordinator. Publish only after the control transaction commits. */
class NotificationSourceGeneration(private val database: NotificationInboxDatabase, private val cipher: PayloadCipher,
    private val mirror: (SourcePolicySnapshot) -> Unit = {}) {
    @Volatile private var published = SourcePolicySnapshot()
    fun snapshot(): SourcePolicySnapshot = published
    fun reload(): SourcePolicySnapshot {
        val db = database.readableDatabase
        val snapshot = SourcePolicySnapshot(NotificationInboxRepository.control(db,"policies_initialized") == "1",
            NotificationInboxRepository.control(db,"global_enabled") == "1",
            PaymentSource.entries.mapNotNull { source -> readPolicy(db,source)?.let { source to it } }.toMap())
        published = snapshot
        runCatching { mirror(snapshot) }
        return snapshot
    }
    fun initialize(enabled: Boolean, weChatEnabled: Boolean, alipayEnabled: Boolean, clock: IntakeClock): SourcePolicySnapshot {
        database.writableDatabase.intakeTransaction { db ->
            if (NotificationInboxRepository.control(db,"policies_initialized") == "1") return@intakeTransaction
            NotificationInboxRepository.setControl(db,"global_enabled", if (enabled) "1" else "0")
            PaymentSource.entries.forEach { source ->
                val selected = if (source == PaymentSource.WECHAT) weChatEnabled else alipayEnabled
                writePolicy(db,SourcePolicy(source,selected,1,if(enabled && selected) SourcePolicyState.ARMING else SourcePolicyState.OFF,clock))
            }
            NotificationInboxRepository.setControl(db,"policies_initialized","1")
        }
        return reload()
    }
    fun setEnabled(enabled: Boolean, clock: IntakeClock): SourcePolicySnapshot {
        database.writableDatabase.intakeTransaction { db ->
            check(NotificationInboxRepository.control(db,"policies_initialized") == "1")
            if ((NotificationInboxRepository.control(db,"global_enabled") == "1") == enabled) return@intakeTransaction
            NotificationInboxRepository.setControl(db,"global_enabled", if(enabled) "1" else "0")
            PaymentSource.entries.forEach { source ->
                val old = requireNotNull(readPolicy(db,source))
                transition(db,old,old.selected,enabled && old.selected,clock)
            }
        }
        return reload()
    }
    fun setSourceEnabled(source: PaymentSource, enabled: Boolean, clock: IntakeClock): SourcePolicySnapshot {
        database.writableDatabase.intakeTransaction { db ->
            val old = requireNotNull(readPolicy(db,source))
            if(old.selected == enabled) return@intakeTransaction
            transition(db,old,enabled,enabled && NotificationInboxRepository.control(db,"global_enabled") == "1",clock)
        }
        return reload()
    }
    /** Forward-only restore completion; retrying a committed restore cannot advance policy twice. */
    fun applyRestoredSettings(restoreId: String, enabled: Boolean, weChatEnabled: Boolean,
        alipayEnabled: Boolean, clock: IntakeClock): SourcePolicySnapshot {
        database.writableDatabase.intakeTransaction { db ->
            if(NotificationInboxRepository.control(db,"sources_restore_id") == restoreId) return@intakeTransaction
            NotificationInboxRepository.setControl(db,"global_enabled",if(enabled) "1" else "0")
            PaymentSource.entries.forEach { source ->
                val selected = if(source == PaymentSource.WECHAT) weChatEnabled else alipayEnabled
                val generation = (readPolicy(db,source)?.generation ?: 0)+1
                writePolicy(db,SourcePolicy(source,selected,generation,if(enabled && selected) SourcePolicyState.ARMING else SourcePolicyState.OFF,clock))
            }
            db.execSQL("UPDATE notification_inbox SET state='QUARANTINED',reason='BOOK_CHANGED',updated_at=? WHERE state IN ('PENDING','PROCESSING')",arrayOf(clock.wallTime))
            NotificationInboxRepository.setControl(db,"policies_initialized","1")
            NotificationInboxRepository.setControl(db,"sources_restore_id",restoreId)
        }
        return reload()
    }
    private fun transition(db: SQLiteDatabase, old: SourcePolicy, selected: Boolean, active: Boolean, clock: IntakeClock) {
        writePolicy(db,old.copy(selected=selected,generation=old.generation+1,state=if(active) SourcePolicyState.ARMING else SourcePolicyState.OFF,enabledAt=clock,failure=null))
        // Also invalidates queued work when an enabled source is re-armed for a new ledger.
        db.execSQL("""UPDATE notification_inbox SET state='DISCARDED',payload=NULL,nonce=NULL,updated_at=?
            WHERE source=? AND state IN ('PENDING','PROCESSING','QUARANTINED','FAILED')""",arrayOf(clock.wallTime,old.source.name))
    }
    fun allows(source: PaymentSource, generation: Long): Boolean {
        val current = published
        val policy = current.sources[source] ?: return false
        return current.initialized && current.globalEnabled && policy.state == SourcePolicyState.ON && policy.generation == generation
    }
    /** A new time anchor does not revoke legal, already persisted inputs from this source generation. */
    fun rearmUncertainBoundaries(clock: IntakeClock): SourcePolicySnapshot {
        database.writableDatabase.intakeTransaction { db ->
            PaymentSource.entries.forEach { source ->
                val policy = readPolicy(db,source) ?: return@forEach
                if(policy.state == SourcePolicyState.ON && !clockReliable(policy.enabledAt,clock)) {
                    db.update("source_policies",ContentValues().apply {
                        put("state",SourcePolicyState.ARMING.name); put("failure","CLOCK_UNCERTAIN")
                        put("enabled_wall",clock.wallTime); put("enabled_elapsed",clock.elapsedTime); put("boot_count",clock.bootCount)
                    },"source=?",arrayOf(source.name))
                }
            }
        }
        return reload()
    }
    fun boundaryFailed(source: PaymentSource, generation: Long, reason: String) {
        database.writableDatabase.update("source_policies",ContentValues().apply { put("failure",reason) },
            "source=? AND generation=? AND state='ARMING'",arrayOf(source.name,generation.toString()))
        reload()
    }
    /** The caller obtains the system snapshot outside every database transaction. */
    fun completeBoundary(source: PaymentSource, generation: Long, notifications: List<RawNotification>, clock: IntakeClock): Boolean {
        val old = readPolicy(database.readableDatabase,source) ?: return false
        if(old.generation != generation || old.state != SourcePolicyState.ARMING) return false
        if(!clockReliable(old.enabledAt,clock)) { boundaryFailed(source,generation,"CLOCK_UNCERTAIN"); return false }
        val previousIdentities = try { readBoundaryIdentities(database.readableDatabase,cipher,old).orEmpty() }
        catch (_: Exception) { boundaryFailed(source,generation,"ENCRYPTION"); return false }
        val identities = (previousIdentities + notifications.filter { paymentSource(it.packageName) == source && it.postTime <= old.enabledAt.wallTime }.map { it.notificationKey }).distinct()
        if(identities.size > NotificationInboxRepository.MAX_ITEMS) { boundaryFailed(source,generation,"CAPACITY"); return false }
        val plain = JSONArray(identities).toString().toByteArray(Charsets.UTF_8)
        if(plain.size > NotificationInboxRepository.MAX_TOTAL_BYTES) { plain.fill(0); boundaryFailed(source,generation,"CAPACITY"); return false }
        val encrypted = try { cipher.encrypt(plain,boundaryIdentity(source,generation)) }
        catch (_: Exception) { boundaryFailed(source,generation,"ENCRYPTION"); return false }
        finally { plain.fill(0) }
        val committed = database.writableDatabase.intakeTransaction { db ->
            val current = readPolicy(db,source) ?: return@intakeTransaction false
            if(current.generation != generation || current.state != SourcePolicyState.ARMING) return@intakeTransaction false
            val usage = NotificationInboxRepository.payloadUsage(db)
            val replaced = db.rawQuery("SELECT boundary_count,COALESCE(length(boundary_payload)+length(boundary_nonce),0) FROM source_policies WHERE source=?",arrayOf(source.name)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
            if(usage.first-replaced.first + identities.size > NotificationInboxRepository.MAX_ITEMS || usage.second-replaced.second + encrypted.ciphertext.size + encrypted.nonce.size > NotificationInboxRepository.MAX_TOTAL_BYTES) {
                db.execSQL("UPDATE source_policies SET failure='CAPACITY' WHERE source=?",arrayOf(source.name))
                return@intakeTransaction false
            }
            db.update("source_policies",ContentValues().apply {
                put("state",SourcePolicyState.ON.name); putNull("failure"); put("boundary_payload",encrypted.ciphertext); put("boundary_nonce",encrypted.nonce); put("boundary_count",identities.size)
            },"source=?",arrayOf(source.name))
            true
        }
        reload()
        if(!committed) return false
        // Release only verifiably post-enable live callbacks. A restore quarantine never enters here.
        val repo = NotificationInboxRepository(database,cipher)
        repo.listQuarantined().filter { it.source == source && it.sourceGeneration == generation && it.reason == QuarantineReason.ENABLE_BOUNDARY }.forEach { row ->
            val raw = repo.readPayload(row.intakeId,clock.wallTime) ?: return@forEach
            database.writableDatabase.intakeTransaction { db ->
                val policy = readPolicy(db,source) ?: return@intakeTransaction
                if(policy.state == SourcePolicyState.ON && policy.generation == generation &&
                    NotificationInboxRepository.control(db,"restore_barrier") == null &&
                    classify(db,cipher,policy,raw,IntakeClock(row.receivedAt,row.receivedElapsed,row.bootCount)) == null) {
                    db.execSQL("UPDATE notification_inbox SET state='PENDING',reason=NULL WHERE intake_id=? AND state='QUARANTINED' AND reason='ENABLE_BOUNDARY'",arrayOf(row.intakeId))
                }
            }
        }
        return true
    }
    fun classify(raw: RawNotification, clock: IntakeClock): QuarantineReason? {
        val source = paymentSource(raw.packageName) ?: return QuarantineReason.ENABLE_BOUNDARY
        val policy = readPolicy(database.readableDatabase,source) ?: return QuarantineReason.INITIALIZING
        return if(policy.state != SourcePolicyState.ON) QuarantineReason.ENABLE_BOUNDARY else classify(database.readableDatabase,cipher,policy,raw,clock)
    }
    companion object {
        internal fun readPolicy(db: SQLiteDatabase, source: PaymentSource): SourcePolicy? = db.rawQuery("SELECT selected,generation,state,enabled_wall,enabled_elapsed,boot_count,failure FROM source_policies WHERE source=?",arrayOf(source.name)).use { c ->
            if(!c.moveToFirst()) null else SourcePolicy(source,c.getInt(0)!=0,c.getLong(1),SourcePolicyState.valueOf(c.getString(2)),IntakeClock(c.getLong(3),c.getLong(4),if(c.isNull(5)) null else c.getInt(5)),c.getString(6))
        }
        private fun writePolicy(db: SQLiteDatabase, policy: SourcePolicy) {
            db.insertWithOnConflict("source_policies",null,ContentValues().apply {
                put("source",policy.source.name); put("selected",if(policy.selected) 1 else 0); put("generation",policy.generation)
                put("state",policy.state.name); put("enabled_wall",policy.enabledAt.wallTime); put("enabled_elapsed",policy.enabledAt.elapsedTime)
                put("boot_count",policy.enabledAt.bootCount); put("failure",policy.failure)
            },SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
        fun clockReliable(anchor: IntakeClock, now: IntakeClock): Boolean = anchor.bootCount != null && anchor.bootCount == now.bootCount &&
            now.elapsedTime >= anchor.elapsedTime && abs((now.wallTime-anchor.wallTime)-(now.elapsedTime-anchor.elapsedTime)) <= 2_000
        private fun boundaryIdentity(source: PaymentSource,generation: Long) = "boundary:1:${source.name}:$generation"
        private fun readBoundaryIdentities(db: SQLiteDatabase,cipher: PayloadCipher,policy: SourcePolicy): List<String>? {
            val encrypted = db.rawQuery("SELECT boundary_payload,boundary_nonce FROM source_policies WHERE source=?",arrayOf(policy.source.name)).use {
                if(!it.moveToFirst() || it.isNull(0)) null else EncryptedPayload(it.getBlob(0),it.getBlob(1))
            } ?: return null
            val bytes = cipher.decrypt(encrypted,boundaryIdentity(policy.source,policy.generation))
            return try { JSONArray(bytes.toString(Charsets.UTF_8)).let { array -> List(array.length()) { array.getString(it) } } }
            finally { bytes.fill(0) }
        }
        internal fun classify(db: SQLiteDatabase,cipher: PayloadCipher,policy: SourcePolicy,raw: RawNotification,clock: IntakeClock): QuarantineReason? {
            if(!clockReliable(policy.enabledAt,clock)) return QuarantineReason.CLOCK_UNCERTAIN
            if(raw.postTime <= policy.enabledAt.wallTime || raw.postTime > clock.wallTime + 2_000) return QuarantineReason.ENABLE_BOUNDARY
            val encrypted = db.rawQuery("SELECT boundary_payload,boundary_nonce FROM source_policies WHERE source=?",arrayOf(policy.source.name)).use {
                if(!it.moveToFirst() || it.isNull(0)) null else EncryptedPayload(it.getBlob(0),it.getBlob(1))
            } ?: return QuarantineReason.ENABLE_BOUNDARY
            val bytes = try { cipher.decrypt(encrypted,boundaryIdentity(policy.source,policy.generation)) }
            catch (_: Exception) { return QuarantineReason.KEY_UNAVAILABLE }
            return try {
                val identities = JSONArray(bytes.toString(Charsets.UTF_8))
                if((0 until identities.length()).any { identities.getString(it) == raw.notificationKey }) QuarantineReason.ENABLE_BOUNDARY else null
            } catch (_: Exception) { QuarantineReason.PAYLOAD_INVALID } finally { bytes.fill(0) }
        }
    }
}
