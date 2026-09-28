package cn.local.smsrelay

/** Adapter for existing single-target persistence fixtures. */
fun RelayStore.claim(eventKey: String, subscriptionId: Int, partCount: Int, test: Boolean, body: String): String? =
    claim(eventKey, subscriptionId, partCount, test, body, listOf("13800138000"))?.single()?.id
