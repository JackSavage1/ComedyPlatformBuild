package com.comedyplatform.mictracker.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.core.model.PlanKey
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import java.time.LocalDate

/**
 * Calendar (spec 6.3) — a port of the web's Weekly Calendar page, laid out
 * for a phone: one week at a time, a Mon–Sun day strip, and the selected
 * day's mics as cards.
 *
 *   - ◀ / ▶ move between last week (−1) and 3 weeks out (+3); "Today" jumps back
 *   - day chips show how many (filtered) mics happen that day
 *   - the filter button (with a badge counting active filters) opens a sheet
 *   - each card: tap → Mic Detail for that date; swipe right = Going,
 *     swipe left = Cancelled; long-press for the same actions + Clear
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit, onOpenManage: () -> Unit) {
    val viewModel = appViewModel { container ->
        CalendarViewModel(repository = container.micRepository, today = { container.today() })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    // The app may have been open since yesterday.
    LaunchedEffect(Unit) { viewModel.syncToday() }

    // One-off confirmations ("✅ Going: …"). showSnackbar suspends until the
    // snackbar goes away; a newer message restarts this effect and replaces it.
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📅 Calendar") },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        val activeCount = state.filters.activeCount
                        BadgedBox(
                            badge = {
                                if (activeCount > 0) Badge { Text(activeCount.toString()) }
                            },
                        ) {
                            Icon(imageVector = Icons.Filled.FilterList, contentDescription = "Filters")
                        }
                    }
                    IconButton(onClick = onOpenManage) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Manage")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            WeekHeader(
                title = state.weekTitle,
                rangeText = state.weekRangeText,
                showTodayButton = state.weekOffset != 0,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                onPrevious = viewModel::previousWeek,
                onNext = viewModel::nextWeek,
                onToday = viewModel::goToToday,
            )
            DayStrip(
                days = state.days,
                selectedDate = state.selectedDate,
                onSelect = viewModel::selectDay,
            )
            Text(
                text = "Showing ${state.shownCount} of ${state.totalCount} mics",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            RefreshableBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (!state.hasLoaded && state.error == null) {
                    LoadingBox(message = "Loading mics…")
                } else {
                    DayList(
                        state = state,
                        onOpenMic = onOpenMic,
                        onRetry = viewModel::refresh,
                        onDismissError = viewModel::dismissError,
                        onGoing = { item -> viewModel.markGoing(item.mic, state.selectedDate) },
                        onCancelled = { item -> viewModel.markCancelled(item.mic, state.selectedDate) },
                        onClear = { item -> viewModel.clearPlan(item.mic, state.selectedDate) },
                    )
                }
            }
        }
    }

    if (showFilters) {
        CalendarFilterSheet(
            filters = state.filters,
            neighborhoodOptions = state.neighborhoodOptions,
            shownCount = state.shownCount,
            onDismiss = { showFilters = false },
            onToggleBorough = viewModel::toggleBorough,
            onToggleNeighborhood = viewModel::toggleNeighborhood,
            onCostChange = viewModel::setCostFilter,
            onSignupChange = viewModel::setSignupFilter,
            onOnlyUnvisitedChange = viewModel::setOnlyUnvisited,
            onOnlyGoingChange = viewModel::setOnlyGoing,
            onReset = viewModel::resetFilters,
        )
    }
}

/** The selected day's cards (or "No mics"). */
@Composable
private fun DayList(
    state: CalendarUiState,
    onOpenMic: (micId: Int, date: LocalDate?) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onGoing: (CalendarMicItem) -> Unit,
    onCancelled: (CalendarMicItem) -> Unit,
    onClear: (CalendarMicItem) -> Unit,
) {
    val date = state.selectedDate
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "error") {
            ErrorBanner(message = state.error, onDismiss = onDismissError)
        }

        if (!state.hasLoaded) {
            item(key = "retry") {
                EmptyState(
                    title = "Couldn't load the mics",
                    message = "Check your connection, then pull down or tap Retry.",
                    action = { Button(onClick = onRetry) { Text("Retry") } },
                )
            }
            return@LazyColumn
        }

        item(key = "day-title") {
            Text(
                text = "${DateRules.weekdayName(date)} ${DateRules.monthDay(date)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        if (state.dayMics.isEmpty()) {
            item(key = "empty") { EmptyState(message = "No mics") }
        } else {
            items(items = state.dayMics, key = { item -> "mic-${item.mic.id}-$date" }) { item ->
                SwipeableMicCard(
                    item = item,
                    busy = PlanKey(item.mic.id, date) in state.busyKeys,
                    onOpen = { onOpenMic(item.mic.id, date) },
                    onGoing = { onGoing(item) },
                    onCancelled = { onCancelled(item) },
                    onClear = { onClear(item) },
                )
            }
            item(key = "hint") {
                Text(
                    text = "Swipe right for Going, left for Cancelled · long-press for more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}
