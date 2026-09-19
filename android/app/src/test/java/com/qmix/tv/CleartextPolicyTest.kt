package com.qmix.tv

import android.content.pm.ApplicationInfo
import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleartextPolicyTest {
    @Test
    fun dynamically_configured_lan_http_is_permitted_by_android_policy() {
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()

        assertTrue(
            "The merged manifest must opt into cleartext for runtime-selected hosts",
            application.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        )
        assertTrue(
            "Android policy must permit a user-selected LAN host; URL validation and the warning bound its use",
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("192.168.1.20"),
        )
    }
}
