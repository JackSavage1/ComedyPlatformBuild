package com.comedyplatform.mictracker.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.PlanKey
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.rules.CostFilter
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.MicFilters
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.SignupDeadline
import com.comedyplatform.mictracker.core.rules.SignupFilter
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

/** One chip of the Mon–Sun day strip. */
data class CalendarDay(
    val date: LocalDate,
    /** Filtered mics on this weekday. */
    val count: Int,
    /** Only possible in the current week. */
    val isToday: Boolean,
    val isPast: Boolean,
)

/** One mic card in the selected day's list. */
data class CalendarMicItem(
    val mic: Mic,
    /** Going / cancelled for the selected date, or null. */
    val planStatus: PlanStatus?,
    /** Rule 7 for the selected date, or null when the mic has no advance signup. */
    val deadline: SignupDeadline?,
)

data class CalendarUiState(
    val today: LocalDate,
    /** −1 (last week) … +3 (three weeks out). */
    val weekOffset: Int = 0,
    val monday: LocalDate = DateRules.weekStart(today),
    val days: List<CalendarDay> = emptyList(),
    val selectedDate: LocalDate = today,
    val dayMics: List<CalendarMicItem> = emptyList(),
    val filters: MicFilters = MicFilters(),
    /** Neighborhood choices for the filter sheet (limited to the selected boroughs). */
    val neighborhoodOptions: List<String> = emptyList(),
    /** "Showing X of Y mics": X = filtered active mics (whole week), Y = all active mics. */
    val shownCount: Int = 0,
    val totalCount: Int = 0,
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    /** Plan actions in flight (their cards show a spinner). */
    val busyKeys: Set<PlanKey> = emptySet(),
    val error: String? = null,
    /** One-off confirmation for a snackbar ("✅ Going: …"). */
    val message: String? = null,
) {
    /** "Last Week" / "This Week" / "Next Week" / "N Weeks Out". */
    val weekTitle: String get() = DateRules.weekTitle(weekOffset)

    /** "Oct 20 – Oct 26, 2026". */
    val weekRangeText: String get() = DateRules.weekRangeText(monday)

    val canGoBack: Boolean get() = weekOffset > DateRules.MIN_WEEK_OFFSET
    val canGoForward: Boolean get() = weekOffset < DateRules.MAX_WEEK_OFFSET
}

/**
 * State and actions for the Calendar tab (spec 6.3, a port of the web's
 * Weekly Calendar page). Mics, sets and plans come from the shared
 * [MicRepository]; week, selected day and filters live here.
 */
