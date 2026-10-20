package com.comedyplatform.mictracker.ui.tonight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.MicRow
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import com.comedyplatform.mictracker.ui.components.SectionHeader
import com.comedyplatform.mictracker.ui.components.UrgencyDot
import java.time.LocalDate

/**
 * Tonight (spec 6.2) — the home tab, a port of the web app's `app.py`:
 *
 *   - today's full date
 *   - "Tonight's Mics — Monday" with a tappable row per mic (→ Mic Detail, date = today)
 *   - "Coming Up Tomorrow — Tuesday" as compact rows (→ Mic Detail, date = tomorrow)
 *   - quick stats: Total Active Mics · Neighborhoods · Most Mics In
 *
 * The gear button opens Manage. Pull down to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonightScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit, onOpenManage: () -> Unit) {
    val viewModel = appViewModel { container ->
        TonightViewModel(repository = container.micRepository, today = { container.today() })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Each time the tab is shown, make sure "tonight" is still today's date.
    LaunchedEffect(Unit) { viewModel.syncToday() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎤 Mic Tracker") },
                actions = {
                    IconButton(onClick = onOpenManage) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Manage")
                    }
                },
            )
        },
    ) { padding ->
        RefreshableBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.hasLoaded && state.error == null) {
                LoadingBox(message = "Loading mics…")
            } else {
                TonightContent(
                    state = state,
                    onOpenMic = onOpenMic,
                    onDismissError = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun TonightContent(
    state: TonightUiState,
    onOpenMic: (micId: Int, date: LocalDate?) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "error") {
            ErrorBanner(message = state.error, onDismiss = onDismissError)
        }

        if (!state.hasLoaded) {
            // The very first load failed: nothing to show yet.
            item(key = "retry") {
                EmptyState(
                    title = "Couldn't load the mics",
                    message = "Check your connection, then pull down or tap Retry.",
                    action = { Button(onClick = onRetry) { Text("Retry") } },
                )
            }
            return@LazyColumn
        }

        // ---- Header: "📆 Monday, October 20, 2026" ---------------------------
        item(key = "date") {
            Text(
                text = "📆 ${DateRules.fullDate(state.today)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }

        // ---- Tonight -----------------------------------------------------------
        item(key = "tonight-header") {
            SectionHeader(
                title = "🌙 Tonight's Mics — ${state.todayName}",
                subtitle = if (state.tonightMics.isEmpty()) null else TonightRules.countText(state.tonightMics.size, "tonight"),
            )
        }
        if (state.tonightMics.isEmpty()) {
            item(key = "tonight-empty") {
                EmptyState(message = "No open mics listed for ${state.todayName}. Rest day! 😴")
            }
        } else {
            items(items = state.tonightMics, key = { mic -> "tonight-${mic.id}" }) { mic ->
                Column {
                    MicRow(mic = mic, onClick = { onOpenMic(mic.id, state.today) })
                    HorizontalDivider(modifier = Modifier.padding(start = 38.dp))
                }
            }
        }

        // ---- Tomorrow ------------------------------------------------------------
        item(key = "tomorrow-header") {
            SectionHeader(
                title = "📅 Coming Up Tomorrow — ${state.tomorrowName}",
                subtitle = if (state.tomorrowMics.isEmpty()) null else TonightRules.countText(state.tomorrowMics.size, ""),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (state.tomorrowMics.isEmpty()) {
            item(key = "tomorrow-empty") {
                EmptyState(message = "No open mics listed for ${state.tomorrowName}.")
            }
        } else {
            items(items = state.tomorrowMics, key = { mic -> "tomorrow-${mic.id}" }) { mic ->
                CompactMicRow(mic = mic, onClick = { onOpenMic(mic.id, state.tomorrow) })
            }
        }

        // ---- Quick stats -----------------------------------------------------------
        item(key = "stats") {
            QuickStats(
                state = state,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
            )
        }
    }
}

/**
 * One compact "tomorrow" line, like the web:
 * `🟢 7:00 PM — Name @ Venue (Neighborhood) · $5`
 */
@Composable
private fun CompactMicRow(mic: Mic, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UrgencyDot(mic.urgencyLevel)
        Spacer(Modifier.width(12.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(mic.timeText) }
                append(" — ")
                append(mic.displayName)
                if (mic.venueName.isNotEmpty()) append(" @ ${mic.venueName}")
                val neighborhood = TextRules.trimmedOrNull(mic.neighborhood)
                if (neighborhood != null) append(" ($neighborhood)")
                append(" · ${mic.costText}")
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The three quick-stat tiles at the bottom of the page. */
@Composable
private fun QuickStats(state: TonightUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(label = "Total Active Mics", value = state.totalActive.toString(), modifier = Modifier.weight(1f))
        StatTile(label = "Neighborhoods", value = state.neighborhoodCount.toString(), modifier = Modifier.weight(1f))
        StatTile(label = "Most Mics In", value = state.topBorough, modifier = Modifier.weight(1f))
    }
}

/** A small card: caption on top, big value underneath (like Streamlit's st.metric). */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
