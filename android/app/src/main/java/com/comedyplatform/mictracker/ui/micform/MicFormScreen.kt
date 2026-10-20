package com.comedyplatform.mictracker.ui.micform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.core.model.MicDraft
import com.comedyplatform.mictracker.core.model.SignupMethod
import com.comedyplatform.mictracker.core.model.Urgency
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import com.comedyplatform.mictracker.ui.components.Callout
import com.comedyplatform.mictracker.ui.components.CalloutTone
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import kotlinx.coroutines.delay

/**
 * Mic Form (spec 6.9) — add a new mic (editMicId == null) or edit one.
 *
 * Required: Name, Venue, Day of week, Start time. The start time comes from a
 * time picker (stored "HH:mm") and fills in the display time ("7:00 PM"),
 * which stays editable. On success the confirmation shows briefly, then
 * [onDone] goes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicFormScreen(editMicId: Int?, onDone: () -> Unit) {
    val viewModel = appViewModel(key = "mic-form-${editMicId ?: "new"}") { container ->
        MicFormViewModel(
            repository = container.micRepository,
            geocoder = container.geocodingService,
            editMicId = editMicId,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Saved: let the success message register, then go back.
    LaunchedEffect(state.successMessage) {
        if (state.successMessage != null) {
            delay(SUCCESS_DISPLAY_MILLIS)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit Mic" else "Add a Mic") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            state.loading -> LoadingBox(modifier = contentModifier, message = "Loading mic…")
            state.notFound -> Column(modifier = contentModifier) {
                EmptyState(
                    title = "Mic not found",
                    message = "This mic may have been removed.",
                    action = { TextButton(onClick = onDone) { Text("Go back") } },
                )
            }
            else -> MicFormContent(
                state = state,
                onChange = viewModel::update,
                onPickTime = viewModel::setStartTime,
                onSave = viewModel::save,
                onDismissError = viewModel::dismissError,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun MicFormContent(
    state: MicFormUiState,
    onChange: ((MicDraft) -> MicDraft) -> Unit,
    onPickTime: (hour: Int, minute: Int) -> Unit,
    onSave: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val enabled = state.inputsEnabled
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Basic info --------------------------------------------------------
        FormSectionTitle("Basic Info")
        FormTextField(
            label = "Name *",
            value = draft.name,
            onValueChange = { text -> onChange { it.copy(name = text) } },
            enabled = enabled,
            placeholder = "e.g. Comedy Night",
            isError = state.isMissing(draft.name),
            capitalization = KeyboardCapitalization.Words,
        )
        FormTextField(
            label = "Venue *",
            value = draft.venue,
            onValueChange = { text -> onChange { it.copy(venue = text) } },
            enabled = enabled,
            placeholder = "e.g. The Comedy Club",
            isError = state.isMissing(draft.venue),
            capitalization = KeyboardCapitalization.Words,
        )
        FormTextField(
            label = "Address",
            value = draft.address ?: "",
            onValueChange = { text -> onChange { it.copy(address = text) } },
            enabled = enabled,
            placeholder = "e.g. 123 Main St",
            supportingText = "Used to place the mic on the map",
            capitalization = KeyboardCapitalization.Words,
        )
        FormTextField(
            label = "Neighborhood",
            value = draft.neighborhood ?: "",
            onValueChange = { text -> onChange { it.copy(neighborhood = text) } },
            enabled = enabled,
            placeholder = "e.g. East Village",
            capitalization = KeyboardCapitalization.Words,
        )
        PickerField(
            label = "Borough",
            valueText = TextRules.trimmedOrNull(draft.borough) ?: NOT_SET,
            options = boroughOptions(draft.borough),
            onSelect = { borough -> onChange { it.copy(borough = borough) } },
            enabled = enabled,
        )

        // ---- Schedule ------------------------------------------------------------
        FormSectionTitle("Schedule")
        PickerField(
            label = "Day of week *",
            valueText = draft.dayOfWeek,
            options = dayOptions(draft.dayOfWeek),
            onSelect = { day -> onChange { it.copy(dayOfWeek = day) } },
            enabled = enabled,
            placeholder = "Choose a day",
            isError = state.isMissing(draft.dayOfWeek),
        )
        StartTimeField(
            startTime = draft.startTime,
            displayHint = MicRules.hourAndMinute(draft.startTime)?.let { (h, m) -> MicRules.displayTimeString(h, m) },
            onClick = { showTimePicker = true },
            enabled = enabled,
            isError = state.isMissing(draft.startTime),
        )
        FormTextField(
            label = "Display time",
            value = draft.displayTime ?: "",
            onValueChange = { text -> onChange { it.copy(displayTime = text) } },
            enabled = enabled,
            placeholder = "e.g. 7:00 PM",
            supportingText = "Filled in from the start time — edit if you like",
        )

        // ---- Details -----------------------------------------------------------------
        FormSectionTitle("Details")
        FormTextField(
            label = "Cost",
            value = draft.cost ?: "",
            onValueChange = { text -> onChange { it.copy(cost = text) } },
            enabled = enabled,
            placeholder = "e.g. Free, $5, 1 drink min",
        )
        val setLength = draft.setLengthMin ?: 0
        StepperRow(
            label = "Set length",
            caption = "0 = unknown",
            valueText = MicFormRules.setLengthLabel(setLength),
            onDecrement = if (setLength > 0) {
                { onChange { it.copy(setLengthMin = (setLength - 1).takeIf { minutes -> minutes > 0 }) } }
            } else {
                null
            },
            onIncrement = if (setLength < MicFormRules.MAX_SET_LENGTH) {
                { onChange { it.copy(setLengthMin = setLength + 1) } }
            } else {
                null
            },
            enabled = enabled,
        )
        PickerField(
            label = "Signup method",
            valueText = SignupMethod.displayText(draft.signupMethod) ?: NOT_SET,
            options = signupOptions(draft.signupMethod),
            onSelect = { method -> onChange { it.copy(signupMethod = method) } },
            enabled = enabled,
        )
        FormTextField(
            label = "Signup URL",
            value = draft.signupUrl ?: "",
            onValueChange = { text -> onChange { it.copy(signupUrl = text) } },
            enabled = enabled,
            placeholder = "https://…",
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
        )
        FormTextField(
            label = "Signup notes",
            value = draft.signupNotes ?: "",
            onValueChange = { text -> onChange { it.copy(signupNotes = text) } },
            enabled = enabled,
            placeholder = "e.g. Arrive 30 min early",
        )
        FormTextField(
            label = "Venue website",
            value = draft.venueUrl ?: "",
            onValueChange = { text -> onChange { it.copy(venueUrl = text) } },
            enabled = enabled,
            placeholder = "https://…",
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
        )
        FormTextField(
            label = "Instagram",
            value = draft.instagram ?: "",
            onValueChange = { text -> onChange { it.copy(instagram = text) } },
            enabled = enabled,
            placeholder = "e.g. @comedyclub",
            capitalization = KeyboardCapitalization.None,
        )

        // ---- Urgency ---------------------------------------------------------------------
        FormSectionTitle("Urgency")
        UrgencySelector(
            selected = Urgency.from(draft.urgency),
            onSelect = { urgency -> onChange { it.copy(urgency = urgency.raw) } },
            enabled = enabled,
        )
        FormTextField(
            label = "Urgency note",
            value = draft.urgencyNote ?: "",
            onValueChange = { text -> onChange { it.copy(urgencyNote = text) } },
            enabled = enabled,
            placeholder = "e.g. Signs up fast!",
        )
        val advanceDays = draft.advanceDays
        StepperRow(
            label = "Advance days to sign up",
            caption = "How many days ahead the list opens",
            valueText = MicFormRules.advanceDaysLabel(advanceDays),
            onDecrement = if (advanceDays > 0) {
                { onChange { it.copy(advanceDays = advanceDays - 1) } }
            } else {
                null
            },
            onIncrement = if (advanceDays < MicFormRules.MAX_ADVANCE_DAYS) {
                { onChange { it.copy(advanceDays = advanceDays + 1) } }
            } else {
                null
            },
            enabled = enabled,
        )
        SwitchField(
            label = "Biweekly 🔄",
            caption = "Runs every other week",
            checked = draft.isBiweekly,
            onCheckedChange = { checked -> onChange { it.copy(isBiweekly = checked) } },
            enabled = enabled,
        )
        FormTextField(
            label = "Notes",
            value = draft.notes ?: "",
            onValueChange = { text -> onChange { it.copy(notes = text) } },
            enabled = enabled,
            placeholder = "Any other details about this mic",
            singleLine = false,
            minLines = 3,
        )

        // ---- Feedback + Save -----------------------------------------------------------------
        state.validationError?.let { message -> Callout(text = message, tone = CalloutTone.ERROR) }
        ErrorBanner(message = state.error, onDismiss = onDismissError)
        state.successMessage?.let { message ->
            Callout(text = "✅ $message", tone = CalloutTone.SUCCESS)
        }
        state.geocodeNote?.let { note -> Callout(text = note, tone = CalloutTone.INFO) }

        Button(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            when (state.savePhase) {
                SavePhase.GEOCODING -> {
                    SmallSpinner()
                    Spacer(Modifier.width(8.dp))
                    Text("Finding address…")
                }
                SavePhase.SAVING -> {
                    SmallSpinner()
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…")
                }
                null -> Text(if (state.isEdit) "💾 Save Changes" else "➕ Add Mic")
            }
        }
    }

    if (showTimePicker) {
        val (hour, minute) = MicRules.hourAndMinute(draft.startTime) ?: Pair(DEFAULT_HOUR, 0)
        StartTimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { pickedHour, pickedMinute ->
                showTimePicker = false
                onPickTime(pickedHour, pickedMinute)
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

/** normal / medium / high as a segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrgencySelector(selected: Urgency, onSelect: (Urgency) -> Unit, enabled: Boolean) {
    val options = Urgency.pickerOrder
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, urgency ->
            SegmentedButton(
                selected = urgency == selected,
                onClick = { onSelect(urgency) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
                label = { Text("${urgency.dot} ${urgency.label}", maxLines = 1) },
            )
        }
    }
}

// ---- Picker options -----------------------------------------------------------------

/** "Not set" + the five boroughs (+ the mic's own value if it's something else). */
private fun boroughOptions(current: String?): List<PickerOption<String?>> {
    val options = mutableListOf<PickerOption<String?>>(PickerOption(null, NOT_SET))
    Boroughs.all.forEach { options += PickerOption(it, it) }
    val custom = TextRules.trimmedOrNull(current)
    if (custom != null && Boroughs.all.none { it.equals(custom, ignoreCase = true) }) {
        options += PickerOption(custom, custom)
    }
    return options
}

