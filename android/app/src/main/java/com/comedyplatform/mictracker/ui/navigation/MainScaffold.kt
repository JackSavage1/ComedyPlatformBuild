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
import androidx.compose.runtime.getValue
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
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.ui.calendar.CalendarScreen
import com.comedyplatform.mictracker.ui.detail.MicDetailScreen
import com.comedyplatform.mictracker.ui.manage.ManageScreen
import com.comedyplatform.mictracker.ui.map.MapScreen
import com.comedyplatform.mictracker.ui.micform.MicFormScreen
import com.comedyplatform.mictracker.ui.sets.SetsScreen
import com.comedyplatform.mictracker.ui.stats.StatsScreen
import com.comedyplatform.mictracker.ui.tonight.TonightScreen
import java.time.LocalDate

/**
 * The signed-in app: a bottom NavigationBar with the 5 tabs, and a NavHost
 * with the tabs plus Mic Detail, Mic Form and Manage (which hide the bar).
 *
 * Insets: this Scaffold pads its content for the bottom bar / system bars and
 * *consumes* those insets, so screens may use their own Scaffold + TopAppBar
 * without getting the status-bar padding twice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold() {
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
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
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
private fun MicTrackerNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val openMic: (Int, LocalDate?) -> Unit = { micId, date ->
        navController.navigate(Routes.micDetail(micId, date))
    }
    val openManage: () -> Unit = { navController.navigate(Routes.MANAGE) { launchSingleTop = true } }

    NavHost(
        navController = navController,
        startDestination = Routes.TONIGHT,
        modifier = modifier,
    ) {
        composable(Routes.TONIGHT) {
            TonightScreen(onOpenMic = openMic, onOpenManage = openManage)
        }
        composable(Routes.CALENDAR) {
            CalendarScreen(onOpenMic = openMic, onOpenManage = openManage)
        }
        composable(Routes.MAP) {
            MapScreen(onOpenMic = openMic)
        }
        composable(Routes.SETS) {
            SetsScreen()
        }
        composable(Routes.STATS) {
            StatsScreen()
        }
        composable(Routes.MANAGE) {
            ManageScreen(
                onOpenMic = openMic,
                onAddMic = { navController.navigate(Routes.micForm(null)) },
                onBack = { navController.popIfPossible() },
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
