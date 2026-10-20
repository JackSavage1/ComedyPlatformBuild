package com.comedyplatform.mictracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.comedyplatform.mictracker.ui.navigation.MicTrackerRoot
import com.comedyplatform.mictracker.ui.theme.MicTrackerTheme

/**
 * The app's only Activity. Everything on screen is Jetpack Compose; moving
 * between screens is handled by Navigation Compose inside [MicTrackerRoot].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spec 8.3: CI screenshot launches pass `demo` / `screen` extras.
        // Only on a fresh start — after a rotation the screens restore themselves.
        if (savedInstanceState == null) {
            val container = (application as MicTrackerApplication).container
            container.applyLaunchOptions(launchOptions(intent))
        }
        // Draw behind the status/navigation bars (required look on Android 15);
        // the root scaffold adds the matching padding.
        enableEdgeToEdge()
        setContent {
            MicTrackerTheme {
                MicTrackerRoot()
            }
        }
    }

    /** Reads the automation extras; a normal launch (no extras) gives [LaunchOptions.NONE]. */
    private fun launchOptions(intent: Intent?): LaunchOptions {
        if (intent?.extras == null) return LaunchOptions.NONE
        return LaunchOptions.parse(
            // `--ez demo true` arrives as a boolean, `--es demo true` as text;
            // reading the other type just gives the default (false / null).
            demoFlag = intent.getBooleanExtra(LaunchOptions.EXTRA_DEMO, false),
            demoText = intent.getStringExtra(LaunchOptions.EXTRA_DEMO),
            screen = intent.getStringExtra(LaunchOptions.EXTRA_SCREEN),
        )
    }
}
