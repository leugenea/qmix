package com.qmix.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStateTest {
    @Test
    fun activate_transitions_startup_to_activated() {
        val initial = StartupState()

        assertFalse(initial.isActivated)
        assertTrue(initial.activate().isActivated)
    }
}
