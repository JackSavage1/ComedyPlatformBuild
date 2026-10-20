package com.comedyplatform.mictracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 8.3: the `demo` / `screen` launch extras used for CI screenshots. */
class LaunchOptionsTest {

    @Test
    fun everyScreenOfTheSpecTableParses() {
        val keys = listOf(
            "sign-in", "tonight", "calendar", "calendar-filters", "mic-detail", "map", "sets-log",
            "sets-history", "set-detail", "stats", "stats-charts", "manage", "import", "mic-form",
        )
        // Same names, same order as the table (CI numbers the PNGs in this order).
        assertEquals(keys, LaunchScreen.entries.map { it.key })
        for (key in keys) assertEquals(key, LaunchScreen.fromKey(key)?.key)
    }

    @Test
    fun screenNamesAreForgivingButUnknownOnesAreIgnored() {
        assertEquals(LaunchScreen.STATS_CHARTS, LaunchScreen.fromKey("  Stats-Charts "))
        assertNull(LaunchScreen.fromKey("settings"))
        assertNull(LaunchScreen.fromKey(""))
        assertNull(LaunchScreen.fromKey(null))
    }

    @Test
    fun demoComesFromTheBooleanOrTextExtra() {
        assertEquals(LaunchOptions(demo = true), LaunchOptions.parse(demoFlag = true, demoText = null, screen = null))
        assertTrue(LaunchOptions.parse(demoFlag = false, demoText = "TRUE", screen = null).demo)
        assertFalse(LaunchOptions.parse(demoFlag = false, demoText = "no", screen = null).demo)
        val both = LaunchOptions.parse(demoFlag = true, demoText = null, screen = "map")
        assertEquals(LaunchOptions(demo = true, screen = LaunchScreen.MAP), both)
    }

    @Test
    fun aNormalLaunchAsksForNothing() {
        assertEquals(LaunchOptions.NONE, LaunchOptions.parse(demoFlag = false, demoText = null, screen = null))
        val hooks = LaunchHooks(null)
        assertFalse(hooks.showsSignIn)
        assertNull(hooks.takeForStartTab())
        assertNull(hooks.takeForNavigation())
        assertNull(hooks.takeScreenState(*LaunchScreen.entries.toTypedArray()))
    }

    @Test
    fun eachUseIsHandedOutOnce() {
        val hooks = LaunchHooks(LaunchScreen.IMPORT)
        assertEquals(LaunchScreen.IMPORT, hooks.takeForStartTab())
        assertNull(hooks.takeForStartTab())
        assertEquals(LaunchScreen.IMPORT, hooks.takeForNavigation())
        assertNull(hooks.takeForNavigation())
        // Only the screen the launch named gets its initial state, and only once.
        assertNull(hooks.takeScreenState(LaunchScreen.CALENDAR_FILTERS))
        assertEquals(LaunchScreen.IMPORT, hooks.takeScreenState(LaunchScreen.IMPORT))
        assertNull(hooks.takeScreenState(LaunchScreen.IMPORT))
    }

    @Test
    fun signInIsAGateRuleNotAOneShot() {
        val hooks = LaunchHooks(LaunchScreen.SIGN_IN)
        assertTrue(hooks.showsSignIn)
        hooks.takeForStartTab()
        assertTrue(hooks.showsSignIn)
    }
}
