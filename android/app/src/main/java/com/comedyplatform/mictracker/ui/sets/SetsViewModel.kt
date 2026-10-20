package com.comedyplatform.mictracker.ui.sets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.CrowdSize
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.RecordingType
import com.comedyplatform.mictracker.core.model.SetDraft
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.data.ApiException
import com.comedyplatform.mictracker.data.MicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The two tabs of My Sets (spec 6.6). */
enum class SetsTab(val label: String) {
    LOG("📝 Log a Set"),
    HISTORY("📋 History"),
}

/** What saving the form will do. */
enum class FormMode {
    /** A brand-new set → POST my_sets. */
    NEW,

    /** Filling in a planned (skeletal) row created by "Going" → PATCH that row. */
    FILL_PLANNED,

    /** Editing an already logged set from Set Detail → PATCH that row. */
    EDIT_LOGGED,
}

/**
 * Everything the "Log a Set" form edits. Text fields hold what the user
 * typed; blank → null happens when the [SetDraft] is built on save.
 */
data class SetFormState(
    /** The chosen mic, or null until one is picked. */
    val micId: Int? = null,
    val date: LocalDate,
    /** 1–10, default 5. */
    val setRating: Int = 5,
    /** 1–10, default 5. */
    val crowdRating: Int = 5,
    /** "empty" / "sparse" / "decent" / "packed" (null only for an old row that had none). */
    val crowdSize: String? = CrowdSize.DECENT.raw,
    val setList: String = "",
    val newMaterial: Boolean = false,
    val recordingUrl: String = "",
    /** "none" / "video" / "audio" (default none). */
    val recordingType: String? = RecordingType.NONE.raw,
    val notes: String = "",
    val gotFeedback: Boolean = false,
    val feedbackNotes: String = "",
    val wouldReturn: Boolean = true,
    /** Selected tags, in the fixed option order (rule 14). */
    val tags: List<String> = emptyList(),
    val mode: FormMode = FormMode.NEW,
    /** The my_sets row that saving will PATCH (planned or logged), or null for a new set. */
    val targetSetId: Int? = null,
) {
    companion object {
        /** An empty form dated [today]. */
        fun blank(today: LocalDate): SetFormState = SetFormState(date = today)

        /**
         * Prefills from an existing row. Uses :core's [SetDraft.fromSet] so a
         * planned row gets the form defaults (ratings 5, crowd decent,
         * recording none). The date is clamped to today (the picker's max).
         */
        fun fromSet(item: SetWithMic, today: LocalDate): SetFormState {
            val draft = SetDraft.fromSet(item.set, fallbackDate = today)
            return SetFormState(
                micId = item.set.openMicId,
                date = SetsRules.clampToToday(draft.datePerformed, today),
                setRating = (draft.setRating ?: 5).coerceIn(1, 10),
                crowdRating = (draft.crowdRating ?: 5).coerceIn(1, 10),
                crowdSize = draft.crowdSize,
                setList = draft.setList ?: "",
                newMaterial = draft.newMaterial,
                recordingUrl = draft.recordingUrl ?: "",
                recordingType = draft.recordingType,
                notes = draft.notes ?: "",
                gotFeedback = draft.gotFeedback,
                feedbackNotes = draft.feedbackNotes ?: "",
                wouldReturn = draft.wouldReturn,
                tags = draft.tags,
                mode = if (item.set.isPlanned) FormMode.FILL_PLANNED else FormMode.EDIT_LOGGED,
                targetSetId = item.set.id,
            )
        }
    }
}

/** A one-off message for the snackbar. [id] makes repeated identical messages show again. */
data class SetsMessage(val text: String, val celebrate: Boolean = false, val id: Long = System.nanoTime())

/** Everything both tabs show. */
data class SetsUiState(
    val tab: SetsTab = SetsTab.LOG,
    val today: LocalDate,
    // ---- Shared data -------------------------------------------------------
    /** All sets joined with their mics, newest first. */
    val allSets: List<SetWithMic> = emptyList(),
    /** Active mics for the picker (sorted Mon→Sun then time by the repository). */
    val activeMics: List<Mic> = emptyList(),
    /** Every mic by id, including inactive ones (a planned row may point at a deleted mic). */
    val micsById: Map<Int, Mic> = emptyMap(),
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    // ---- Log tab -------------------------------------------------------------
    /** Planned rows, oldest first. */
    val planned: List<SetWithMic> = emptyList(),
    val form: SetFormState,
    val isSaving: Boolean = false,
    /** Inline validation message under the Save button ("Please select a mic!"). */
    val formError: String? = null,
    /** Planned rows whose ✕ is working. */
    val removingIds: Set<Int> = emptySet(),
    // ---- History tab ---------------------------------------------------------
    val summary: SetsRules.HistorySummary? = null,
    val micFilterOptions: List<String> = emptyList(),
    val tagFilterOptions: List<String> = emptyList(),
    /** null = All. */
    val micFilter: String? = null,
    val minRating: Int = 1,
    val tagFilter: Set<String> = emptySet(),
    val filteredSets: List<SetWithMic> = emptyList(),
    /** The row shown in Set Detail, or null. Looked up live so edits show immediately. */
    val detail: SetWithMic? = null,
    val isDeleting: Boolean = false,
    // ---- Feedback ------------------------------------------------------------
    val error: String? = null,
    val message: SetsMessage? = null,
) {
    /** The row the form is updating (for the banner), if it's still loaded. */
    val formTarget: SetWithMic?
        get() = form.targetSetId?.let { id -> allSets.firstOrNull { it.set.id == id } }

    /** The mic currently chosen in the form (may be inactive for an old planned row). */
    val selectedMic: Mic?
        get() = form.micId?.let { micsById[it] }
}

