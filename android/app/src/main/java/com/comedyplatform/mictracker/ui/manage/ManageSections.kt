package com.comedyplatform.mictracker.ui.manage

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.ui.components.Callout
import com.comedyplatform.mictracker.ui.components.CalloutTone
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import com.comedyplatform.mictracker.ui.components.rememberLinkOpener

// =============================================================================
// The Manage sub-pages (spec 6.8). Each one is a scrolling list so the
// screen's pull-to-refresh works everywhere.
// =============================================================================

/** A short grey caption line. */
@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

// -----------------------------------------------------------------------------
// Import from Scrapers
// -----------------------------------------------------------------------------

/**
 * Pending scraped mics grouped by source, each with Add / Dismiss, plus
 * "Add all" per source.
 */
@Composable
internal fun ImportSection(
    state: ManageUiState,
    onAdd: (ScrapedMic) -> Unit,
    onDismiss: (ScrapedMic) -> Unit,
    onAddAll: (ManageRules.SourceGroup) -> Unit,
) {
    if (!state.reviewLoaded && state.isLoadingReview) {
        LoadingBox(message = "Loading new mics…")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "caption") {
            Caption(
                text = "Scrapers run nightly on GitHub Actions. Duplicates are filtered automatically.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (state.scrapedGroups.isEmpty()) {
            item(key = "empty") {
                if (state.reviewFailed) {
                    EmptyState(title = "Couldn't load new mics", message = "Pull down to try again.")
                } else {
                    EmptyState(title = "You're up to date.", message = "New mics from the scrapers will show up here for review.")
                }
            }
        }

        state.scrapedGroups.forEach { group ->
            item(key = "source-${group.key}") {
                SourceHeader(group = group, state = state, onAddAll = { onAddAll(group) })
            }
            items(group.mics, key = { mic -> "scraped-${mic.id}" }) { mic ->
                ScrapedMicCard(
                    mic = mic,
                    isBusy = mic.id in state.busyScrapedIds,
                    enabled = !state.isImporting,
                    onAdd = { onAdd(mic) },
                    onDismiss = { onDismiss(mic) },
                )
            }
        }
    }
}

