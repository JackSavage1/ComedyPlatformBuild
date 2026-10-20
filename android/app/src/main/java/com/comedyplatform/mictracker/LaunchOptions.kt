package com.comedyplatform.mictracker

// =============================================================================
// Launch options for automated screenshots (spec 8.3).
//
// CI starts the app with intent extras, e.g.
//     adb shell am start -n com.comedyplatform.mictracker/.MainActivity \
//         --ez demo true --es screen calendar-filters
// MainActivity reads them on a fresh start and hands them to AppContainer.
// A normal launch has no extras, so everything here stays off.
//
// Plain Kotlin (no Android imports) so it can be unit-tested on the JVM.
// =============================================================================

/** Every screen CI can open directly, by its `screen` extra (the spec 8.3 table, in order). */
enum class LaunchScreen(val key: String) {
    SIGN_IN("sign-in"),
    TONIGHT("tonight"),
    CALENDAR("calendar"),
    CALENDAR_FILTERS("calendar-filters"),
    MIC_DETAIL("mic-detail"),
    MAP("map"),
    SETS_LOG("sets-log"),
    SETS_HISTORY("sets-history"),
    SET_DETAIL("set-detail"),
    STATS("stats"),
    STATS_CHARTS("stats-charts"),
    MANAGE("manage"),
    IMPORT("import"),
    MIC_FORM("mic-form"),
    ;

    companion object {
        /** "calendar-filters" (any case, surrounding spaces ignored) → CALENDAR_FILTERS; unknown → null. */
        fun fromKey(raw: String?): LaunchScreen? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == key }
        }
    }
}

/**
 * What the launch intent asked for.
 *
 * @param demo open demo mode (no Supabase needed); alone it shows the Tonight tab
 * @param screen open this screen directly; null = the normal start
 */
data class LaunchOptions(val demo: Boolean = false, val screen: LaunchScreen? = null) {

    companion object {
        /** Intent extra names (spec 8.3). */
        const val EXTRA_DEMO = "demo"
        const val EXTRA_SCREEN = "screen"

        /** A normal launch. */
        val NONE = LaunchOptions()

        /**
         * Builds the options from the raw extras. [demoText] covers
         * `--es demo true` (a string instead of `--ez`'s boolean).
         */
        fun parse(demoFlag: Boolean, demoText: String?, screen: String?): LaunchOptions = LaunchOptions(
            demo = demoFlag || demoText?.trim()?.lowercase() in setOf("true", "1", "yes"),
            screen = LaunchScreen.fromKey(screen),
        )
    }
}

/**
 * Hands the launch's [screen] to the parts of the UI that act on it — each
 * part ONCE, so after the screenshot the app behaves normally (going back
 * to Manage later doesn't reopen Import, a second tabs screen after leaving
 * demo starts on Tonight, ...). Main thread only.
 *
 *   takeForStartTab()    MainScaffold: which bottom tab to start on
 *   takeForNavigation()  MainScaffold: a screen to open on top once data is loaded
 *   takeScreenState(…)   a screen's initial state (sheet open, tab, scroll position)
 */
class LaunchHooks(val screen: LaunchScreen?) {

    private enum class Use { START_TAB, NAVIGATION, SCREEN_STATE }

    private val used = mutableSetOf<Use>()

    /** True when the launch asked for the Sign in screen (shown even without Supabase settings). */
    val showsSignIn: Boolean get() = screen == LaunchScreen.SIGN_IN

    /** The launch's screen the first time it's asked; null afterwards (and on normal launches). */
    fun takeForStartTab(): LaunchScreen? = take(Use.START_TAB)

    /** Like [takeForStartTab], for the screen MainScaffold opens on top of the tabs. */
    fun takeForNavigation(): LaunchScreen? = take(Use.NAVIGATION)

    /**
     * The launch's screen when it is one of [targets] and no screen has taken
     * its initial state yet; null otherwise.
     */
    fun takeScreenState(vararg targets: LaunchScreen): LaunchScreen? =
        if (screen != null && screen in targets) take(Use.SCREEN_STATE) else null

    private fun take(use: Use): LaunchScreen? = if (screen != null && used.add(use)) screen else null

    companion object {
        /** No launch instructions (a normal launch). */
        val NONE = LaunchHooks(null)
    }
}
