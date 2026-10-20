package com.comedyplatform.mictracker.ui.sets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.CrowdSize
import com.comedyplatform.mictracker.core.model.RecordingType
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.core.rules.TagRules
import com.comedyplatform.mictracker.ui.components.Callout
import com.comedyplatform.mictracker.ui.components.CalloutTone
import com.comedyplatform.mictracker.ui.components.SectionHeader
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import com.comedyplatform.mictracker.ui.components.TagSelector
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The "Log a Set" tab (spec 6.6): the planned sets waiting for details, then
 * the full log form. A form with text fields scrolls best as a plain Column.
 */
@Composable
internal fun LogSetTab(
    state: SetsUiState,
    onOpenPlanned: (SetWithMic) -> Unit,
    onRemovePlanned: (setId: Int) -> Unit,
    onFormChange: ((SetFormState) -> SetFormState) -> Unit,
    onToggleTag: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onClearForm: () -> Unit,
    onSave: () -> Unit,
) {
    val form = state.form
    var showMicPicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Shrink above the keyboard (the app draws edge-to-edge) so the
            // focused Notes / Feedback field can scroll into view.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        // ---- Planned sets -------------------------------------------------
        if (state.planned.isNotEmpty()) {
            SectionHeader(
                title = "📋 Planned Sets — Ready to Log",
                subtitle = "You marked these as Going. Tap one to fill in your set details, " +
                    "or ✕ to remove it if you didn't go.",
            )
            state.planned.forEach { item ->
                PlannedSetRow(
                    item = item,
                    isLoaded = form.targetSetId == item.set.id,
                    isRemoving = item.set.id in state.removingIds,
                    onClick = { onOpenPlanned(item) },
                    onRemove = { onRemovePlanned(item.set.id) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        SectionHeader(
            title = if (form.mode == FormMode.EDIT_LOGGED) "Edit a Set" else "Log a Performance",
            subtitle = "Record your set details — the more you log, the better your Stats get!",
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Prefill banner ----------------------------------------------
            when (form.mode) {
                FormMode.FILL_PLANNED -> PrefillBanner(
                    text = SetsRules.fillingText(state.formTarget),
                    buttonLabel = "Log a different set instead",
                    onClear = onClearForm,
                )
                FormMode.EDIT_LOGGED -> PrefillBanner(
                    text = SetsRules.editingText(state.formTarget),
                    buttonLabel = "Cancel edit",
                    onClear = onClearForm,
                )
                FormMode.NEW -> Unit
            }

            // ---- Which mic? ---------------------------------------------------
            FormSection(title = "Which mic?") {
                val mic = state.selectedMic
                val label = when {
                    mic == null && form.micId != null -> "Mic #${form.micId}"
                    mic == null -> "Choose a mic…"
                    mic.isActiveMic -> SetsRules.micPickerLabel(mic)
                    else -> SetsRules.micPickerLabel(mic) + " (inactive)"
                }
                PickerButton(
                    text = label,
                    isPlaceholder = form.micId == null,
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    onClick = { showMicPicker = true },
                )
            }

            // ---- When? ----------------------------------------------------------
            FormSection(title = "When?") {
                PickerButton(
                    text = "Date performed: " + SetsRules.longDate(form.date),
                    isPlaceholder = false,
                    trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    onClick = { showDatePicker = true },
                )
            }

            // ---- How'd it go? ---------------------------------------------------
            FormSection(title = "How'd it go?") {
                RatingSlider(
                    label = "Set rating (how did YOU do?)",
                    value = form.setRating,
                    help = "1 = total bomb, 10 = absolute killer set",
                    onChange = { value -> onFormChange { it.copy(setRating = value) } },
                )
                RatingSlider(
                    label = "Crowd rating (how was the audience?)",
                    value = form.crowdRating,
                    help = "1 = dead silent / hostile, 10 = engaged and laughing at everything",
                    onChange = { value -> onFormChange { it.copy(crowdRating = value) } },
                )
                FieldLabel("Crowd size")
                ChoiceSegments(
                    options = CrowdSize.entries.map { it.raw to it.label },
                    selected = form.crowdSize,
                    onSelect = { raw -> onFormChange { it.copy(crowdSize = raw) } },
                )
            }

            // ---- Your material -------------------------------------------------
            FormSection(title = "Your material") {
                OutlinedTextField(
                    value = form.setList,
                    onValueChange = { text -> onFormChange { it.copy(setList = text) } },
                    label = { Text("Set list (bit names, order)") },
                    placeholder = { Text("1. Subway bit\n2. Dating app opener\n3. New bit about coffee") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = "Trying new material?",
                    checked = form.newMaterial,
                    onCheckedChange = { checked -> onFormChange { it.copy(newMaterial = checked) } },
                )
            }

            // ---- Recording -------------------------------------------------------
            FormSection(title = "Recording") {
                OutlinedTextField(
                    value = form.recordingUrl,
                    onValueChange = { text -> onFormChange { it.copy(recordingUrl = text) } },
                    label = { Text("Recording link") },
                    placeholder = { Text("YouTube URL, Google Drive link, etc.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel("Recording type")
                ChoiceSegments(
                    options = RecordingType.entries.map { it.raw to it.label },
                    selected = form.recordingType,
                    onSelect = { raw -> onFormChange { it.copy(recordingType = raw) } },
                )
            }

            // ---- Reflections -----------------------------------------------------
            FormSection(title = "Reflections") {
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { text -> onFormChange { it.copy(notes = text) } },
                    label = { Text("Notes — what worked, what didn't") },
                    placeholder = { Text("The subway bit killed. The coffee bit needs a better tag.") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = "Got feedback from host or other comics?",
                    checked = form.gotFeedback,
                    onCheckedChange = { checked -> onFormChange { it.copy(gotFeedback = checked) } },
                )
                if (form.gotFeedback) {
                    OutlinedTextField(
                        value = form.feedbackNotes,
                        onValueChange = { text -> onFormChange { it.copy(feedbackNotes = text) } },
                        label = { Text("What feedback did you get?") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SwitchRow(
                    label = "Would return to this mic?",
                    checked = form.wouldReturn,
                    onCheckedChange = { checked -> onFormChange { it.copy(wouldReturn = checked) } },
                )
            }

            // ---- Tags -------------------------------------------------------------
            FormSection(title = "Tags (pick all that apply)") {
                // Keep any unknown tags from an older row visible so they can be removed.
                val options = TagRules.options + form.tags.filter { it !in TagRules.options }
                TagSelector(
                    selected = form.tags.toSet(),
                    onToggle = onToggleTag,
                    options = options,
                )
            }

            // ---- Save ---------------------------------------------------------------
            val formError = state.formError
            if (formError != null) {
                Callout(text = formError, tone = CalloutTone.ERROR)
            }
            Button(
                onClick = onSave,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) {
                    SmallSpinner()
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (form.mode == FormMode.EDIT_LOGGED) "💾 Save Changes" else "💾 Save Set")
            }
        }
    }

    if (showMicPicker) {
        MicPickerDialog(
            activeMics = state.activeMics,
            selectedMicId = form.micId,
            onPick = { mic ->
                showMicPicker = false
                onFormChange { it.copy(micId = mic.id) }
            },
            onDismiss = { showMicPicker = false },
        )
    }

    if (showDatePicker) {
        SetDatePickerDialog(
            initial = form.date,
            today = state.today,
            onPick = { date ->
                showDatePicker = false
                onDateChange(date)
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/** One planned row: "📝 Log: Mic @ Venue — 10/20/2026" with a trailing ✕. */
@Composable
private fun PlannedSetRow(
    item: SetWithMic,
    isLoaded: Boolean,
    isRemoving: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SetsRules.plannedLabel(item),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isLoaded) FontWeight.Bold else FontWeight.Normal,
            color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        )
        if (isRemoving) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
        } else {
            IconButton(onClick = onRemove) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove planned set")
            }
        }
    }
}

/** The info banner shown while the form edits an existing row, with a way out. */
@Composable
private fun PrefillBanner(text: String, buttonLabel: String, onClear: () -> Unit) {
    Column {
        Callout(text = text, tone = CalloutTone.INFO)
        TextButton(onClick = onClear) { Text(buttonLabel) }
    }
}

/** A titled group of fields ("How'd it go?"). */
@Composable
private fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A full-width outlined button that looks like a dropdown field. */
@Composable
private fun PickerButton(
    text: String,
    isPlaceholder: Boolean,
    trailingIcon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        trailingIcon()
    }
}

/** "Set rating … 7/10" + a 1–10 slider + a help line. */
@Composable
private fun RatingSlider(label: String, value: Int, help: String, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = "$value/10", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onChange(raw.roundToInt().coerceIn(1, 10)) },
            valueRange = 1f..10f,
            // 1, 2, …, 10 → 10 positions → 8 steps between the two ends.
            steps = 8,
        )
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A segmented control for a small set of raw values, e.g. crowd size.
 * [options] are (raw value, label) pairs. A [selected] value that isn't one
 * of the options (an old row's unknown text) simply shows nothing selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSegments(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (raw, label) ->
            SegmentedButton(
                selected = raw == selected,
                onClick = { onSelect(raw) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // No check-mark icon: four labels need all the width a phone has.
                icon = {},
            ) {
                Text(text = label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
