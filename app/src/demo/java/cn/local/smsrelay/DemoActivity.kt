package cn.local.smsrelay

import android.os.Bundle
import cn.local.smsrelay.core.RelayConfig

/** Isolated screenshot fixture. The demo manifest removes all telephony permissions and receivers. */
class DemoActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("introduced", true).commit()
        RelayStore(this).use { store ->
            store.save(RelayConfig("+12025550101\n+12025550102", "+12025550103\n+12025550104", -1))
            val attempts = store.claim("demo-fixture", 1, 1, false,
                "【演示短信】这是虚构的转发内容。\n用于展示正文查看功能，没有实际发送短信。",
                listOf("+12025550103", "+12025550104"))
            attempts?.forEachIndexed { index, attempt ->
                if (index == 0) store.partResult(attempt.id, 0, -1)
            }
        }
        super.onCreate(savedInstanceState)
    }
}