class CalendarViewModel(
    private val repository: MicRepository,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** Week offset + the day the user tapped (null = default day). */
    private data class Navigation(val weekOffset: Int = 0, val selectedDate: LocalDate? = null)

    /** Things only this screen knows about. */
    private data class LocalState(
        val busyKeys: Set<PlanKey> = emptySet(),
        val error: String? = null,
        val message: String? = null,
    )

    /** The screen's own inputs, bundled so they fit in one combine(). */
    private data class Inputs(
        val navigation: Navigation,
        val filters: MicFilters,
        val local: LocalState,
        val today: LocalDate,
        val isRefreshing: Boolean,
    )

    private val navigation = MutableStateFlow(Navigation())
    private val filters = MutableStateFlow(MicFilters())
    private val local = MutableStateFlow(LocalState())
    private val currentDay = MutableStateFlow(today())

    private val inputs = combine(navigation, filters, local, currentDay, repository.isRefreshing) { nav, f, l, day, refreshing ->
        Inputs(nav, f, l, day, refreshing)
    }

    private val snapshots = combine(
        repository.activeMics,
        repository.loggedVisitCounts,
        repository.planLookup,
        repository.hasLoaded,
        repository.lastError,
    ) { activeMics, visitCounts, planLookup, hasLoaded, repoError ->
        RepoSnapshot(activeMics, visitCounts.keys, planLookup, hasLoaded || activeMics.isNotEmpty(), repoError)
    }

    val state: StateFlow<CalendarUiState> = combine(snapshots, inputs) { snapshot, input -> buildState(snapshot, input) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState(today = today()))

    /** What the shared repository currently holds. */
    private data class RepoSnapshot(
        val activeMics: List<Mic>,
        /** Rule 6: mics with ≥1 LOGGED set. */
        val visitedMicIds: Set<Int>,
        val planLookup: Map<PlanKey, PlanStatus>,
        val hasLoaded: Boolean,
        val error: String?,
    )

    private fun buildState(snapshot: RepoSnapshot, input: Inputs): CalendarUiState {
        val today = input.today
        val offset = input.navigation.weekOffset
        val monday = DateRules.mondayForOffset(today, offset)
        val weekDates = DateRules.weekDates(monday)
        val sunday = weekDates.last()

        // Default day: today in the current week, else Monday.
        val selected = input.navigation.selectedDate?.takeIf { it in weekDates }
            ?: if (today in weekDates) today else monday

        // "Only going this week" = going on any date of the week being shown.
        val goingMicIds = snapshot.planLookup
            .filter { (key, status) -> status == PlanStatus.GOING && key.date >= monday && key.date <= sunday }
            .keys
            .map { it.micId }
            .toSet()

        val filtered = input.filters.apply(snapshot.activeMics, snapshot.visitedMicIds, goingMicIds)

        val days = weekDates.map { date ->
            CalendarDay(
                date = date,
                count = filtered.count { it.weekday == date.dayOfWeek },
                isToday = date == today,
                isPast = date.isBefore(today),
            )
        }

        val dayMics = MicRules.sortedByStartTime(filtered.filter { it.weekday == selected.dayOfWeek })
            .map { mic ->
                CalendarMicItem(
                    mic = mic,
                    planStatus = snapshot.planLookup[PlanKey(mic.id, selected)],
                    deadline = mic.signupDeadline(selected, today),
                )
            }

        return CalendarUiState(
            today = today,
            weekOffset = offset,
            monday = monday,
            days = days,
            selectedDate = selected,
            dayMics = dayMics,
            filters = input.filters,
            neighborhoodOptions = MicFilters.neighborhoodOptions(snapshot.activeMics, input.filters.boroughs),
            shownCount = filtered.size,
            totalCount = snapshot.activeMics.size,
            hasLoaded = snapshot.hasLoaded,
            isRefreshing = input.isRefreshing,
            busyKeys = input.local.busyKeys,
            error = input.local.error ?: snapshot.error,
            message = input.local.message,
        )
    }

    // ------------------------------------------------------------------
    // Week + day navigation
    // ------------------------------------------------------------------

    fun previousWeek() = setWeekOffset(navigation.value.weekOffset - 1)

    fun nextWeek() = setWeekOffset(navigation.value.weekOffset + 1)

    /** The "Today" button: back to this week, today selected. */
    fun goToToday() = setWeekOffset(0)

    fun selectDay(date: LocalDate) = navigation.update { it.copy(selectedDate = date) }

    private fun setWeekOffset(offset: Int) {
        val clamped = offset.coerceIn(DateRules.MIN_WEEK_OFFSET, DateRules.MAX_WEEK_OFFSET)
        syncToday()
        // Changing weeks resets the selected day to the default (today / Monday).
        navigation.value = Navigation(weekOffset = clamped, selectedDate = null)
        ensurePlansLoaded(DateRules.mondayForOffset(currentDay.value, clamped))
    }

    /** Re-reads the device date (the app may stay open past midnight). */
    fun syncToday() {
        val now = today()
        if (currentDay.value != now) currentDay.value = now
    }

    // ------------------------------------------------------------------
    // Filters (spec 6.3 filter sheet)
    // ------------------------------------------------------------------

    fun toggleBorough(borough: String) = filters.update { current ->
        val boroughs = if (borough in current.boroughs) current.boroughs - borough else current.boroughs + borough
        // Drop chosen neighborhoods that the new borough selection no longer offers,
        // so a hidden choice can't silently filter everything out.
        val stillOffered = MicFilters.neighborhoodOptions(repository.activeMics.value, boroughs).toSet()
        current.copy(boroughs = boroughs, neighborhoods = current.neighborhoods.filter { it in stillOffered }.toSet())
    }

    fun toggleNeighborhood(neighborhood: String) = filters.update { current ->
        val updated = if (neighborhood in current.neighborhoods) {
            current.neighborhoods - neighborhood
        } else {
            current.neighborhoods + neighborhood
        }
        current.copy(neighborhoods = updated)
    }

    fun setCostFilter(cost: CostFilter) = filters.update { it.copy(cost = cost) }

    fun setSignupFilter(signup: SignupFilter) = filters.update { it.copy(signup = signup) }

    fun setOnlyUnvisited(value: Boolean) = filters.update { it.copy(onlyUnvisited = value) }

    fun setOnlyGoing(value: Boolean) = filters.update { it.copy(onlyGoing = value) }

    fun resetFilters() {
        filters.value = MicFilters()
    }

    // ------------------------------------------------------------------
    // Going / Cancelled / Clear (swipe + long-press on a card)
    // ------------------------------------------------------------------

    fun markGoing(mic: Mic, date: LocalDate) = planAction(mic, date, "✅ Going: ${mic.displayName} (${dayLabel(date)})") {
        repository.setPlanStatus(mic.id, date, PlanStatus.GOING)
    }

    fun markCancelled(mic: Mic, date: LocalDate) = planAction(mic, date, "❌ Cancelled: ${mic.displayName} (${dayLabel(date)})") {
        repository.setPlanStatus(mic.id, date, PlanStatus.CANCELLED)
    }

    fun clearPlan(mic: Mic, date: LocalDate) = planAction(mic, date, "Cleared plan for ${mic.displayName} (${dayLabel(date)})") {
        repository.clearPlan(mic.id, date)
    }

    private fun planAction(mic: Mic, date: LocalDate, successMessage: String, block: suspend () -> Unit) {
        val key = PlanKey(mic.id, date)
        if (key in local.value.busyKeys) return // ignore double swipes
        viewModelScope.launch {
            local.update { it.copy(busyKeys = it.busyKeys + key, error = null) }
            try {
                block()
                local.update { it.copy(message = successMessage) }
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update { it.copy(busyKeys = it.busyKeys - key) }
            }
        }
    }

    private fun dayLabel(date: LocalDate): String = "${DateRules.shortWeekday(date)} ${DateRules.monthDay(date)}"

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Pull-to-refresh: reload mics, sets and plans. */
    fun refresh() {
        syncToday()
        viewModelScope.launch {
            repository.refreshAll()
            // refreshAll covers last week … 3 weeks ahead; make sure the shown week is loaded too.
            ensurePlansLoaded(DateRules.mondayForOffset(currentDay.value, navigation.value.weekOffset))
        }
    }

    /**
     * The repository loads plans for last week … 3 weeks ahead on every
     * refresh. Only a week outside that window (possible if the app stayed
     * open across a Monday) needs its own request.
     */
    private fun ensurePlansLoaded(monday: LocalDate) {
        val window = repository.defaultPlanWindow()
        val sunday = monday.plusDays(6)
        if (!monday.isBefore(window.start) && !sunday.isAfter(window.endInclusive)) return
        viewModelScope.launch {
            try {
                repository.loadPlans(monday, sunday)
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
        repository.clearError()
    }

    fun messageShown() = local.update { it.copy(message = null) }
}
