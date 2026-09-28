package cn.local.smsrelay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import cn.local.smsrelay.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

open class MainActivity : Activity() {
    private lateinit var store: RelayStore
    private lateinit var allMessages: Switch
    private lateinit var source: EditText
    private lateinit var destination: EditText
    private lateinit var sim: Spinner
    private lateinit var toggle: Switch
    private lateinit var status: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var records: LinearLayout
    private var subscriptions: List<SubscriptionInfo> = emptyList()
    private var updating = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshStatus(); refreshRecords(); handler.postDelayed(this, 3_000) }
    }
    private val ink = Color.rgb(25, 39, 65)
    private val muted = Color.rgb(101, 115, 138)
    private val accent = Color.rgb(49, 94, 220)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.getBoolean(R.bool.protect_screens)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = RelayStore(this)
        buildUi()
        val config = store.config()
        source.setText(config.source)
        allMessages.isChecked = config.forwardAll
        source.isEnabled = !config.forwardAll
        destination.setText(config.destination)
        loadSims(config.subscriptionId)
        updating = true
        toggle.isChecked = config.enabled
        updating = false
        if (savedInstanceState == null && !getPreferences(MODE_PRIVATE).getBoolean("introduced", false)) {
            showGuide()
            getPreferences(MODE_PRIVATE).edit().putBoolean("introduced", true).apply()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(242, 245, 251)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(32)) }
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        root.addView(label(getString(R.string.app_name), 30, bold = true))
        root.addView(label("让重要信息，及时到达。", 14, muted))

        val overview = card(root)
        status = label("已暂停", 21, bold = true)
        overview.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(229, 238, 255), Color.rgb(242, 247, 255))).apply { cornerRadius = dp(24).toFloat() }
        overview.addView(label("转发状态", 12, accent, true))
        overview.addView(status)
        overview.addView(label("A 手机  →  多个目标手机\n各目标直接使用系统短信接收。", 15))
        toggle = Switch(this).apply {
            text = "启用自动转发"
            minHeight = dp(52)
            setTextColor(ink)
            setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    if (checked) {
                        if (!saveSettings(true)) setToggle(false)
                    } else {
                        try { store.save(store.config().copy(enabled = false)); refreshStatus() }
                        catch (_: RuntimeException) { setToggle(store.config().enabled); toast("暂停失败，请重试或在系统中强行停止应用") }
                    }
                }
            }
        }
        overview.addView(toggle)
        overview.addView(label("关闭可停止后续转发；已经提交的短信无法撤回。", 12, muted))

        val settings = card(root)
        settings.addView(label("01  转发规则", 19, bold = true))
        allMessages = Switch(this).apply {
            id = R.id.forward_all
            text = "转发全部新短信"
            minHeight = dp(56)
            setTextColor(ink)
            setOnCheckedChangeListener { _, checked ->
                if (::source.isInitialized) {
                    source.isEnabled = !checked
                    source.alpha = if (checked) 0.45f else 1f
                }
            }
        }
        settings.addView(allMessages)
        settings.addView(label("关闭：仅转发下方指定来源。开启：不限制来源，所有新短信都发往全部目标。修改后请保存。", 12, muted))
        source = input(settings, "来源号码（可多个，每行一个）", "例如：\n+8613800138000\n106123456", R.id.source_number).apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        settings.addView(label("也支持中文或英文逗号分隔。任一号码完整匹配就转发；请保留实际发件号码的 +86。", 12, muted))
        destination = input(settings, "目标号码（可多个，每行一个）", "接收转发短信的手机号", R.id.destination_number).apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        settings.addView(label("符合当前范围的新短信向全部目标各发送一次。多个目标分别计费；请勿填写 A 手机自身号码，避免循环转发。", 12, muted))
        settings.addView(label("发送 SIM 卡", 14, bold = true))
        sim = Spinner(this).apply { id = R.id.sending_sim; minimumHeight = dp(52) }
        settings.addView(sim)
        settings.addView(label("接收可来自 A 手机任一 SIM；发送只使用这里选中的卡。换卡后请重新选择。", 12, muted))
        button(settings, "保存设置") { saveSettings(toggle.isChecked) }
        settings.addView(label("修改号码或 SIM 后请保存。仅转发启用后的新短信，不补发历史短信。", 12, muted))

        val access = card(root)
        access.addView(label("02  权限与运行", 19, bold = true))
        permissionStatus = label("", 14, muted)
        access.addView(permissionStatus)
        button(access, "授予必要权限") {
            if (hasPermissions()) {
                loadSims(-1)
                val saved = store.config().subscriptionId
                subscriptions.indexOfFirst { it.subscriptionId == saved }.takeIf { it >= 0 }?.let { sim.setSelection(it) }
                refreshStatus()
                toast("权限已授予，SIM 列表已刷新，请选择并保存")
                return@button
            }
            AlertDialog.Builder(this).setTitle("需要三项权限")
                .setMessage("接收短信：按转发范围处理新短信。\n发送短信：由 A 手机转发到全部目标手机。\n电话状态：列出并固定发送 SIM 卡。\n\n不读取历史短信，不请求联网权限。")
                .setPositiveButton("继续") { _, _ -> requestPermissions(PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray(), 10) }
                .setNegativeButton("取消", null).show()
        }
        button(access, "打开应用设置") { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button(access, "后台运行与兼容说明") { showGuide() }

        val test = card(root)
        test.addView(label("03  验证连接", 19, bold = true))
        test.addView(label("首次启用前，先给各目标发送测试短信。测试和转发按目标分别计费，长短信每个目标还可能按多条计费。", 14, muted))
        button(test, "发送测试短信") {
            if (!saveSettings(toggle.isChecked)) return@button
            val config = store.config()
            AlertDialog.Builder(this).setTitle("向全部目标发送测试短信")
                .setMessage("共 ${RelayRules.destinations(config.destination).size} 个目标：\n${config.destination}\n发送卡：${selectedSimLabel()}\n\n每个目标各发一条测试短信，按目标分别计费。")
                .setPositiveButton("发送") { _, _ ->
                    RelayWorker.executor.execute {
                        val outcome = try {
                            synchronized(RelayLock.monitor) {
                                // Use the settings shown in the confirmation, even if the form later changes.
                                RelayStore(this).use { journal -> RelayEngine(journal, AndroidSmsTransport(this))
                                    .test(config, "test:${UUID.randomUUID()}") }
                            }
                        } catch (_: RuntimeException) { null }
                        runOnUiThread {
                            if (!isDestroyed) {
                                toast(when (outcome) {
                                    ForwardOutcome.SUBMITTED -> "已向全部目标提交，请分别确认收到"
                                    ForwardOutcome.PARTIAL -> "部分目标提交失败，请查看各目标发送记录"
                                    else -> "未能提交，请检查权限、SIM 卡及发送记录"
                                })
                                refreshRecords()
                            }
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }

        val history = card(root)
        history.addView(label("04  发送记录", 19, bold = true))
        history.addView(label("点击记录查看完整短信。成功表示系统报告已发送，不代表目标手机已收到。状态未知可能正在等待回执，也可能因重启等原因丢失回执。失败及未知均不自动重发。", 12, muted))
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        history.addView(records)
        root.addView(label("正文仅存本机 · 不上传数据\n按目标保留最近 200 条记录及正文，点击查看", 12, muted))
    }

    private fun saveSettings(enabled: Boolean): Boolean {
        val config = RelayConfig(source.text.toString().trim(), destination.text.toString().trim(),
            subscriptions.getOrNull(sim.selectedItemPosition)?.subscriptionId ?: -1, enabled, forwardAll = allMessages.isChecked)
        val error = RelayRules.validationError(config)
        if (error != null) { toast(error); return false }
        if (!hasPermissions()) { toast("请先授予接收短信、发送短信和电话状态权限"); return false }
        val unavailable = try { AndroidSmsTransport(this).unavailableReason(config.subscriptionId) }
            catch (_: RuntimeException) { "TELEPHONY_UNAVAILABLE" }
        if (unavailable != null) {
            toast("所选 SIM 卡不可用，请刷新权限并重新选择"); loadSims(config.subscriptionId); return false
        }
        return try { store.save(config); source.setText(store.config().source); destination.setText(store.config().destination); refreshStatus(); toast("设置已保存"); true }
        catch (_: RuntimeException) { toast("设置保存失败，请检查存储空间"); false }
    }

    private fun loadSims(preferred: Int) {
        subscriptions = if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try { getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex } }
            catch (_: RuntimeException) { emptyList() }
        } else emptyList()
        val labels = subscriptions.map { "SIM ${it.simSlotIndex + 1} · ${it.carrierName}（ID ${it.subscriptionId}）" }
        sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (labels.isEmpty()) listOf("暂无可用 SIM，请先授权并检查插卡") else labels)
        val index = subscriptions.indexOfFirst { it.subscriptionId == preferred }
        // Do not silently select another SIM if a saved subscription disappeared.
        if (index >= 0) sim.setSelection(index)
        else if (preferred >= 0 && subscriptions.isNotEmpty()) {
            subscriptions = emptyList()
            sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("原发送 SIM 已失效，点击“授予必要权限”刷新后重新选择"))
        }
    }

    private fun refreshStatus() {
        val config = store.config()
        val ready = hasPermissions() && try { AndroidSmsTransport(this).unavailableReason(config.subscriptionId) == null } catch (_: RuntimeException) { false }
        status.text = when { !config.enabled -> "已暂停"; !ready -> "已启用 · 需要处理"; config.forwardAll -> "运行中 · 全部新短信"; else -> "运行中 · 指定来源" }
        status.setTextColor(if (config.enabled && ready) accent else ink)
        val names = listOf("接收短信", "发送短信", "电话状态")
        permissionStatus.text = PERMISSIONS.mapIndexed { i, p -> "${names[i]}：${if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"}" }.joinToString("\n") +
            if (config.enabled && !ready) "\n请检查权限和已保存的发送 SIM 卡。" else ""
        val health = HealthNotice.get(this)
        if (health.isNotBlank()) permissionStatus.append("\n$health")
    }

    private var lastRecords: List<SendRecord>? = null
    private fun refreshRecords() {
        val current = try { store.records() } catch (_: RuntimeException) { return }
        if (current == lastRecords) return
        lastRecords = current
        records.removeAllViews()
        if (current.isEmpty()) records.addView(label("还没有发送记录", 14, muted))
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        current.forEach { record ->
            val state = when (record.state) { SendState.SENT -> "发送成功"; SendState.FAILED -> "发送失败"; SendState.UNKNOWN -> "状态未知" }
            val targetLabel = record.destination ?: "旧记录未保存目标"
            val title = "${if (record.test) "测试" else "转发"} · $state · $targetLabel"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "record:${record.id}"
                setPadding(dp(8), dp(8), dp(8), dp(8))
                val selectable = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
                isFocusable = true
                contentDescription = "$title，${format.format(Date(record.createdAt))}，点击查看短信内容"
                setOnClickListener { showMessageDetails(record, title) }
            }
            row.addView(label("$title  ›", 15, if (record.state == SendState.FAILED) Color.rgb(168, 54, 44) else ink, true))
            row.addView(label("${format.format(Date(record.createdAt))}  ·  SIM ID ${record.subscriptionId}\n已成功 ${record.success}/${record.total} 段${record.error?.let { "  ·  ${errorText(it)}" } ?: ""}", 12, muted))
            records.addView(row)
        }
    }

    private fun showMessageDetails(record: SendRecord, title: String) {
        val body = try { store.messageBody(record.id) }
            catch (_: RuntimeException) { toast("短信内容读取失败，请稍后重试"); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
        }
        panel.addView(label("目标：${record.destination ?: "旧记录未保存目标"}", 14, bold = true))
        panel.addView(label("这里显示本次尝试发送的完整内容，不代表目标手机已收到。", 12, muted))
        panel.addView(label(body ?: "这条记录没有保存正文，无法查看。旧版本未保存正文，升级后也无法恢复。", 16).apply {
            id = R.id.message_detail_body
            setTextIsSelectable(body != null)
        })
        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton("关闭", null).create()
        // Dialogs have their own windows; protect the displayed codes as well as the main screen.
        if (resources.getBoolean(R.bool.protect_screens)) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun errorText(error: String): String = when (error) {
        "PERMISSION_DENIED" -> "短信或电话权限不足"
        "SIM_UNAVAILABLE" -> "所选 SIM 不可用"
        "TELEPHONY_UNAVAILABLE" -> "系统短信服务不可用"
        "SPLIT_ERROR" -> "无法处理短信分段"
        "SUBMISSION_ERROR" -> "提交异常，部分短信可能已提交"
        "MODEM_2" -> "无线通信已关闭"
        "MODEM_4" -> "无移动网络服务"
        else -> "系统返回 $error"
    }

    private fun showGuide() {
        AlertDialog.Builder(this).setTitle("使用前请看这里")
            .setMessage("1. 可选择转发全部新短信，或仅转发指定来源。全部模式会包含验证码及其他私人短信，并按目标产生短信费用。来源号码可配置多个，每行一个，也可用逗号分隔。目标也可配置多个，任一来源完整匹配即向全部目标各转发一次；+86 等前缀需与实际发件号码一致。\n\n2. A 手机需保持开机、SIM 可发短信；无需移动数据或 Wi-Fi。\n\n3. 小米/华为请在系统设置允许自启动、后台活动，并取消此应用的电池限制。菜单随系统版本变化。强行停止应用后需重新打开；重启后需先解锁。\n\n4. 系统可能延迟或阻止第三方读取验证码。HarmonyOS 5 及以上暂不承诺支持。请用实际短信验证，能安装不等于能转发。\n\n5. 所有匹配短信均原文转发，多个目标分别计费，长短信可能按多条收费。请勿把目标填成 A 自己的号码。\n\n6. 为方便查看详情，本工具在本机私有存储保留最近 200 条记录的正文（可能含验证码），不联网、不云备份。系统短信应用及运营商也可能保留发件记录。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("电池设置") { _, _ -> openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .show()
    }

    private fun openSettings(intent: Intent) { try { startActivity(intent) } catch (_: RuntimeException) { toast("无法自动打开，请到系统设置手动查找") } }
    private fun hasPermissions() = PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun selectedSimLabel() = sim.selectedItem?.toString().orEmpty()
    private fun setToggle(value: Boolean) { updating = true; toggle.isChecked = value; updating = false; refreshStatus() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadSims(-1)
        val saved = store.config().subscriptionId
        subscriptions.indexOfFirst { it.subscriptionId == saved }.takeIf { it >= 0 }?.let { sim.setSelection(it) }
        refreshStatus()
        if (!hasPermissions()) toast("权限未全部授予；若已选择不再询问，请打开应用设置")
    }
    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            val preferred = subscriptions.getOrNull(sim.selectedItemPosition)?.subscriptionId ?: store.config().subscriptionId
            loadSims(preferred)
            handler.post(refresh)
        }
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { store.close(); super.onDestroy() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun card(root: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(227, 233, 244)) }
        root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }
    private fun input(parent: LinearLayout, title: String, placeholder: String, viewId: Int): EditText {
        val field = EditText(this).apply {
            id = viewId; hint = placeholder; inputType = InputType.TYPE_CLASS_PHONE
            textSize = 17f; setSingleLine(); minHeight = dp(52); setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(245, 247, 252)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(219, 226, 240)) }
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        parent.addView(label(title, 14, bold = true).apply { labelFor = viewId })
        parent.addView(field)
        return field
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title; isAllCaps = false; minHeight = dp(50)
            val primary = title == "保存设置"
            setTextColor(if (primary) Color.WHITE else accent)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accent else Color.rgb(233, 239, 253))
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    companion object {
        private val PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
    }
}
