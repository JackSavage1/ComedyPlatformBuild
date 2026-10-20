package com.comedyplatform.mictracker.ui.micform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicDraft
import com.comedyplatform.mictracker.core.model.SignupMethod
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import com.comedyplatform.mictracker.data.ApiException
import com.comedyplatform.mictracker.data.GeocodingService
import com.comedyplatform.mictracker.data.MicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Save button is busy with. */
enum class SavePhase { GEOCODING, SAVING }

data class MicFormUiState(
    /** True when editing an existing mic, false when adding one. */
    val isEdit: Boolean,
    /** The form's values (raw text as typed; cleaned up on save). */
    val draft: MicDraft,
    /** Edit only: waiting for the mic to arrive from the repository. */
    val loading: Boolean = false,
    /** Edit only: the mic doesn't exist (anymore). */
    val notFound: Boolean = false,
    /** Non-null while saving. */
    val savePhase: SavePhase? = null,
    /** Set after the user tried to save with a required field empty. */
    val showValidation: Boolean = false,
    /** The server's message when saving failed. */
    val error: String? = null,
    /** Set once saved — the screen shows it briefly, then closes. */
    val successMessage: String? = null,
    /** Extra info about the map pin (e.g. "Couldn't find that address on the map"). */
    val geocodeNote: String? = null,
) {
    val isSaving: Boolean get() = savePhase != null

    /** Inputs are locked while saving and after success. */
    val inputsEnabled: Boolean get() = !isSaving && successMessage == null && !loading && !notFound

    /** "Name, Venue, Day of Week, and Start Time are required!" when something's missing. */
    val validationError: String? get() = if (showValidation) draft.validationError else null

    fun isMissing(value: String): Boolean = showValidation && value.isBlank()
}

/**
 * The Mic Form (spec 6.9): add a new mic or edit an existing one.
 *
 * Add  → POST open_mics with null keys omitted and `is_active: true`. When an
 *        address is given it's geocoded on the phone first (rule 18) and the
 *        coordinates are included if found.
 * Edit → PATCH every field (explicit nulls for cleared ones). If the address
 *        changed it's re-geocoded and the coordinates are sent too.
 *
 * @param editMicId the mic to edit, or null to add a new one
 */
