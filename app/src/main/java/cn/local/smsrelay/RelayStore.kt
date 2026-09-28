package cn.local.smsrelay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.local.smsrelay.core.ClaimedAttempt
import cn.local.smsrelay.core.RelayConfig
import cn.local.smsrelay.core.RelayJournal
import cn.local.smsrelay.core.RelayRules
import cn.local.smsrelay.core.SendState
import java.util.UUID

/** All entry points share this lock: saving/disabling cannot race an in-process submission. */
object RelayLock { val monitor = Any() }

data class SendRecord(
    val id: String, val createdAt: Long, val subscriptionId: Int, val test: Boolean,
    val total: Int, val success: Int, val state: SendState, val error: String?, val destination: String?
)

class RelayStore(context: Context) : SQLiteOpenHelper(context, "relay.db", null, 3), RelayJournal {
    private val prefs = context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (event_key TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE attempts (id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, subscription_id INTEGER NOT NULL, is_test INTEGER NOT NULL, part_count INTEGER NOT NULL, error TEXT, body TEXT, destination TEXT)")
        db.execSQL("CREATE TABLE parts (attempt_id TEXT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE, part_index INTEGER NOT NULL, result INTEGER, PRIMARY KEY(attempt_id, part_index))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Existing attempts keep NULL: the old version never stored their contents.
        if (oldVersion < 2) db.execSQL("ALTER TABLE attempts ADD COLUMN body TEXT")
        if (oldVersion < 3) db.execSQL("ALTER TABLE attempts ADD COLUMN destination TEXT")
    }

    fun config(): RelayConfig = synchronized(RelayLock.monitor) {
        RelayConfig(prefs.getString("source", "")!!, prefs.getString("destination", "")!!,
            prefs.getInt("subscription", -1), prefs.getBoolean("enabled", false), prefs.getLong("enabled_at", 0),
            prefs.getBoolean("forward_all", false))
    }

    fun save(config: RelayConfig) = synchronized(RelayLock.monitor) {
        val before = config()
        val numbers = RelayRules.sources(config.source)
        val targets = RelayRules.destinations(config.destination)
        val unchanged = RelayRules.sources(before.source).toSet() == numbers.toSet() && RelayRules.destinations(before.destination).toSet() == targets.toSet() &&
            before.subscriptionId == config.subscriptionId && before.forwardAll == config.forwardAll && before.enabled
        val since = if (!config.enabled) 0L else if (unchanged) before.enabledAt else System.currentTimeMillis()
        check(prefs.edit().putString("source", numbers.joinToString("\n")).putString("destination", targets.joinToString("\n"))
            .putInt("subscription", config.subscriptionId).putBoolean("enabled", config.enabled)
            .putBoolean("forward_all", config.forwardAll).putLong("enabled_at", since).commit()) { "SETTINGS_WRITE_FAILED" }
    }

    override fun claim(eventKey: String, subscriptionId: Int, partCount: Int, test: Boolean, body: String, destinations: List<String>): List<ClaimedAttempt>? =
        synchronized(RelayLock.monitor) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val inserted = db.insertWithOnConflict("events", null,
                    ContentValues().apply { put("event_key", eventKey) }, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) return@synchronized null
                require(destinations.isNotEmpty()) { "EMPTY_DESTINATIONS" }
                val attempts = destinations.distinct().map { destination ->
                    val id = UUID.randomUUID().toString()
                    db.insertOrThrow("attempts", null, ContentValues().apply {
                        put("id", id); put("created_at", System.currentTimeMillis())
                        put("subscription_id", subscriptionId); put("is_test", if (test) 1 else 0)
                        put("part_count", partCount); put("body", body); put("destination", destination)
                    })
                    repeat(partCount) { index -> db.insertOrThrow("parts", null, ContentValues().apply {
                        put("attempt_id", id); put("part_index", index)
                    }) }
                    ClaimedAttempt(id, destination)
                }
                // Bounded local history includes body; event fingerprints survive pruning.
                db.execSQL("DELETE FROM attempts WHERE id NOT IN (SELECT id FROM attempts ORDER BY created_at DESC, rowid DESC LIMIT 200)")
                db.setTransactionSuccessful()
                attempts
            } finally { db.endTransaction() }
        }

    override fun fail(id: String, reason: String) = synchronized(RelayLock.monitor) {
        writableDatabase.update("attempts", ContentValues().apply { put("error", reason) }, "id = ?", arrayOf(id))
        Unit
    }

    fun partResult(id: String, index: Int, result: Int) = synchronized(RelayLock.monitor) {
        // A duplicate or delayed callback must never overwrite a previously recorded result.
        writableDatabase.update("parts", ContentValues().apply { put("result", result) },
            "attempt_id = ? AND part_index = ? AND result IS NULL", arrayOf(id, index.toString()))
        Unit
    }

    fun messageBody(id: String): String? = synchronized(RelayLock.monitor) {
        readableDatabase.rawQuery("SELECT body FROM attempts WHERE id = ?", arrayOf(id)).use { rows ->
            if (rows.moveToFirst() && !rows.isNull(0)) rows.getString(0) else null
        }
    }

    fun records(): List<SendRecord> = synchronized(RelayLock.monitor) {
        val records = mutableListOf<SendRecord>()
        readableDatabase.rawQuery("SELECT id, created_at, subscription_id, is_test, part_count, error, destination FROM attempts ORDER BY created_at DESC, rowid DESC LIMIT 200", null).use { rows ->
            while (rows.moveToNext()) {
                val results = mutableListOf<Int?>()
                readableDatabase.rawQuery("SELECT result FROM parts WHERE attempt_id = ? ORDER BY part_index", arrayOf(rows.getString(0))).use { parts ->
                    while (parts.moveToNext()) results += if (parts.isNull(0)) null else parts.getInt(0)
                }
                val error = if (rows.isNull(5)) null else rows.getString(5)
                val resultError = results.firstOrNull { it != null && it != -1 }?.let { "MODEM_$it" }
                records += SendRecord(rows.getString(0), rows.getLong(1), rows.getInt(2), rows.getInt(3) != 0,
                    rows.getInt(4), results.count { it == -1 },
                    if (error != null) SendState.FAILED else RelayRules.state(results), error ?: resultError, if (rows.isNull(6)) null else rows.getString(6))
            }
        }
        records
    }
}
