package com.qmix.tv

import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionLifecycleTest {
    @Test
    fun in_memory_session_survives_activity_recreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var before: HostSessionController
        scenario.onActivity { activity ->
            before = (activity.application as QMixApplication).hostSession
            before.updateSettings("https://api.example", "https://guest.example")
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            val after = (activity.application as QMixApplication).hostSession
            assertSame(before, after)
            assertEquals(
                HostingState.Setup("https://api.example", "https://guest.example"),
                after.state,
            )
        }
        scenario.close()
    }
}
