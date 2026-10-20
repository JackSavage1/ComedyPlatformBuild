package com.comedyplatform.mictracker.ui.sets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.TagChips
import com.comedyplatform.mictracker.ui.components.TagSelector
import com.comedyplatform.mictracker.ui.stats.MetricTile
import kotlin.math.roundToInt

/**
 * The "History" tab (spec 6.6): summary numbers, filters, then every set
 * newest first. Tap a logged set for its details; tap a planned one to log it.
 */
@Composable
internal fun HistoryTab(
    state: SetsUiState,
    onMicFilter: (String?) -> Unit,
    onMinRating: (Int) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenRow: (SetWithMic) -> Unit,
) {
    if (state.allSets.isEmpty()) {
        // Scrollable so pull-to-refresh still works on the empty screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            EmptyState(
                title = "No sets logged yet!",
                message = "Hit a mic tonight and come back to log it. " +
                    "Your first data point is always the hardest. 🎤",
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        val summary = state.summary
        if (summary != null) {
            item(key = "summary") { SummaryGrid(summary) }
        }

        item(key = "filters") {
            HistoryFilters(
                state = state,
                onMicFilter = onMicFilter,
                onMinRating = onMinRating,
                onToggleTag = onToggleTag,
                onClearFilters = onClearFilters,
            )
        }

        item(key = "count") {
            Text(
                text = SetsRules.showingText(state.filteredSets.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.filteredSets.isEmpty()) {
            item(key = "no-match") {
                EmptyState(
                    message = "No sets match these filters.",
                    action = { TextButton(onClick = onClearFilters) { Text("Clear filters") } },
                )
            }
        }

        items(state.filteredSets, key = { item -> item.set.id }) { item ->
            HistoryRow(item = item, onClick = { onOpenRow(item) })
            HorizontalDivider()
        }
    }
}

/** Total Sets · Avg Set Rating · Most Visited · Week Streak (2 × 2). */
@Composable
private fun SummaryGrid(summary: SetsRules.HistorySummary) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(label = "Total Sets", value = summary.totalSets, modifier = Modifier.weight(1f))
            MetricTile(label = "Avg Set Rating", value = summary.averageRating, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(label = "Most Visited", value = summary.mostVisited, modifier = Modifier.weight(1f))
            MetricTile(label = "Week Streak", value = summary.weekStreak, modifier = Modifier.weight(1f))
        }
    }
}

/** Mic (All + names present) · Min set rating · Tags (any of). */
@Composable
private fun HistoryFilters(
    state: SetsUiState,
    onMicFilter: (String?) -> Unit,
    onMinRating: (Int) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    val anyFilter = state.micFilter != null || state.minRating > 1 || state.tagFilter.isNotEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Filters",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (anyFilter) {
                    TextButton(onClick = onClearFilters) { Text("Clear") }
                }
            }

            // ---- Mic --------------------------------------------------------------
            MicFilterMenu(
                selected = state.micFilter,
                options = state.micFilterOptions,
                onSelect = onMicFilter,
            )

            // ---- Min set rating ----------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Min set rating",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.minRating}/10",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = state.minRating.toFloat(),
                onValueChange = { raw -> onMinRating(raw.roundToInt()) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text(
                text = "Planned sets are always shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Tags ------------------------------------------------------------------
            if (state.tagFilterOptions.isNotEmpty()) {
                Text(text = "Tags (any of)", style = MaterialTheme.typography.bodyMedium)
                TagSelector(
                    selected = state.tagFilter,
                    onToggle = onToggleTag,
                    options = state.tagFilterOptions,
                )
            }
        }
    }
}

/** "Filter by mic" as a button that opens a menu: All, then each mic name. */
@Composable
private fun MicFilterMenu(selected: String?, options: List<String>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Mic: " + (selected ?: "All"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            options.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(name)
                    },
                )
            }
        }
    }
}

/**
 * 🔥 **10/20/2026** — Mic @ Venue
 * Set: 8/10 · Crowd: 6/10   (or "PLANNED — tap to log details")
 * [tag pills]
 */
@Composable
private fun HistoryRow(item: SetWithMic, onClick: () -> Unit) {
    val set = item.set
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                append(set.ratingEmoji)
                append(" ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(set.dateText) }
                append(" — ")
                append(item.micAndVenue)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = set.summaryText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (set.isPlanned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TagChips(tags = set.tagList)
    }
}
