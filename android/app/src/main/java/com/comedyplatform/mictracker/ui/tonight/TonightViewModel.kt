package com.comedyplatform.mictracker.ui.tonight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.data.MicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Everything the Tonight screen (spec 6.2, a port of the web app's home page)
 * shows. All lists contain ACTIVE mics only, already sorted by start time.
 */
data class TonightUiState(
    /** The device's local date when the state was built. */
    val today: LocalDate,
    val tonightMics: List<Mic> = emptyList(),
    val tomorrowMics: List<Mic> = emptyList(),
    /** Quick stats: number of active mics. */
    val totalActive: Int = 0,
    /** Quick stats: distinct non-blank neighborhoods of active mics. */
    val neighborhoodCount: Int = 0,
    /** Quick stats: borough with the most active mics, or "N/A". */
    val topBorough: String = "N/A",
    /** False until the first successful load (show a spinner). */
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    /** The last load error from the shared repository, or null. */
    val error: String? = null,
) {
    val tomorrow: LocalDate get() = today.plusDays(1)

    /** "Monday". */
    val todayName: String get() = DateRules.weekdayName(today)

    /** "Tuesday". */
    val tomorrowName: String get() = DateRules.weekdayName(tomorrow)
}

/**
 * Reads the shared [MicRepository] and turns it into [TonightUiState].
 *
 * @param today returns the device's local date (spec 3.5: "Today" = device date)
 */
class TonightViewModel(
    private val repository: MicRepository,
    private val today: () -> LocalDate,
) : ViewModel() {

    /**
     * The date the screen is showing. Re-read on refresh and whenever the tab
     * is shown, so an app left open past midnight moves on to the new day.
     */
    private val currentDay = MutableStateFlow(today())

    val state: StateFlow<TonightUiState> = combine(
        repository.activeMics,
        repository.hasLoaded,
        repository.isRefreshing,
        repository.lastError,
        currentDay,
    ) { activeMics, hasLoaded, isRefreshing, error, day ->
        TonightUiState(
            today = day,
            tonightMics = TonightRules.micsOn(day.dayOfWeek, activeMics),
            tomorrowMics = TonightRules.micsOn(day.plusDays(1).dayOfWeek, activeMics),
            totalActive = activeMics.size,
            neighborhoodCount = TonightRules.neighborhoodCount(activeMics),
            topBorough = Boroughs.mostCommon(activeMics.map { it.borough }),
            // Show whatever we have even if a later refresh failed.
            hasLoaded = hasLoaded || activeMics.isNotEmpty(),
            isRefreshing = isRefreshing,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightUiState(today = today()))

    /** Re-reads the device date (cheap; call when the screen appears). */
    fun syncToday() {
        val now = today()
        if (currentDay.value != now) currentDay.value = now
    }

    /** Pull-to-refresh: reload everything from Supabase. Errors land in [TonightUiState.error]. */
    fun refresh() {
        syncToday()
        viewModelScope.launch { repository.refreshAll() }
    }

    fun dismissError() = repository.clearError()
}

/**
 * The small pure calculations behind the Tonight screen (plain Kotlin, no
 * Android), kept separate so they're easy to read and test.
 */
object TonightRules {

    /** Active mics whose `day_of_week` is [day], sorted by start time (spec 3.5). */
    fun micsOn(day: DayOfWeek, activeMics: List<Mic>): List<Mic> =
        MicRules.sortedByStartTime(activeMics.filter { it.weekday == day })

    /** Distinct non-blank neighborhoods (the "Neighborhoods" quick stat). */
    fun neighborhoodCount(mics: List<Mic>): Int =
        mics.mapNotNull { TextRules.trimmedOrNull(it.neighborhood) }.distinct().size

    /** "1 mic tonight" / "5 mics tonight". */
    fun countText(count: Int, suffix: String): String =
        if (count == 1) "1 mic $suffix".trim() else "$count mics $suffix".trim()
}
