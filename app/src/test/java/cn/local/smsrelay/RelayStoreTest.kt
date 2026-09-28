package cn.local.smsrelay

import android.content.Context
import cn.local.smsrelay.core.RelayConfig
import cn.local.smsrelay.core.SendState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayStoreTest {
    private lateinit var context: Context
    private lateinit var store: RelayStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("relay.db")
        context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = RelayStore(context)
    }
    @After fun close() { store.close() }

    @Test fun allModePersistsAndOldSettingsRemainRestricted() {
        assertFalse(store.config().forwardAll)
        store.save(RelayConfig("106123", "13800138000", 3, true))
        val prefs = context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("enabled_at", 1L).commit()
        store.save(store.config().copy(forwardAll = true))
        assertTrue(store.config().forwardAll)
        assertTrue(store.config().enabledAt > 1L)
        val since = store.config().enabledAt
        store.save(store.config())
        assertEquals(since, store.config().enabledAt)
        store.close()
        store = RelayStore(context)
        assertTrue(store.config().forwardAll)
        store.save(store.config().copy(forwardAll = false))
        assertEquals("106123", store.config().source)
        assertFalse(store.config().forwardAll)
    }

    @Test fun reopeningDatabaseDoesNotResendAnAlreadyClaimedEvent() {
        assertNotNull(store.claim("fingerprint1", 3, 2, false, "测试正文"))
        store.close()
        store = RelayStore(context)
        assertNull(store.claim("fingerprint1", 3, 2, false, "测试正文"))
        assertEquals(1, store.records().size)
        assertEquals(SendState.UNKNOWN, store.records().single().state)
    }
    @Test fun multipartAndDuplicateCallbacksAreAggregatedWithoutOverwriting() {
        val id = store.claim("fingerprint", 3, 2, false, "测试正文")!!
        store.partResult(id, 0, -1)
        assertEquals(SendState.UNKNOWN, store.records().single().state)
        store.partResult(id, 0, 4)
        store.partResult(id, 99, 4)
        store.partResult("unknown", 1, 4)
        store.partResult(id, 1, -1)
        assertEquals(SendState.SENT, store.records().single().state)
        assertEquals(2, store.records().single().success)
    }
    @Test fun failureIsStickyEvenIfOtherPartsLaterReportSuccess() {
        val id = store.claim("fingerprint", 3, 2, false, "测试正文")!!
        store.partResult(id, 0, 4)
        store.partResult(id, 1, -1)
        assertEquals(SendState.FAILED, store.records().single().state)
        assertEquals("MODEM_4", store.records().single().error)
    }
    @Test fun submissionErrorCannotBeChangedToSuccessByLateCallbacks() {
        val id = store.claim("fingerprint", 3, 1, false, "测试正文")!!
        store.fail(id, "SUBMISSION_ERROR")
        store.partResult(id, 0, -1)
        assertEquals(SendState.FAILED, store.records().single().state)
    }
    @Test fun pruningVisibleHistoryPreservesDedupeAcrossReopen() {
        repeat(205) { store.claim("event$it", 3, 1, false, "测试正文") }
        assertEquals(200, store.records().size)
        store.close()
        store = RelayStore(context)
        assertNull(store.claim("event0", 3, 1, false, "测试正文"))
        assertEquals(200, store.records().size)
    }
    @Test fun configSurvivesReopenAndSavingUnchangedSettingsKeepsActivationTime() {
        val config = RelayConfig("106123456", "13800138000", 3, true)
        store.save(config)
        val first = store.config()
        assertTrue(first.enabledAt > 0)
        store.close()
        store = RelayStore(context)
        assertEquals(first, store.config())
        store.save(config)
        assertEquals(first.enabledAt, store.config().enabledAt)
        store.save(config.copy(enabled = false))
        assertFalse(store.config().enabled)
        assertEquals(0L, store.config().enabledAt)
    }
    @Test fun fullBodySurvivesReopenAndDuplicateDoesNotReplaceIt() {
        val body = "【测试】验证码 123456\n" + "长短信内容🙂".repeat(60)
        val id = store.claim("content", 3, 4, false, body)!!
        store.close()
        store = RelayStore(context)
        assertEquals(body, store.messageBody(id))
        assertNull(store.claim("content", 3, 4, false, "不同内容"))
        assertEquals(body, store.messageBody(id))
    }
    @Test fun pruningRemovesBodyButKeepsDedupe() {
        val old = store.claim("old", 3, 1, false, "旧正文")!!
        repeat(200) { store.claim("new$it", 3, 1, false, "新正文$it") }
        assertNull(store.messageBody(old))
        assertNull(store.claim("old", 3, 1, false, "旧正文"))
        assertEquals(200, store.records().size)
    }
    @Test fun versionOneMigrationPreservesSettingsHistoryAndDedupe() {
        store.save(RelayConfig("+8613800138000", "13900139000", 3, true))
        val config = store.config()
        store.close()
        context.deleteDatabase("relay.db")
        context.openOrCreateDatabase("relay.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE events (event_key TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE attempts (id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, subscription_id INTEGER NOT NULL, is_test INTEGER NOT NULL, part_count INTEGER NOT NULL, error TEXT)")
            db.execSQL("CREATE TABLE parts (attempt_id TEXT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE, part_index INTEGER NOT NULL, result INTEGER, PRIMARY KEY(attempt_id, part_index))")
            db.execSQL("INSERT INTO events VALUES ('legacy-event')")
            db.execSQL("INSERT INTO attempts VALUES ('legacy-id', 1, 3, 0, 1, NULL)")
            db.execSQL("INSERT INTO parts VALUES ('legacy-id', 0, -1)")
            db.version = 1
        }
        store = RelayStore(context)
        assertEquals(config, store.config())
        assertEquals(SendState.SENT, store.records().single().state)
        assertNull(store.messageBody("legacy-id"))
        assertNull(store.claim("legacy-event", 3, 1, false, "不可补写旧正文"))
        val id = store.claim("after-upgrade", 3, 1, false, "升级后正文")!!
        assertEquals("升级后正文", store.messageBody(id))
        assertEquals(3, store.readableDatabase.version)
    }
    @Test fun multipleSourcesAreDeduplicatedAndRetainedAcrossReopen() {
        store.save(RelayConfig(" 106123456，+8613900139000\n106123456 ", "13800138000", 3, true))
        store.close()
        store = RelayStore(context)
        assertEquals("106123456\n+8613900139000", store.config().source)
        assertEquals("13800138000", store.config().destination)
        val since = store.config().enabledAt
        store.save(store.config().copy(source = "+8613900139000,106123456"))
        assertEquals(since, store.config().enabledAt)
    }
    @Test fun legacySingleSourceSettingsStillLoadWithoutResettingEnablement() {
        context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE).edit()
            .putString("source", "+8613900139000").putString("destination", "13800138000")
            .putInt("subscription", 3).putBoolean("enabled", true).putLong("enabled_at", 1234).commit()
        val config = store.config()
        assertEquals("+8613900139000", config.source)
        assertEquals(1234L, config.enabledAt)
        assertTrue(cn.local.smsrelay.core.RelayRules.matches(config, "+8613900139000", 1235))
    }
    @Test fun multipleDestinationsPersistAndReorderingDoesNotResetEnableTime() {
        store.save(RelayConfig("106123456", " 13800138000，13900139000\n13800138000 ", 3, true))
        store.close()
        store = RelayStore(context)
        assertEquals("13800138000\n13900139000", store.config().destination)
        val enabledAt = store.config().enabledAt
        store.save(store.config().copy(destination = "13900139000,13800138000"))
        assertEquals(enabledAt, store.config().enabledAt)
    }
    @Test fun targetRecordsAndCallbacksStayIndependentAfterReopen() {
        val batch = store.claim("batch", 3, 2, false, "完整正文", listOf("13800138000", "13900139000", "13800138000"))!!
        assertEquals(2, batch.size)
        store.partResult(batch[0].id, 0, -1)
        store.partResult(batch[0].id, 1, -1)
        store.partResult(batch[1].id, 0, 4)
        store.close()
        store = RelayStore(context)
        val byTarget = store.records().associateBy { it.destination }
        assertEquals(SendState.SENT, byTarget["13800138000"]!!.state)
        assertEquals(SendState.FAILED, byTarget["13900139000"]!!.state)
        batch.forEach { assertEquals("完整正文", store.messageBody(it.id)) }
        assertNull(store.claim("batch", 3, 2, false, "完整正文", listOf("13700137000")))
    }
    @Test fun failedBatchWriteRollsBackEventAndAllTargets() {
        store.writableDatabase.execSQL("CREATE TRIGGER reject_second BEFORE INSERT ON attempts WHEN NEW.destination = '13900139000' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            store.claim("atomic", 3, 1, false, "正文", listOf("13800138000", "13900139000"))
            fail("second target insertion should fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(store.records().isEmpty())
        store.writableDatabase.execSQL("DROP TRIGGER reject_second")
        assertEquals(2, store.claim("atomic", 3, 1, false, "正文", listOf("13800138000", "13900139000"))!!.size)
    }
    @Test fun versionTwoMigrationPreservesBodyAndDoesNotInventHistoricalTarget() {
        store.save(RelayConfig("106123456", "13800138000", 3, true))
        val config = store.config()
        store.close()
        context.deleteDatabase("relay.db")
        context.openOrCreateDatabase("relay.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE events (event_key TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE attempts (id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, subscription_id INTEGER NOT NULL, is_test INTEGER NOT NULL, part_count INTEGER NOT NULL, error TEXT, body TEXT)")
            db.execSQL("CREATE TABLE parts (attempt_id TEXT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE, part_index INTEGER NOT NULL, result INTEGER, PRIMARY KEY(attempt_id, part_index))")
            db.execSQL("INSERT INTO events VALUES ('v2-event')")
            db.execSQL("INSERT INTO attempts VALUES ('v2-id', 1, 3, 0, 1, NULL, '旧版正文')")
            db.execSQL("INSERT INTO parts VALUES ('v2-id', 0, -1)")
            db.version = 2
        }
        store = RelayStore(context)
        assertEquals(config, store.config())
        assertEquals("旧版正文", store.messageBody("v2-id"))
        assertNull(store.records().single().destination)
        assertNull(store.claim("v2-event", 3, 1, false, "旧版正文", listOf("13900139000")))
        assertEquals(3, store.readableDatabase.version)
    }
    @Test fun concurrentClaimsProduceOnlyOneAttempt() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map { pool.submit(java.util.concurrent.Callable {
                RelayStore(context).use { it.claim("same", 3, 1, false, "测试正文") }
            }) }
            assertEquals(1, futures.map { it.get() }.count { it != null })
            assertEquals(1, store.records().size)
        } finally { pool.shutdownNow() }
    }
}
