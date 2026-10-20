package com.comedyplatform.mictracker.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.PlanKey
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.rules.SignupDeadline
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
import kotlin.math.roundToInt

/** Which button is currently working (to show a spinner and disable the others). */
enum class DetailAction { GOING, CANCELLED, CLEAR, RATING, DELETE }

data class MicDetailUiState(
    /** null while loading, or when the mic doesn't exist (see [hasLoaded]). */
    val mic: Mic? = null,
    /** The date context (from Calendar / Tonight / Map), or null (from Manage). */
    val date: LocalDate? = null,
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    /** Going / cancelled for [date], or null. */
    val planStatus: PlanStatus? = null,
    /** Rule 7 for [date]. */
    val signupDeadline: SignupDeadline? = null,
    /** Rule 6: logged sets at this mic. */
    val visitCount: Int = 0,
    /** The slider's value, 0–10 in steps of 0.5 (0 = "Not rated"). */
    val ratingValue: Float = 0f,
    /** True when the slider differs from the saved rating. */
    val ratingChanged: Boolean = false,
    val busyAction: DetailAction? = null,
    val error: String? = null,
    /** A one-off success message for a snackbar ("Rating updated"). */
    val message: String? = null,
    /** Set after a successful delete — the screen then goes back. */
    val deleted: Boolean = false,
)

/**
 * State and actions for Mic Detail (spec 6.4). Reads everything from the
 * shared [MicRepository], so changes made here show up everywhere.
 */
class MicDetailViewModel(
    private val repository: MicRepository,
    private val micId: Int,
    private val date: LocalDate?,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** Things only this screen knows about. */
    private data class LocalState(
        val ratingDraft: Float? = null,
        val busyAction: DetailAction? = null,
        val error: String? = null,
        val message: String? = null,
        val deleted: Boolean = false,
        val isRefreshing: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<MicDetailUiState> = combine(
        repository.micsById,
        repository.planLookup,
        repository.loggedVisitCounts,
        repository.hasLoaded,
        local,
    ) { micsById, plans, visits, hasLoaded, localState ->
        val mic = micsById[micId]
        val savedRating = savedSliderValue(mic)
        val slider = localState.ratingDraft ?: savedRating
        MicDetailUiState(
            mic = mic,
            date = date,
            hasLoaded = hasLoaded || mic != null,
            isRefreshing = localState.isRefreshing,
            planStatus = date?.let { plans[PlanKey(micId, it)] },
            signupDeadline = if (mic != null && date != null) mic.signupDeadline(date, today()) else null,
            visitCount = visits[micId] ?: 0,
            ratingValue = slider,
            ratingChanged = slider != savedRating,
            busyAction = localState.busyAction,
            error = localState.error,
            message = localState.message,
            deleted = localState.deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MicDetailUiState(date = date))

    init {
        // Make sure we know the plan status for a date outside the default window.
        if (date != null) {
            viewModelScope.launch {
                try {
                    repository.loadPlans(date, date)
                } catch (e: ApiException) {
                    local.update { it.copy(error = e.userMessage) }
                }
            }
        }
    }

    // ---- Actions ---------------------------------------------------------

    fun markGoing() = planAction(DetailAction.GOING) { d -> repository.setPlanStatus(micId, d, PlanStatus.GOING) }

    fun markCancelled() = planAction(DetailAction.CANCELLED) { d -> repository.setPlanStatus(micId, d, PlanStatus.CANCELLED) }

    fun clearPlan() = planAction(DetailAction.CLEAR) { d -> repository.clearPlan(micId, d) }

    /** Slider moved: snap to 0.5 steps. */
    fun onRatingChange(value: Float) {
        val snapped = (value * 2f).roundToInt() / 2f
        local.update { it.copy(ratingDraft = snapped.coerceIn(0f, 10f)) }
    }

    /** "Update rating" → PATCH mic_rating (0 = Not rated → null). */
    fun saveRating() {
        val value = state.value.ratingValue
        perform(DetailAction.RATING) {
            repository.updateMicRating(micId, if (value <= 0f) null else value.toDouble())
            local.update { it.copy(ratingDraft = null, message = "Rating updated") }
        }
    }

    /** RPC delete_mic (soft delete), then the screen goes back. */
    fun deleteMic() {
        perform(DetailAction.DELETE) {
            repository.deleteMic(micId)
            local.update { it.copy(deleted = true) }
        }
    }

    /** Pull-to-refresh. */
    fun refresh() {
        if (local.value.isRefreshing) return
        viewModelScope.launch {
            local.update { it.copy(isRefreshing = true) }
            val ok = repository.refreshAll()
            var error: String? = if (ok) null else repository.lastError.value
            if (date != null) {
                try {
                    repository.loadPlans(date, date)
                } catch (e: ApiException) {
                    error = e.userMessage
                }
            }
            local.update { it.copy(isRefreshing = false, error = error) }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    // ---- Helpers ---------------------------------------------------------

    private fun planAction(action: DetailAction, block: suspend (LocalDate) -> Unit) {
        val d = date ?: return
        perform(action) { block(d) }
    }

    private fun perform(action: DetailAction, block: suspend () -> Unit) {
        if (local.value.busyAction != null) return
        viewModelScope.launch {
            local.update { it.copy(busyAction = action, error = null) }
            try {
                block()
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update { it.copy(busyAction = null) }
            }
        }
    }

    private fun savedSliderValue(mic: Mic?): Float {
        val rating = mic?.micRating ?: return 0f
        return ((rating * 2.0).roundToInt() / 2.0).toFloat().coerceIn(0f, 10f)
    }
}