/**
 * State and actions for My Sets (spec 6.6). Reads the shared [MicRepository];
 * every save/delete goes through it, so Stats and Mic Detail update too.
 */
class SetsViewModel(
    private val repository: MicRepository,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** Things only this screen knows about. */
    private data class LocalState(
        val tab: SetsTab,
        val form: SetFormState,
        val isSaving: Boolean = false,
        val formError: String? = null,
        val removingIds: Set<Int> = emptySet(),
        val micFilter: String? = null,
        val minRating: Int = 1,
        val tagFilter: Set<String> = emptySet(),
        val detailSetId: Int? = null,
        val isDeleting: Boolean = false,
        val error: String? = null,
        val message: SetsMessage? = null,
    )

    private val local = MutableStateFlow(
        LocalState(tab = SetsTab.LOG, form = SetFormState.blank(today())),
    )

    /** The repository's data in one value (combine() takes at most 5 flows at a time). */
    private data class RepoSnapshot(
        val sets: List<SetWithMic>,
        val activeMics: List<Mic>,
        val micsById: Map<Int, Mic>,
        val hasLoaded: Boolean,
        val isRefreshing: Boolean,
    )

    private val repoSnapshot = combine(
        repository.setsWithMics,
        repository.activeMics,
        repository.micsById,
        repository.hasLoaded,
        repository.isRefreshing,
    ) { sets, active, byId, hasLoaded, refreshing ->
        RepoSnapshot(sets, active, byId, hasLoaded, refreshing)
    }

    val state: StateFlow<SetsUiState> = combine(
        repoSnapshot,
        repository.lastError,
        local,
    ) { repo, repoError, ui ->
        val day = today()
        // A filter value that no longer exists (e.g. its last set was deleted) means "All".
        val micOptions = SetsRules.micFilterOptions(repo.sets)
        val micFilter = ui.micFilter?.takeIf { it in micOptions }
        val tagOptions = SetsRules.tagFilterOptions(repo.sets)
        val tagFilter = ui.tagFilter.filter { it in tagOptions }.toSet()
        SetsUiState(
            tab = ui.tab,
            today = day,
            allSets = repo.sets,
            activeMics = repo.activeMics,
            micsById = repo.micsById,
            hasLoaded = repo.hasLoaded || repo.sets.isNotEmpty(),
            isRefreshing = repo.isRefreshing,
            planned = SetsRules.plannedOldestFirst(repo.sets),
            form = ui.form,
            isSaving = ui.isSaving,
            formError = ui.formError,
            removingIds = ui.removingIds,
            summary = if (repo.sets.isEmpty()) null else SetsRules.summary(repo.sets, day),
            micFilterOptions = micOptions,
            tagFilterOptions = tagOptions,
            micFilter = micFilter,
            minRating = ui.minRating,
            tagFilter = tagFilter,
            filteredSets = SetsRules.filterHistory(repo.sets, micFilter, ui.minRating, tagFilter),
            detail = ui.detailSetId?.let { id -> repo.sets.firstOrNull { it.set.id == id } },
            isDeleting = ui.isDeleting,
            error = ui.error ?: repoError,
            message = ui.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SetsUiState(today = today(), form = local.value.form),
    )

    // ------------------------------------------------------------------
    // Tabs + refresh
    // ------------------------------------------------------------------

    fun selectTab(tab: SetsTab) = local.update { it.copy(tab = tab) }

    /** Pull-to-refresh: reload mics, sets and plans. Errors show via the repository's lastError. */
    fun refresh() {
        viewModelScope.launch { repository.refreshAll() }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
        repository.clearError()
    }

    fun messageShown() = local.update { it.copy(message = null) }

    // ------------------------------------------------------------------
    // The form
    // ------------------------------------------------------------------

    /** Applies a change to the form (every field edit goes through here). */
    fun updateForm(change: (SetFormState) -> SetFormState) =
        local.update { it.copy(form = change(it.form), formError = null) }

    fun toggleTag(tag: String) = updateForm { it.copy(tags = SetsRules.toggleTag(it.tags, tag)) }

    /** Picked in the date dialog; clamped so it can never be after today. */
    fun setDate(date: LocalDate) = updateForm { it.copy(date = SetsRules.clampToToday(date, today())) }

    /** Tap on a planned row (Log tab or History) → prefill the form; saving UPDATEs that row. */
    fun startFromPlanned(item: SetWithMic) {
        local.update {
            it.copy(
                tab = SetsTab.LOG,
                form = SetFormState.fromSet(item, today()),
                formError = null,
                detailSetId = null,
            )
        }
    }

    /** Set Detail → Edit: prefill the form from a logged set; saving PATCHes it. */
    fun startEdit(item: SetWithMic) = startFromPlanned(item)

    /** "Log a different set instead" / "Cancel edit": back to an empty form. */
    fun clearForm() {
        local.update { it.copy(form = SetFormState.blank(today()), formError = null) }
    }

    /** Save: validate, then INSERT (new) or PATCH (planned row / edit). */
    fun save() {
        val current = local.value
        if (current.isSaving) return
        val form = current.form
        val micId = form.micId
        if (micId == null) {
            local.update { it.copy(formError = "Please select a mic!") }
            return
        }
        val draft = SetDraft(
            openMicId = micId,
            datePerformed = SetsRules.clampToToday(form.date, today()),
            setRating = form.setRating,
            crowdRating = form.crowdRating,
            crowdSize = form.crowdSize,
            setList = form.setList,
            recordingUrl = form.recordingUrl,
            recordingType = form.recordingType,
            notes = form.notes,
            newMaterial = form.newMaterial,
            gotFeedback = form.gotFeedback,
            feedbackNotes = form.feedbackNotes,
            wouldReturn = form.wouldReturn,
            tags = form.tags,
        )
        viewModelScope.launch {
            local.update { it.copy(isSaving = true, formError = null, error = null) }
            try {
                val target = form.targetSetId
                if (target != null) {
                    repository.updateSet(target, draft)
                } else {
                    repository.logSet(draft)
                }
                val text = if (form.mode == FormMode.EDIT_LOGGED) "✅ Set updated!" else "✅ Set logged! Keep grinding! 🎤"
                local.update {
                    it.copy(
                        form = SetFormState.blank(today()),
                        message = SetsMessage(text, celebrate = form.mode != FormMode.EDIT_LOGGED),
                    )
                }
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update { it.copy(isSaving = false) }
            }
        }
    }

    /** ✕ on a planned row → RPC delete_planned_set. */
    fun removePlanned(setId: Int) {
        if (setId in local.value.removingIds) return
        viewModelScope.launch {
            local.update { it.copy(removingIds = it.removingIds + setId, error = null) }
            try {
                val deleted = repository.deletePlannedSet(setId)
                local.update { state ->
                    // If that row was loaded into the form, the form must not point at it any more.
                    val form = if (state.form.targetSetId == setId) SetFormState.blank(today()) else state.form
                    state.copy(
                        form = form,
                        message = if (deleted) {
                            SetsMessage("Removed the planned set.")
                        } else {
                            SetsMessage("That set already has a rating, so it was kept.")
                        },
                    )
                }
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update { it.copy(removingIds = it.removingIds - setId) }
            }
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    fun setMicFilter(name: String?) = local.update { it.copy(micFilter = name) }

    fun setMinRating(value: Int) = local.update { it.copy(minRating = value.coerceIn(1, 10)) }

    fun toggleTagFilter(tag: String) = local.update {
        it.copy(tagFilter = if (tag in it.tagFilter) it.tagFilter - tag else it.tagFilter + tag)
    }

    fun clearHistoryFilters() = local.update { it.copy(micFilter = null, minRating = 1, tagFilter = emptySet()) }

    /** Tap on a history row: logged → Set Detail; planned → Log tab prefilled. */
    fun openHistoryRow(item: SetWithMic) {
        if (item.set.isPlanned) {
            startFromPlanned(item)
        } else {
            local.update { it.copy(detailSetId = item.set.id) }
        }
    }

    fun closeDetail() = local.update { it.copy(detailSetId = null) }

    /** Set Detail → Delete (after the confirm dialog) → DELETE my_sets?id=eq.X. */
    fun deleteSet(setId: Int) {
        if (local.value.isDeleting) return
        viewModelScope.launch {
            local.update { it.copy(isDeleting = true, error = null) }
            try {
                repository.deleteSet(setId)
                local.update { state ->
                    val form = if (state.form.targetSetId == setId) SetFormState.blank(today()) else state.form
                    state.copy(detailSetId = null, form = form, message = SetsMessage("Set deleted."))
                }
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update { it.copy(isDeleting = false) }
            }
        }
    }
}
