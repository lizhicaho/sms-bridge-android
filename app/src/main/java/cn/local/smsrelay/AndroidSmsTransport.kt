package cn.local.smsrelay

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import cn.local.smsrelay.core.SmsTransport
import cn.local.smsrelay.core.Submission

class AndroidSmsTransport(private val context: Context) : SmsTransport {
    override fun unavailableReason(subscriptionId: Int): String? {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return "PERMISSION_DENIED"
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty()
        return if (subscriptions.any { it.subscriptionId == subscriptionId }) null else "SIM_UNAVAILABLE"
    }

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int): SmsManager = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
    } else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)

    override fun split(body: String, subscriptionId: Int): List<String> = manager(subscriptionId).divideMessage(body)

    override fun submit(submission: Submission) {
        // Recheck before submission; never silently substitute the default SIM.
        check(unavailableReason(submission.subscriptionId) == null) { "SIM_OR_PERMISSION_CHANGED" }
        val sent = ArrayList(submission.parts.indices.map { index ->
            val intent = Intent(context, SentReceiver::class.java).apply {
                action = ACTION_SENT
                data = Uri.parse("smsrelay://sent/${submission.id}/$index")
                putExtra("attempt", submission.id)
                putExtra("part", index)
            }
            PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        })
        val sms = manager(submission.subscriptionId)
        if (submission.parts.size == 1) {
            sms.sendTextMessage(submission.destination, null, submission.parts.single(), sent.single(), null)
        } else {
            sms.sendMultipartTextMessage(submission.destination, null, ArrayList(submission.parts), sent, null)
        }
    }

    companion object { const val ACTION_SENT = "cn.local.smsrelay.SMS_SENT" }
}
