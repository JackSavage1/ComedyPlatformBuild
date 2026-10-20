package com.comedyplatform.mictracker

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
        // Draw behind the status/navigation bars (required look on Android 15);
        // the root scaffold adds the matching padding.
        enableEdgeToEdge()
        setContent {
            MicTrackerTheme {
                MicTrackerRoot()
            }
        }
    }
}