/** "Bad Slava (12)" with an "Add all" button (or its progress). */
@Composable
private fun SourceHeader(group: ManageRules.SourceGroup, state: ManageUiState, onAddAll: () -> Unit) {
    val progress = state.addAll?.takeIf { it.sourceKey == group.key }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${group.title} (${group.mics.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (progress != null) {
            SmallSpinner()
            Spacer(Modifier.width(8.dp))
            Text("Adding ${progress.done + 1} of ${progress.total}…", style = MaterialTheme.typography.labelMedium)
        } else {
            TextButton(onClick = onAddAll, enabled = !state.isImporting) { Text("Add all") }
        }
    }
}

/** One pending mic: "Monday — Name @ Venue", address, time · cost, sign-up link, Add / Dismiss. */
@Composable
private fun ScrapedMicCard(
    mic: ScrapedMic,
    isBusy: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val links = rememberLinkOpener()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = mic.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val address = TextRules.trimmedOrNull(mic.address)
            if (address != null) Caption("📍 $address")
            val details = ManageRules.scrapedDetails(mic)
            if (details.isNotEmpty()) Caption("🕖 $details")

            val link = mic.signupLink
            val rawSignup = TextRules.trimmedOrNull(mic.signupUrl)
            if (link != null) {
                Text(
                    text = "📝 $link",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { links.openUrl(link) },
                )
            } else if (rawSignup != null) {
                Caption("📝 $rawSignup")
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onAdd, enabled = enabled) { Text("Add") }
                OutlinedButton(onClick = onDismiss, enabled = enabled) { Text("Dismiss") }
                if (isBusy) SmallSpinner()
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Scrape History
// -----------------------------------------------------------------------------

/** scrape_log, newest first: source, time, status, notes. */
@Composable
internal fun ScrapeHistorySection(state: ManageUiState) {
    if (!state.reviewLoaded && state.isLoadingReview) {
        LoadingBox(message = "Loading scrape history…")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.scrapeLog.isEmpty()) {
            item(key = "empty") { EmptyState(message = "No scrapes logged yet.") }
        }
        items(state.scrapeLog, key = { entry -> entry.id }) { entry ->
            ScrapeLogRow(entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ScrapeLogRow(entry: ScrapeLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.sourceDisplayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            val status = TextRules.trimmedOrNull(entry.status)
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.lowercase().contains("error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Caption("🕒 ${entry.lastScrapedText}")
        val notes = TextRules.trimmedOrNull(entry.notes)
        if (notes != null) Caption(notes)
    }
}

// -----------------------------------------------------------------------------
// Manage Mics
// -----------------------------------------------------------------------------

/** Searchable list of active mics ("Mon — Name @ Venue") → Mic Detail without a date. */
@Composable
internal fun ManageMicsSection(
    state: ManageUiState,
    onQueryChange: (String) -> Unit,
    onOpenMic: (micId: Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "search") {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search name, venue, neighborhood, day…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "count") {
            Caption(
                text = "Showing ${state.searchResults.size} of ${state.activeMics.size} active mics",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.searchResults.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    message = if (state.activeMics.isEmpty()) "No active mics yet." else "No mics match \"${state.searchQuery}\".",
                )
            }
        }
        items(state.searchResults, key = { mic -> mic.id }) { mic ->
            ManageMicRow(mic = mic, onClick = { onOpenMic(mic.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ManageMicRow(mic: Mic, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = ManageRules.manageLabel(mic),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val location = listOf(mic.timeText, mic.locationText).filter { it.isNotEmpty() }.joinToString(" · ")
        if (location.isNotEmpty()) Caption(location)
    }
}

// -----------------------------------------------------------------------------
// Geocoding
// -----------------------------------------------------------------------------

/**
 * "Mics with coordinates X / Y", the "Geocode with device" button with
 * progress, and the active mics that still have no map pin.
 */
@Composable
internal fun GeocodingSection(
    state: ManageUiState,
    onGeocode: () -> Unit,
    onStop: () -> Unit,
    onOpenMic: (micId: Int) -> Unit,
) {
    val progress = state.geocode
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "summary") {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = state.coordinatesSummary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Caption(
                    "Map pins come from each mic's address. The phone's geocoder looks up one address per " +
                        "second and only accepts results inside NYC.",
                )

                if (!state.geocoderAvailable) {
                    Callout(
                        text = "This phone has no geocoding service, so addresses can't be looked up here. " +
                            "Add coordinates from the web app instead.",
                        tone = CalloutTone.WARNING,
                    )
                }

                if (progress.isRunning) {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Geocoding ${(progress.done + 1).coerceAtMost(progress.total)} of ${progress.total}" +
                            (progress.currentName?.let { ": $it" } ?: "") + "…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(
                        onClick = onGeocode,
                        enabled = state.geocoderAvailable && state.geocodableCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("📍 Geocode with device (${state.geocodableCount})")
                    }
                }

                val result = progress.resultText
                if (result != null && !progress.isRunning) {
                    Callout(text = result, tone = CalloutTone.SUCCESS)
                }

                val noAddress = state.missingCoordinates.size - state.geocodableCount
                if (noAddress > 0) {
                    Caption(
                        "$noAddress ${if (noAddress == 1) "mic has" else "mics have"} no address — " +
                            "open it and use Edit mic to add one.",
                    )
                }
            }
        }

        if (state.missingCoordinates.isEmpty()) {
            item(key = "all-set") {
                EmptyState(message = "Every active mic has coordinates. 🗺️")
            }
        } else {
            item(key = "missing-header") {
                Text(
                    text = "Missing coordinates (${state.missingCoordinates.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.missingCoordinates, key = { mic -> mic.id }) { mic ->
                MissingCoordinatesRow(mic = mic, onClick = { onOpenMic(mic.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MissingCoordinatesRow(mic: Mic, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = if (mic.venueName.isEmpty()) mic.displayName else "${mic.displayName} @ ${mic.venueName}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Caption("📍 " + (TextRules.trimmedOrNull(mic.address) ?: "No address"))
        }
    }
}
