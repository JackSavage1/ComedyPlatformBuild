package com.comedyplatform.mictracker.ui.navigation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.comedyplatform.mictracker.AppContainer
import com.comedyplatform.mictracker.LaunchHooks
import com.comedyplatform.mictracker.LaunchScreen
import com.comedyplatform.mictracker.appContainer
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.ui.calendar.CalendarScreen
import com.comedyplatform.mictracker.ui.detail.MicDetailScreen
import com.comedyplatform.mictracker.ui.manage.ManageScreen
import com.comedyplatform.mictracker.ui.manage.ManageSection
import com.comedyplatform.mictracker.ui.map.MapScreen
import com.comedyplatform.mictracker.ui.micform.MicFormScreen
import com.comedyplatform.mictracker.ui.sets.SetsScreen
import com.comedyplatform.mictracker.ui.stats.StatsScreen
import com.comedyplatform.mictracker.ui.tonight.TonightScreen
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * The app behind the gate (signed in, or demo mode): a bottom NavigationBar
 * with the 5 tabs, and a NavHost with the tabs plus Mic Detail, Mic Form and
 * Manage (which hide the bar).
 *
 * Insets: this Scaffold pads its content for the bottom bar / system bars and
 * *consumes* those insets, so screens may use their own Scaffold + TopAppBar
 * without getting the status-bar padding twice.
 *
 * CI screenshots (spec 8.3): when the launch asked for a screen, the NavHost
 * starts on that screen's tab, opens a screen on top once the data has
 * loaded (Mic Detail, Manage, Mic Form), and hands screens their initial
 * state (see [LaunchHooks]). A normal launch starts on Tonight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold() {
    val container = appContainer()
    val hooks = container.launchHooks
    // Saved so a rotation keeps the same start tab (the hook is only handed out once).
    val startRoute = rememberSaveable { startRouteFor(hooks.takeForStartTab()) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = currentDestination == null || currentDestination.route in TopLevelTab.routes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelTab.entries.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        MicTrackerNavHost(
            navController = navController,
            startRoute = startRoute,
            hooks = hooks,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }

    // After the NavHost exists: open the launch's screen on top, if any.
    LaunchedEffect(navController) {
        openLaunchScreen(hooks.takeForNavigation(), navController, container)
    }
}

/** The tab a launch's screen lives on (spec 8.3); Tonight for everything else and normal launches. */
private fun startRouteFor(screen: LaunchScreen?): String = when (screen) {
    LaunchScreen.CALENDAR, LaunchScreen.CALENDAR_FILTERS -> Routes.CALENDAR
    LaunchScreen.MAP -> Routes.MAP
    LaunchScreen.SETS_LOG, LaunchScreen.SETS_HISTORY, LaunchScreen.SET_DETAIL -> Routes.SETS
    LaunchScreen.STATS, LaunchScreen.STATS_CHARTS -> Routes.STATS
    null, LaunchScreen.SIGN_IN, LaunchScreen.TONIGHT, LaunchScreen.MIC_DETAIL,
    LaunchScreen.MANAGE, LaunchScreen.IMPORT, LaunchScreen.MIC_FORM,
    -> Routes.TONIGHT
}

/** The mic `screen=mic-detail` opens (spec 8.3). */
private const val LAUNCH_MIC_ID = 4

/** Opens the screens that sit on top of a tab (spec 8.3). Does nothing for the others. */
private suspend fun openLaunchScreen(screen: LaunchScreen?, navController: NavHostController, container: AppContainer) {
    when (screen) {
        LaunchScreen.MIC_DETAIL -> {
            // "Date = its next occurrence" needs the mic's weekday, so wait for the first load.
            val repository = container.micRepository
            repository.hasLoaded.first { it }
            val date = repository.mic(LAUNCH_MIC_ID)?.nextOccurrence(container.today())
            navController.navigate(Routes.micDetail(LAUNCH_MIC_ID, date))
        }
        // Import is a page inside Manage; ManageScreen opens it (initialSection).
        LaunchScreen.MANAGE, LaunchScreen.IMPORT -> navController.navigate(Routes.MANAGE)
        LaunchScreen.MIC_FORM -> navController.navigate(Routes.micForm(null))
        else -> Unit
    }
}

/** Switch tabs the standard way: one copy of each tab, state saved and restored. */
private fun NavHostController.navigateToTab(tab: TopLevelTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pops the current screen unless it's the last one (guards against double-taps). */
private fun NavHostController.popIfPossible() {
    if (previousBackStackEntry != null) popBackStack()
}

@Composable
private fun MicTrackerNavHost(
    navController: NavHostController,
    startRoute: String,
    hooks: LaunchHooks,
    modifier: Modifier = Modifier,
) {
    val openMic: (Int, LocalDate?) -> Unit = { micId, date ->
        navController.navigate(Routes.micDetail(micId, date))
    }
    val openManage: () -> Unit = { navController.navigate(Routes.MANAGE) { launchSingleTop = true } }

    // Each `remember { hooks.takeScreenState(...) }` below answers true only for
    // the screen a CI launch asked for, and only the first time (spec 8.3).
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(Routes.TONIGHT) {
            TonightScreen(onOpenMic = openMic, onOpenManage = openManage)
        }
        composable(Routes.CALENDAR) {
            val openFilters = remember { hooks.takeScreenState(LaunchScreen.CALENDAR_FILTERS) != null }
            CalendarScreen(onOpenMic = openMic, onOpenManage = openManage, initialFiltersOpen = openFilters)
        }
        composable(Routes.MAP) {
            MapScreen(onOpenMic = openMic)
        }
        composable(Routes.SETS) {
            val launch = remember { hooks.takeScreenState(LaunchScreen.SETS_HISTORY, LaunchScreen.SET_DETAIL) }
            SetsScreen(startOnHistory = launch != null, openLatestSet = launch == LaunchScreen.SET_DETAIL)
        }
        composable(Routes.STATS) {
            val scrollToCharts = remember { hooks.takeScreenState(LaunchScreen.STATS_CHARTS) != null }
            StatsScreen(scrollToCharts = scrollToCharts)
        }
        composable(Routes.MANAGE) {
            val openImport = remember { hooks.takeScreenState(LaunchScreen.IMPORT) != null }
            ManageScreen(
                onOpenMic = openMic,
                onAddMic = { navController.navigate(Routes.micForm(null)) },
                onBack = { navController.popIfPossible() },
                initialSection = if (openImport) ManageSection.IMPORT else null,
            )
        }
        composable(
            route = Routes.MIC_DETAIL,
            arguments = listOf(
                navArgument(Routes.ARG_MIC_ID) { type = NavType.IntType },
                navArgument(Routes.ARG_DATE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val micId = entry.arguments?.getInt(Routes.ARG_MIC_ID) ?: -1
            val date = JsonValues.parseDate(entry.arguments?.getString(Routes.ARG_DATE))
            MicDetailScreen(
                micId = micId,
                date = date,
                onBack = { navController.popIfPossible() },
                onEdit = { id -> navController.navigate(Routes.micForm(id)) },
            )
        }
        composable(
            route = Routes.MIC_FORM,
            arguments = listOf(
                navArgument(Routes.ARG_EDIT_MIC_ID) {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
        ) { entry ->
            val editId = entry.arguments?.getInt(Routes.ARG_EDIT_MIC_ID) ?: -1
            MicFormScreen(
                editMicId = editId.takeIf { it >= 0 },
                onDone = { navController.popIfPossible() },
            )
        }
    }
}
