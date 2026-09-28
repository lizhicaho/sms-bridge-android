package cn.local.smsrelay.core

data class RelayConfig(
    val source: String = "",
    val destination: String = "",
    val subscriptionId: Int = -1,
    val enabled: Boolean = false,
    val enabledAt: Long = 0,
    val forwardAll: Boolean = false
)

enum class SendState { UNKNOWN, SENT, FAILED }

object RelayRules {
    /** Keep full number strings, including +86; only list separators and outer whitespace are removed. */
    fun sources(input: String): List<String> = input.split(Regex("[,，\\r\\n]+"))
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun destinations(input: String): List<String> = sources(input)

    fun validationError(config: RelayConfig): String? {
        val numbers = sources(config.source)
        val targets = destinations(config.destination)
        return when {
            !config.forwardAll && numbers.isEmpty() -> "请至少填写一个来源号码，每行一个"
            !config.forwardAll && numbers.any { !Regex("\\+?[0-9]{3,30}").matches(it) } -> "来源号码格式不正确，请每行填写一个完整号码（号码内不能有空格）"
            targets.isEmpty() -> "请至少填写一个目标号码，每行一个"
            targets.any { !Regex("\\+?[0-9]{5,20}").matches(it) } -> "目标号码格式不正确，请每行填写一个号码（号码内不能有空格）"
            !config.forwardAll && targets.any { it in numbers } -> "接收号码不能与任何来源号码相同"
            config.subscriptionId < 0 -> "请选择发送 SIM 卡"
            else -> null
        }
    }
    fun matches(config: RelayConfig, sender: String, receivedAt: Long): Boolean =
        config.enabled && validationError(config) == null &&
            (config.forwardAll || sender in sources(config.source)) && receivedAt >= config.enabledAt
    fun state(results: List<Int?>): SendState = when {
        results.any { it != null && it != -1 } -> SendState.FAILED
        results.isNotEmpty() && results.all { it == -1 } -> SendState.SENT
        else -> SendState.UNKNOWN
    }
}
