package com.qmix.tv

import android.app.Application
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class QMixApplication : Application() {
    val hostSession: HostSessionController by lazy {
        val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        HostSessionController(
            httpClient = client,
            initialBackendUrl = BuildConfig.DEFAULT_BACKEND_URL,
            initialGuestOrigin = BuildConfig.DEFAULT_GUEST_ORIGIN,
        )
    }
}
