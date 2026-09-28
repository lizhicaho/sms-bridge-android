package cn.local.smsrelay.core

import javax.crypto.SecretKey
import javax.crypto.Mac
import java.nio.ByteBuffer

object EventKey {
    fun compute(key: SecretKey, pdus: List<ByteArray>, subscriptionId: Int): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        mac.update(ByteBuffer.allocate(4).putInt(subscriptionId).array())
        pdus.forEach { pdu ->
            mac.update(ByteBuffer.allocate(4).putInt(pdu.size).array())
            mac.update(pdu)
        }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
