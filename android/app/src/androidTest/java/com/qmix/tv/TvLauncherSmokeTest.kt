package com.qmix.tv

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvLauncherSmokeTest {
    @Test
    fun tv_launcher_opens_with_focused_action_that_activates_on_ok() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val launchIntent = targetContext.packageManager
            .getLeanbackLaunchIntentForPackage(targetContext.packageName)

        assertNotNull("TV launcher activity is required", launchIntent)
        targetContext.startActivity(
            launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        assertTrue(
            "startup action did not appear",
            device.wait(Until.hasObject(By.text("Start hosting")), 30_000),
        )
        assertTrue(
            "startup action must own initial D-pad focus",
            device.wait(
                Until.hasObject(
                    By.pkg(targetContext.packageName)
                        .clazz("android.widget.Button")
                        .focused(true),
                ),
                10_000,
            ),
        )

        assertTrue("D-pad center key was not accepted", device.pressDPadCenter())
        assertTrue(
            "startup action was not activated",
            device.wait(Until.hasObject(By.text("Ready to host")), 10_000),
        )
    }
}
