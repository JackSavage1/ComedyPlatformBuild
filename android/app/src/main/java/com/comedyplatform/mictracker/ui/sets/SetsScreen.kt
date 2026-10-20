package com.comedyplatform.mictracker.ui.sets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * My Sets (spec 6.6) — a port of the web app's My Sets page, with two tabs:
 *
 *   Log a Set   planned sets ready to log + the full log form
 *   History     summary numbers, filters, and every set (tap for details)
 *
 * All data comes from the shared MicRepository via [SetsViewModel].
 *
 * @param startOnHistory open on the History tab (CI's `sets-history` /
 *   `set-detail` screenshots, spec 8.3)
 * @param openLatestSet also open Set Detail of the most recent logged set
 *   once the sets have loaded (`set-detail`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetsScreen(startOnHistory: Boolean = false, openLatestSet: Boolean = false) {
    val viewModel = appViewModel { container ->
        SetsViewModel(repository = container.micRepository, today = { container.today() })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    // Initial state for CI screenshots (spec 8.3); both flags are false on a normal launch.
    LaunchedEffect(Unit) {
        if (startOnHistory) viewModel.selectTab(SetsTab.HISTORY)
        if (openLatestSet) {
            // Sets are newest first, so the first logged one is the most recent.
            val latest = viewModel.state.mapNotNull { s -> s.allSets.firstOrNull { it.set.isLogged } }.first()
            viewModel.openHistoryRow(latest)
        }
    }

    // One-off messages ("✅ Set logged! Keep grinding! 🎤"), with a little
    // buzz when a set was logged — the phone's version of st.balloons().
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        if (message.celebrate) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        snackbarHostState.showSnackbar(message.text)
        viewModel.messageShown()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("🎤 My Sets") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                SetsTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }

            ErrorBanner(message = state.error, onDismiss = viewModel::dismissError)

            RefreshableBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    !state.hasLoaded && state.error == null -> LoadingBox(message = "Loading your sets…")

                    // The very first load failed: offer a retry (pull-to-refresh also works).
                    !state.hasLoaded -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        EmptyState(
                            title = "Couldn't load your sets",
                            message = "Check your connection, then pull down or tap Retry.",
                            action = { Button(onClick = viewModel::refresh) { Text("Retry") } },
                        )
                    }

                    state.tab == SetsTab.LOG -> LogSetTab(
                        state = state,
                        onOpenPlanned = viewModel::startFromPlanned,
                        onRemovePlanned = viewModel::removePlanned,
                        onFormChange = viewModel::updateForm,
                        onToggleTag = viewModel::toggleTag,
                        onDateChange = viewModel::setDate,
                        onClearForm = viewModel::clearForm,
                        onSave = viewModel::save,
                    )

                    else -> HistoryTab(
                        state = state,
                        onMicFilter = viewModel::setMicFilter,
                        onMinRating = viewModel::setMinRating,
                        onToggleTag = viewModel::toggleTagFilter,
                        onClearFilters = viewModel::clearHistoryFilters,
                        onOpenRow = viewModel::openHistoryRow,
                    )
                }
            }
        }
    }

    // Set Detail (tap on a logged set in History).
    val detail = state.detail
    if (detail != null) {
        SetDetailSheet(
            item = detail,
            isDeleting = state.isDeleting,
            onEdit = { viewModel.startEdit(detail) },
            onDelete = { viewModel.deleteSet(detail.set.id) },
            onDismiss = viewModel::closeDetail,
        )
    }
}
