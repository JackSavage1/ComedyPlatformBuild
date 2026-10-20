package com.comedyplatform.mictracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.data.MicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StatsUiState(
    /** null until the first calculation finishes. */
    val data: StatsCalculations.StatsData? = null,
    /** False until the first successful load (show a spinner). */
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Turns the shared [MicRepository]'s sets and mics into [StatsCalculations.StatsData].
 * Recomputes whenever a set is logged, edited or deleted anywhere in the app.
 */
class StatsViewModel(
    private val repository: MicRepository,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** The (small, but not free) aggregation runs off the main thread. */
    private val data = combine(repository.setsWithMics, repository.activeMics) { sets, activeMics ->
        StatsCalculations.compute(sets, activeMics.size, today())
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<StatsUiState> = combine(
        data,
        repository.hasLoaded,
        repository.isRefreshing,
        repository.lastError,
    ) { stats, hasLoaded, isRefreshing, error ->
        StatsUiState(
            data = stats,
            hasLoaded = hasLoaded || !stats.isEmpty,
            isRefreshing = isRefreshing,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** Pull-to-refresh. Errors show via the repository's lastError. */
    fun refresh() {
        viewModelScope.launch { repository.refreshAll() }
    }

    fun dismissError() = repository.clearError()
}
