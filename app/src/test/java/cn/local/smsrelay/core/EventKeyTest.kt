package cn.local.smsrelay.core

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class EventKeyTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
    @Test fun stableForSameEventAndDifferentForNewPduOrReceivingSim() {
        val first = EventKey.compute(key, listOf(byteArrayOf(1, 2, 3)), 1)
        assertEquals(64, first.length)
        assertEquals(first, EventKey.compute(key, listOf(byteArrayOf(1, 2, 3)), 1))
        assertNotEquals(first, EventKey.compute(key, listOf(byteArrayOf(1, 2, 4)), 1))
        assertNotEquals(first, EventKey.compute(key, listOf(byteArrayOf(1, 2, 3)), 2))
    }
    @Test fun segmentBoundariesAndOrderCannotCollide() {
        val first = EventKey.compute(key, listOf(byteArrayOf(1, 2), byteArrayOf(3)), 1)
        assertNotEquals(first, EventKey.compute(key, listOf(byteArrayOf(1), byteArrayOf(2, 3)), 1))
        assertNotEquals(first, EventKey.compute(key, listOf(byteArrayOf(3), byteArrayOf(1, 2)), 1))
    }
    @Test fun installationSecretChangesFingerprint() {
        val first = EventKey.compute(key, listOf("验证码123456".toByteArray()), 1)
        val other = SecretKeySpec(ByteArray(32) { 8 }, "HmacSHA256")
        assertNotEquals(first, EventKey.compute(other, listOf("验证码123456".toByteArray()), 1))
        assertFalse(first.contains("123456"))
    }
}