/** Monday … Sunday (+ the mic's own value if it isn't a weekday name). */
private fun dayOptions(current: String): List<PickerOption<String>> {
    val options = Weekdays.fullNames.map { PickerOption(it, it) }.toMutableList()
    val custom = TextRules.trimmedOrNull(current)
    if (custom != null && Weekdays.fullNames.none { it == custom }) options += PickerOption(custom, custom)
    return options
}

/** "Not set" + in person / online / email / Instagram DM (+ an unknown stored value, kept as-is). */
private fun signupOptions(current: String?): List<PickerOption<String?>> {
    val options = mutableListOf<PickerOption<String?>>(PickerOption(null, NOT_SET))
    SignupMethod.pickerOptions.forEach { options += PickerOption(it.raw, it.label) }
    val raw = TextRules.trimmedOrNull(current)
    if (raw != null && SignupMethod.from(raw) == SignupMethod.OTHER) {
        options += PickerOption(raw, SignupMethod.displayText(raw) ?: raw)
    }
    return options
}

private const val NOT_SET = "Not set"

/** The picker opens at 7 PM when there's no start time yet. */
private const val DEFAULT_HOUR = 19

/** How long the "Added …!" confirmation stays before the form closes. */
private const val SUCCESS_DISPLAY_MILLIS = 1_500L
