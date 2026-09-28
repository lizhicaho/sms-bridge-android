package cn.local.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.SubscriptionManager
import cn.local.smsrelay.core.IncomingSms
import cn.local.smsrelay.core.RelayEngine
import cn.local.smsrelay.core.RelayRules
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object RelayWorker { val executor = Executors.newSingleThreadExecutor() }

class IncomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val receivedAt = System.currentTimeMillis()
        val pending = goAsync()
        RelayWorker.executor.execute {
            try {
                synchronized(RelayLock.monitor) {
                    RelayStore(context).use { store ->
                        val config = store.config()
                        if (!config.enabled) return@use
                        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                        if (messages.isNullOrEmpty()) return@use
                        val sender = messages.first().originatingAddress ?: return@use
                        if (messages.any { it.originatingAddress != sender }) return@use
                        if (!RelayRules.matches(config, sender, receivedAt)) return@use
                        // Android dispatches a completed multipart SMS with its PDUs in segment order.
                        if (messages.any { it.messageBody == null }) {
                            HealthNotice.set(context, "短信内容无法完整解析，本次未转发。")
                            return@use
                        }
                        val body = messages.joinToString("") { it.messageBody }
                        val raw = intent.extras?.get("pdus") as? Array<*> ?: return@use
                        val pdus = raw.map { it as? ByteArray ?: return@use }
                        val incomingSim = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                            intent.getIntExtra("subscription", -1))
                        RelayEngine(store, AndroidSmsTransport(context)).forward(config,
                            IncomingSms(sender, body, receivedAt, EventFingerprint.create(pdus, incomingSim)))
                    }
                }
            } catch (_: Exception) {
                // Fail closed. No body, sender, exception text, or OTP is written to logcat.
                HealthNotice.set(context, "处理短信时发生本地错误，本次未自动重试。请检查权限、存储空间和发送记录。")
            } finally { pending.finish() }
        }
    }
}

class SentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidSmsTransport.ACTION_SENT) return
        val id = intent.getStringExtra("attempt") ?: return
        val index = intent.getIntExtra("part", -1)
        if (index < 0) return
        val code = resultCode
        val pending = goAsync()
        RelayWorker.executor.execute {
            try { RelayStore(context).use { it.partResult(id, index, code) } }
            catch (_: RuntimeException) { HealthNotice.set(context, "发送回执未能保存，请将对应记录视为状态未知。") }
            finally { pending.finish() }
        }
    }
}

object EventFingerprint {
    private const val ALIAS = "smsrelay-event-hmac-v1"
    fun create(pdus: List<ByteArray>, subscriptionId: Int): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
        return cn.local.smsrelay.core.EventKey.compute(key, pdus, subscriptionId)
    }
}

object HealthNotice {
    fun set(context: Context, message: String) {
        context.getSharedPreferences("health", Context.MODE_PRIVATE).edit().putString("notice", message).apply()
    }
    fun get(context: Context): String = context.getSharedPreferences("health", Context.MODE_PRIVATE)
        .getString("notice", "").orEmpty()
}
