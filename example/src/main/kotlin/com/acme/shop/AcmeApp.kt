package com.acme.shop

import android.app.Application
import link.lander.android.Lander

class AcmeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Returns immediately: the launch report runs in the background.
        Lander.initialize(
            this,
            apiKey = BuildConfig.LANDER_API_KEY,
            baseUrl = BuildConfig.LANDER_BASE_URL,
            enableLogging = BuildConfig.DEBUG,
        )
    }
}
