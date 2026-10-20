package com.comedyplatform.mictracker

import android.app.Application
import org.maplibre.android.MapLibre

/**
 * The Application object — created once, before any screen.
 *
 * It builds the [AppContainer] (our hand-rolled dependency injection) and
 * initializes MapLibre, which must happen before any MapView is created.
 */
class MicTrackerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre needs its singleton set up before a MapView is inflated.
        // No API key: we use free Carto styles (spec 6.5).
        MapLibre.getInstance(this)
        container = AppContainer(this)
    }
}
