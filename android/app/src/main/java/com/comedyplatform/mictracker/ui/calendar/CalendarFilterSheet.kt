package com.comedyplatform.mictracker.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.CostFilter
import com.comedyplatform.mictracker.core.rules.MicFilters
import com.comedyplatform.mictracker.core.rules.SignupFilter
import kotlinx.coroutines.launch

/**
 * The Calendar's filter sheet (spec 6.3). Every change applies immediately;
 * "Show N mics" just closes the sheet.
 *
 *   Borough (multi) · Neighborhood (multi, limited to the chosen boroughs)
 *   Cost (single) · Signup Type (single)
 *   Only unvisited · Only going this week · Reset
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CalendarFilterSheet(
    filters: MicFilters,
    neighborhoodOptions: List<String>,
    shownCount: Int,
    onDismiss: () -> Unit,
    onToggleBorough: (String) -> Unit,
    onToggleNeighborhood: (String) -> Unit,
    onCostChange: (CostFilter) -> Unit,
    onSignupChange: (SignupFilter) -> Unit,
    onOnlyUnvisitedChange: (Boolean) -> Unit,
    onOnlyGoingChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Animate the sheet away, then tell the screen it's gone.
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔍 Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset, enabled = filters.isActive) { Text("Reset") }
            }

            // ---- Borough (multi) -------------------------------------------------
            SheetSectionTitle("Borough")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Boroughs.all.forEach { borough ->
                    FilterChip(
                        selected = borough in filters.boroughs,
                        onClick = { onToggleBorough(borough) },
                        label = { Text(borough) },
                    )
                }
            }

            // ---- Neighborhood (multi) ---------------------------------------------
            SheetSectionTitle(
                if (filters.boroughs.isEmpty()) "Neighborhood" else "Neighborhood (in selected boroughs)",
            )
            if (neighborhoodOptions.isEmpty()) {
                Text(
                    text = "No neighborhoods listed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    neighborhoodOptions.forEach { neighborhood ->
                        FilterChip(
                            selected = neighborhood in filters.neighborhoods,
                            onClick = { onToggleNeighborhood(neighborhood) },
                            label = { Text(neighborhood) },
                        )
                    }
                }
            }

            // ---- Cost (single) -------------------------------------------------------
            SheetSectionTitle("Cost")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CostFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filters.cost == option,
                        onClick = { onCostChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            // ---- Signup type (single) -------------------------------------------------
            SheetSectionTitle("Signup Type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignupFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filters.signup == option,
                        onClick = { onSignupChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            // ---- Toggles ---------------------------------------------------------------
            SwitchRow(
                label = "Only unvisited",
                caption = "Hide mics you've logged a set at",
                checked = filters.onlyUnvisited,
                onCheckedChange = onOnlyUnvisitedChange,
            )
            SwitchRow(
                label = "Only going this week",
                caption = "Mics you marked ✅ Going for a day this week",
                checked = filters.onlyGoing,
                onCheckedChange = onOnlyGoingChange,
            )

            Button(
                onClick = close,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (shownCount == 1) "Show 1 mic" else "Show $shownCount mics")
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, caption: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
