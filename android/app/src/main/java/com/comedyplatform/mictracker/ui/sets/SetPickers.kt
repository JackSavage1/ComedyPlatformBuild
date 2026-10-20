package com.comedyplatform.mictracker.ui.sets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.comedyplatform.mictracker.core.model.Mic
import java.time.LocalDate

/**
 * The searchable mic picker for the log form (spec 6.6): active mics grouped
 * by day Mon→Sun, each labelled "Mon — 7:00 PM — Name @ Venue".
 */
@Composable
internal fun MicPickerDialog(
    activeMics: List<Mic>,
    selectedMicId: Int?,
    onPick: (Mic) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(activeMics, query) { SetsRules.micPickerGroups(activeMics, query) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
        ) {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(
                    text = "Choose a mic",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search name, venue, day…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    if (groups.isEmpty()) {
                        item(key = "none") {
                            Text(
                                text = if (activeMics.isEmpty()) "No active mics yet." else "No mics match \"$query\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    groups.forEach { group ->
                        item(key = "day-${group.title}") {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group.mics, key = { mic -> mic.id }) { mic ->
                            MicPickerRow(
                                label = SetsRules.micPickerLabel(mic),
                                selected = mic.id == selectedMicId,
                                onClick = { onPick(mic) },
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 8.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun MicPickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "Date performed" picker. The latest selectable day is today (spec 6.6), so
 * you can't log a set you haven't done yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetDatePickerDialog(
    initial: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val todayMillis = SetsRules.toPickerMillis(today)
    val maxToday = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis

            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = SetsRules.toPickerMillis(SetsRules.clampToToday(initial, today)),
        yearRange = (today.year - 20)..today.year,
        selectableDates = maxToday,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) onPick(SetsRules.fromPickerMillis(millis)) else onDismiss()
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = pickerState)
    }
}