class MicFormViewModel(
    private val repository: MicRepository,
    private val geocoder: GeocodingService,
    private val editMicId: Int?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MicFormUiState(
            isEdit = editMicId != null,
            draft = if (editMicId == null) newMicDefaults() else MicDraft(),
            loading = editMicId != null,
        ),
    )
    val state: StateFlow<MicFormUiState> = _state.asStateFlow()

    /** The mic as it was when the form opened (to see whether the address changed). */
    private var original: Mic? = null

    init {
        if (editMicId != null) loadMicForEditing(editMicId)
    }

    /** Waits until the shared repository has the mic (or has finished loading without it). */
    private fun loadMicForEditing(micId: Int) {
        viewModelScope.launch {
            val (mic, _) = combine(repository.micsById, repository.hasLoaded) { byId, loaded -> Pair(byId[micId], loaded) }
                .first { (found, loaded) -> found != null || loaded }
            if (mic == null) {
                _state.update { it.copy(loading = false, notFound = true) }
            } else {
                original = mic
                _state.update { it.copy(loading = false, draft = MicFormRules.draftForEditing(mic)) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Applies a change to the draft, e.g. `update { it.copy(name = text) }`. */
    fun update(change: (MicDraft) -> MicDraft) {
        if (!_state.value.inputsEnabled) return
        _state.update { it.copy(draft = change(it.draft), error = null) }
    }

    /**
     * The time picker's result: stores the zero-padded 24h start time and
     * auto-fills the display time (rule 19), which stays editable.
     */
    fun setStartTime(hour: Int, minute: Int) = update { draft ->
        draft.copy(
            startTime = MicRules.startTimeString(hour, minute),
            displayTime = MicRules.displayTimeString(hour, minute),
        )
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    fun save() {
        val current = _state.value
        if (!current.inputsEnabled) return
        if (current.draft.validationError != null) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        // Mark busy right away so a quick second tap can't save twice.
        _state.update { it.copy(showValidation = false, error = null, geocodeNote = null, savePhase = SavePhase.SAVING) }
        viewModelScope.launch {
            try {
                if (editMicId == null) add(current.draft) else edit(editMicId, current.draft)
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.userMessage) }
            } finally {
                _state.update { it.copy(savePhase = null) }
            }
        }
    }

    private suspend fun add(formDraft: MicDraft) {
        var draft = formDraft.normalized()
        var note: String? = null
        val address = draft.address
        if (address != null) {
            _state.update { it.copy(savePhase = SavePhase.GEOCODING) }
            val point = geocoder.geocodeMic(address, draft.borough)
            if (point != null) {
                draft = draft.copy(latitude = point.latitude, longitude = point.longitude)
                note = "📍 Placed on the map."
            } else {
                note = NOT_FOUND_NOTE
            }
        }
        _state.update { it.copy(savePhase = SavePhase.SAVING) }
        repository.addMic(draft)
        _state.update {
            it.copy(
                successMessage = "Added ${draft.name} @ ${draft.venue} (${draft.dayOfWeek})!",
                geocodeNote = note,
            )
        }
    }

    private suspend fun edit(micId: Int, formDraft: MicDraft) {
        var draft = formDraft.normalized()
        var includeCoordinates = false
        var note: String? = null
        if (draft.addressDiffersFrom(original?.address)) {
            val address = draft.address
            when {
                // Address removed → the old pin no longer means anything.
                address == null -> {
                    draft = draft.copy(latitude = null, longitude = null)
                    includeCoordinates = true
                }
                // No geocoder on this phone: keep the old pin rather than wiping it.
                !geocoder.isAvailable -> note = NO_GEOCODER_NOTE
                else -> {
                    _state.update { it.copy(savePhase = SavePhase.GEOCODING) }
                    val point = geocoder.geocodeMic(address, draft.borough)
                    // Not found → clear the stale pin so the mic shows up under Manage → Geocoding.
                    draft = draft.copy(latitude = point?.latitude, longitude = point?.longitude)
                    includeCoordinates = true
                    note = if (point != null) "📍 Moved the map pin to the new address." else NOT_FOUND_NOTE
                }
            }
        }
        _state.update { it.copy(savePhase = SavePhase.SAVING) }
        repository.updateMic(micId, draft, includeCoordinates)
        _state.update { it.copy(successMessage = "Updated ${draft.name}!", geocodeNote = note) }
    }

    private companion object {
        const val NOT_FOUND_NOTE = "Couldn't find that address on the map — you can retry from Manage → Geocoding."
        const val NO_GEOCODER_NOTE = "This phone can't look up addresses, so the map pin wasn't changed."

        /** The web form's defaults: Manhattan, in-person signup, normal urgency. */
        fun newMicDefaults(): MicDraft = MicDraft(
            borough = "Manhattan",
            signupMethod = SignupMethod.IN_PERSON.raw,
        )
    }
}

/** Small pure helpers for the form (plain Kotlin). */
object MicFormRules {

    /**
     * Prefill for Edit. A day stored loosely ("mon", "Mondays") is normalized
     * to the full name the pickers use; anything else is kept as-is.
     */
    fun draftForEditing(mic: Mic): MicDraft {
        val draft = MicDraft.fromMic(mic)
        val day = mic.weekday
        return if (day != null) draft.copy(dayOfWeek = Weekdays.fullName(day)) else draft
    }

    /** "Unknown" at 0, else "15 min". */
    fun setLengthLabel(minutes: Int): String = if (minutes <= 0) "Unknown" else "$minutes min"

    /** "None" at 0, else "1 day" / "3 days". */
    fun advanceDaysLabel(days: Int): String = when {
        days <= 0 -> "None"
        days == 1 -> "1 day"
        else -> "$days days"
    }

    const val MAX_SET_LENGTH = 30
    const val MAX_ADVANCE_DAYS = 60
}
