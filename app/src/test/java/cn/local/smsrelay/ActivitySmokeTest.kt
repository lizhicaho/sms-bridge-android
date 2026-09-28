package cn.local.smsrelay

import android.Manifest
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivitySmokeTest {
    @Test fun productionWindowsRemainProtected() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.resources.getBoolean(R.bool.protect_screens))
        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        controller.pause().stop().destroy()
    }
    @Test fun appStartsPausedAndCannotEnableWithMissingSettings() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("relay_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("", activity.findViewById<EditText>(R.id.source_number).text.toString())
        val toggle = descendants(activity.window.decorView).filterIsInstance<Switch>().single { it.text == "启用自动转发" }
        toggle.isChecked = true
        assertFalse(toggle.isChecked)
        RelayStore(activity).use { assertFalse(it.config().enabled) }
        controller.pause().stop().destroy()
    }
    @Test fun allModeDisablesSourceInputWithoutErasingIt() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("relay_settings", Context.MODE_PRIVATE).edit().clear().putString("source", "106123").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val all = activity.findViewById<Switch>(R.id.forward_all)
        val source = activity.findViewById<EditText>(R.id.source_number)
        assertFalse(all.isChecked)
        all.isChecked = true
        assertFalse(source.isEnabled)
        assertEquals("106123", source.text.toString())
        RelayStore(activity).use { assertFalse(it.config().forwardAll) }
        all.isChecked = false
        assertTrue(source.isEnabled)
        controller.pause().stop().destroy()
    }

    @Test fun grantButtonWithAllPermissionsRefreshesWithoutRequestingAnEmptyPermissionSet() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val button = descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text == "授予必要权限" }
        button.performClick()
        assertEquals("权限已授予，SIM 列表已刷新，请选择并保存", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        controller.pause().stop().destroy()
    }
    @Test fun tappingRecordShowsFullOriginalTextWithoutSending() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("relay.db")
        val body = "完整短信\n验证码 123456，换行与🙂保留。".repeat(30)
        val id = RelayStore(app).use { it.claim("detail", 3, 5, false, body)!! }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val row = descendants(controller.get().window.decorView).firstOrNull { it.tag == "record:$id" }
        assertNotNull("发送记录应可点击", row)
        row!!.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val displayed = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>()
        assertTrue(displayed.any { it.text.toString() == body })
        RelayStore(app).use { assertEquals(1, it.records().size) }
        controller.pause().stop().destroy()
    }
    @Test fun sourceAndDestinationFieldsSupportMultipleLines() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val source = activity.findViewById<EditText>(R.id.source_number)
        val destination = activity.findViewById<EditText>(R.id.destination_number)
        assertTrue(source.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        source.setText("106123456\n+8613900139000")
        assertEquals("106123456\n+8613900139000", source.text.toString())
        assertTrue(destination.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        controller.pause().stop().destroy()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
