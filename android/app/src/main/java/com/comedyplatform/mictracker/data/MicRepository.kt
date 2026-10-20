package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicDraft
import com.comedyplatform.mictracker.core.model.MicPlan
import com.comedyplatform.mictracker.core.model.Payloads
import com.comedyplatform.mictracker.core.model.PlanKey
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.Rpc
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.model.SetDraft
import com.comedyplatform.mictracker.core.model.SetEntry
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.core.model.Tables
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.SetRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * The single shared source of truth for the app's data (spec 3.5).
 *
 * Every screen reads the StateFlows below and calls the suspend functions to
 * change data. There is exactly one instance, created by AppContainer.
 *
 * READS
 *   - [mics]: ALL open_mics rows, including inactive ones (set history needs
 *     the names of deactivated mics). Use [activeMics] for lists of mics.
 *   - [sets]: all my_sets rows, newest first (date desc, id desc).
 *   - [plans]: mic_plans rows for every date window loaded so far. [refreshAll]
 *     loads last week through 3 weeks ahead (covers Calendar, Tonight and the
 *     Map's "next occurrence"); [loadPlans] adds any other window.
 *   - [scraped] / [scrapeLog]: Manage screen data, loaded on demand.
 *   - [isRefreshing] / [lastError] / [hasLoaded]: state of [refreshAll].
 *
 * ERRORS
 *   - [refreshAll] never throws; it records the message in [lastError] and
 *     returns false.
 *   - Every other function throws [ApiException] (message safe to show) when
 *     the server rejects the request or the network is down. After a
 *     successful change it refreshes the affected data; a failure of that
 *     follow-up refresh only sets [lastError] (the change itself succeeded).
 */
class MicRepository(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val _mics = MutableStateFlow<List<Mic>>(emptyList())
    private val _sets = MutableStateFlow<List<SetEntry>>(emptyList())
    private val _plans = MutableStateFlow<List<MicPlan>>(emptyList())
    private val _scraped = MutableStateFlow<List<ScrapedMic>>(emptyList())
    private val _scrapeLog = MutableStateFlow<List<ScrapeLogEntry>>(emptyList())
    private val _isRefreshing = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _hasLoaded = MutableStateFlow(false)

    /** Every mic, active or not, sorted Mon→Sun then by start time. */
    val mics: StateFlow<List<Mic>> = _mics.asStateFlow()

    /** All sets (logged + planned), newest first. */
    val sets: StateFlow<List<SetEntry>> = _sets.asStateFlow()

    /** Plans for all loaded date windows. */
    val plans: StateFlow<List<MicPlan>> = _plans.asStateFlow()

    /** Pending scraped mics (Manage → Import). Call [loadScraped] first. */
    val scraped: StateFlow<List<ScrapedMic>> = _scraped.asStateFlow()

    /** Scrape history, newest first (Manage → Scrape History). Call [loadScrapeLog] first. */
    val scrapeLog: StateFlow<List<ScrapeLogEntry>> = _scrapeLog.asStateFlow()

    /** True while [refreshAll] runs (drive pull-to-refresh indicators with it). */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** The last load/refresh error message, or null. Clear with [clearError]. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** True once the first [refreshAll] has succeeded. */
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    // ---- Derived flows ---------------------------------------------------

    /** id → mic for every mic (including inactive). */
    val micsById: StateFlow<Map<Int, Mic>> = _mics
        .map { list -> list.associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Spec 3.5: "active mics" = `is_active != false`, sorted Mon→Sun then by time. */
    val activeMics: StateFlow<List<Mic>> = _mics
        .map { list -> list.filter { it.isActiveMic } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** (mic, date) → going/cancelled, for every loaded plan. */
    val planLookup: StateFlow<Map<PlanKey, PlanStatus>> = _plans
        .map { list -> buildPlanLookup(list) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Rule 6: mic id → number of LOGGED sets (planned rows don't count). */
    val loggedVisitCounts: StateFlow<Map<Int, Int>> = _sets
        .map { list -> SetRules.loggedVisitCounts(list) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Sets joined with their mics (spec 3.5), newest first. */
    val setsWithMics: StateFlow<List<SetWithMic>> = combine(_sets, micsById) { sets, byId ->
        sets.map { set -> SetWithMic(set, set.openMicId?.let { byId[it] }) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ---- Snapshot helpers --------------------------------------------------

    fun mic(id: Int): Mic? = micsById.value[id] ?: _mics.value.firstOrNull { it.id == id }

    fun planStatus(micId: Int, date: LocalDate): PlanStatus? = planLookup.value[PlanKey(micId, date)]

    fun loggedVisitCount(micId: Int): Int = loggedVisitCounts.value[micId] ?: 0

    fun clearError() {
        _lastError.value = null
    }

    /** Forget everything (called on sign-out). */
    fun clear() {
        _mics.value = emptyList()
        _sets.value = emptyList()
        _plans.value = emptyList()
        _scraped.value = emptyList()
        _scrapeLog.value = emptyList()
        _lastError.value = null
        _hasLoaded.value = false
        _isRefreshing.value = false
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private val refreshMutex = Mutex()

    /**
     * Reloads mics, sets and the default plan window (last week … 3 weeks
     * ahead) in parallel. Never throws: returns false and sets [lastError] on
     * failure. Use for first load and pull-to-refresh.
     */
    suspend fun refreshAll(): Boolean = refreshMutex.withLock {
        _isRefreshing.value = true
        try {
            val window = defaultPlanWindow()
            coroutineScope {
                val micsJob = async { fetchMics() }
                val setsJob = async { fetchSets() }
                val plansJob = async { fetchPlans(window.start, window.endInclusive) }
                val mics = micsJob.await()
                val sets = setsJob.await()
                val plans = plansJob.await()
                _mics.value = mics
                _sets.value = sets
                mergePlans(window.start, window.endInclusive, plans)
            }
            _lastError.value = null
            _hasLoaded.value = true
            true
        } catch (e: ApiException) {
            _lastError.value = e.userMessage
            false
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Reloads all mics. Throws [ApiException]. */
    suspend fun refreshMics() {
        _mics.value = fetchMics()
    }

    /** Reloads all sets. Throws [ApiException]. */
    suspend fun refreshSets() {
        _sets.value = fetchSets()
    }

    /** Loads plans for [from]..[to] (inclusive) and merges them into [plans]. Throws [ApiException]. */
    suspend fun loadPlans(from: LocalDate, to: LocalDate) {
        val start = if (from <= to) from else to
        val end = if (from <= to) to else from
        mergePlans(start, end, fetchPlans(start, end))
    }

    /** Pending scraped mics, ordered by source, then Mon→Sun, then name. Throws [ApiException]. */
    suspend fun loadScraped() {
        _scraped.value = api.selectList(
            Tables.SCRAPED_MICS,
            ScrapedMic.serializer(),
            listOf("status" to "eq.pending", "order" to "source.asc,id.asc"),
        ).sortedWith(
            compareBy<ScrapedMic>(
                { it.source ?: "" },
                { Weekdays.sortIndex(it.dayOfWeek) },
                { it.displayName.lowercase() },
            ),
        )
    }

    /** Scrape history, newest first. Throws [ApiException]. */
    suspend fun loadScrapeLog() {
        _scrapeLog.value = api.selectList(
            Tables.SCRAPE_LOG,
            ScrapeLogEntry.serializer(),
            listOf("order" to "last_scraped.desc.nullslast,id.desc"),
        )
    }

    // ------------------------------------------------------------------
    // Plans (Going / Cancelled / Clear)
    // ------------------------------------------------------------------

    /**
     * RPC set_plan_status. "Going" also makes the server create a skeletal
     * (planned) set for that mic and date, so sets are reloaded too.
     */
    suspend fun setPlanStatus(micId: Int, date: LocalDate, status: PlanStatus) {
        api.rpc(Rpc.SET_PLAN_STATUS, Rpc.setPlanStatusArgs(micId, date, status))
        // Show the change immediately, then confirm with the server.
        _plans.update { list ->
            list.filterNot { it.openMicId == micId && it.planDate == date } +
                MicPlan(id = -1, openMicId = micId, planDate = date, status = status.raw)
        }
        refreshQuietly {
            loadPlans(date, date)
            if (status == PlanStatus.GOING) refreshSets()
        }
    }

    /** RPC clear_plan. The skeletal set (if any) is left alone, like the web app. */
    suspend fun clearPlan(micId: Int, date: LocalDate) {
        api.rpc(Rpc.CLEAR_PLAN, Rpc.clearPlanArgs(micId, date))
        _plans.update { list -> list.filterNot { it.openMicId == micId && it.planDate == date } }
        refreshQuietly { loadPlans(date, date) }
    }

    // ------------------------------------------------------------------
    // Mics
    // ------------------------------------------------------------------

    /** POST open_mics (null keys omitted, is_active true). Returns the new mic. */
    suspend fun addMic(draft: MicDraft): Mic {
        val created = CoreJson.decodeFirst(api.insert(Tables.OPEN_MICS, draft.toInsertPayload()), Mic.serializer())
            ?: throw ApiException("The mic wasn't saved. Check that this account is an app admin.")
        replaceMicLocally(created)
        refreshQuietly { refreshMics() }
        return created
    }

    /**
     * PATCH every editable field (explicit nulls for cleared ones).
     * Pass [includeCoordinates] = true when the address changed and was
     * re-geocoded (draft.latitude/longitude may be null to clear the pin).
     */
    suspend fun updateMic(micId: Int, draft: MicDraft, includeCoordinates: Boolean = false): Mic {
        val updated = patchMic(micId, draft.toUpdatePayload(includeCoordinates))
        refreshQuietly { refreshMics() }
        return updated
    }

    /** PATCH mic_rating (0–10, step 0.5). Pass null (or 0) to mark "Not rated". */
    suspend fun updateMicRating(micId: Int, rating: Double?): Mic {
        val value = rating?.takeIf { it > 0.0 }?.coerceAtMost(10.0)
        return patchMic(micId, Payloads.micRating(value))
    }

    /** PATCH latitude/longitude (Manage → Geocoding). Updates the local list without a full reload. */
    suspend fun updateCoordinates(micId: Int, latitude: Double?, longitude: Double?): Mic =
        patchMic(micId, Payloads.coordinates(latitude, longitude))

    /** RPC delete_mic: soft delete (is_active = false) and remove its plans. */
    suspend fun deleteMic(micId: Int) {
        api.rpc(Rpc.DELETE_MIC, Rpc.deleteMicArgs(micId))
        _mics.update { list -> list.map { if (it.id == micId) it.copy(isActive = false) else it } }
        _plans.update { list -> list.filterNot { it.openMicId == micId } }
        refreshQuietly { refreshMics() }
    }

    // ------------------------------------------------------------------
    // Sets
    // ------------------------------------------------------------------

    /** POST my_sets (null keys omitted). Returns the new row. */
    suspend fun logSet(draft: SetDraft): SetEntry {
        val created = CoreJson.decodeFirst(api.insert(Tables.MY_SETS, draft.toInsertPayload()), SetEntry.serializer())
            ?: throw ApiException("The set wasn't saved. Check that this account is an app admin.")
        // filterNot first: a refresh running at the same time may already have
        // fetched this row, and a duplicate id would crash the keyed History list.
        _sets.update { list -> sortSets(list.filterNot { it.id == created.id } + created) }
        refreshQuietly { refreshSets() }
        return created
    }

    /** PATCH my_sets?id=eq.X with every field (explicit nulls). Used for planned rows and edits. */
    suspend fun updateSet(setId: Int, draft: SetDraft): SetEntry {
        val updated = CoreJson.decodeFirst(
            api.update(Tables.MY_SETS, listOf("id" to "eq.$setId"), draft.toUpdatePayload()),
            SetEntry.serializer(),
        ) ?: throw ApiException("That set no longer exists (or this account can't edit it).")
        _sets.update { list -> sortSets(list.map { if (it.id == setId) updated else it }) }
        refreshQuietly { refreshSets() }
        return updated
    }

    /** DELETE my_sets?id=eq.X (Set Detail → Delete). */
    suspend fun deleteSet(setId: Int) {
        api.delete(Tables.MY_SETS, listOf("id" to "eq.$setId"))
        _sets.update { list -> list.filterNot { it.id == setId } }
        refreshQuietly { refreshSets() }
    }

    /**
     * RPC delete_planned_set: deletes the row only if it has no rating, plus
     * the matching plan. Returns whether something was deleted.
     */
    suspend fun deletePlannedSet(setId: Int): Boolean {
        val planned = _sets.value.firstOrNull { it.id == setId }
        val deleted = JsonValues.boolean(api.rpc(Rpc.DELETE_PLANNED_SET, Rpc.deletePlannedSetArgs(setId))) == true
        if (deleted) {
            _sets.update { list -> list.filterNot { it.id == setId } }
            val micId = planned?.openMicId
            val date = planned?.datePerformed
            if (micId != null && date != null) {
                _plans.update { list -> list.filterNot { it.openMicId == micId && it.planDate == date } }
            }
        }
        refreshQuietly {
            refreshSets()
            val date = planned?.datePerformed
            if (date != null) loadPlans(date, date)
        }
        return deleted
    }

    // ------------------------------------------------------------------
    // Scraper review queue
    // ------------------------------------------------------------------

    /** RPC import_scraped_mic. Returns the new open_mics id (null if the server returned none). */
    suspend fun importScraped(scrapedId: Long): Int? {
        val result = api.rpc(Rpc.IMPORT_SCRAPED_MIC, Rpc.importScrapedMicArgs(scrapedId))
        _scraped.update { list -> list.filterNot { it.id == scrapedId } }
        refreshQuietly {
            refreshMics()
            loadScraped()
        }
        return JsonValues.int(result)
    }

    /** RPC dismiss_scraped_mic. */
    suspend fun dismissScraped(scrapedId: Long) {
        api.rpc(Rpc.DISMISS_SCRAPED_MIC, Rpc.dismissScrapedMicArgs(scrapedId))
        _scraped.update { list -> list.filterNot { it.id == scrapedId } }
        refreshQuietly { loadScraped() }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Last week's Monday through the Sunday 3 weeks ahead (Calendar offsets −1…+3). */
    fun defaultPlanWindow(): ClosedRange<LocalDate> {
        val monday = DateRules.weekStart(today())
        return monday.minusWeeks(1)..monday.plusWeeks(4).minusDays(1)
    }

    private suspend fun fetchMics(): List<Mic> =
        api.selectList(Tables.OPEN_MICS, Mic.serializer()).sortedWith(MicRules.byDayThenTime)

    private suspend fun fetchSets(): List<SetEntry> =
        api.selectList(
            Tables.MY_SETS,
            SetEntry.serializer(),
            listOf("order" to "date_performed.desc,id.desc"),
        )

    private suspend fun fetchPlans(from: LocalDate, to: LocalDate): List<MicPlan> =
        api.selectList(
            Tables.MIC_PLANS,
            MicPlan.serializer(),
            listOf(
                "plan_date" to "gte.$from",
                "plan_date" to "lte.$to",
                "order" to "plan_date.asc,id.asc",
            ),
        )

    /** Replace every plan dated inside [from]..[to] with [fetched]. */
    private fun mergePlans(from: LocalDate, to: LocalDate, fetched: List<MicPlan>) {
        _plans.update { list ->
            list.filterNot { plan -> plan.planDate?.let { it in from..to } ?: true } + fetched
        }
    }

    private suspend fun patchMic(micId: Int, body: JsonObject): Mic {
        val updated = CoreJson.decodeFirst(
            api.update(Tables.OPEN_MICS, listOf("id" to "eq.$micId"), body),
            Mic.serializer(),
        ) ?: throw ApiException("That mic no longer exists (or this account can't edit it).")
        replaceMicLocally(updated)
        return updated
    }

    private fun replaceMicLocally(mic: Mic) {
        _mics.update { list ->
            val replaced = if (list.any { it.id == mic.id }) list.map { if (it.id == mic.id) mic else it } else list + mic
            replaced.sortedWith(MicRules.byDayThenTime)
        }
    }

    private fun sortSets(list: List<SetEntry>): List<SetEntry> =
        list.sortedWith(
            compareByDescending<SetEntry> { it.datePerformed ?: LocalDate.MIN }.thenByDescending { it.id },
        )

    private fun buildPlanLookup(list: List<MicPlan>): Map<PlanKey, PlanStatus> {
        val map = HashMap<PlanKey, PlanStatus>()
        for (plan in list) {
            val micId = plan.openMicId ?: continue
            val date = plan.planDate ?: continue
            val status = plan.planStatus ?: continue
            map[PlanKey(micId, date)] = status
        }
        return map
    }

    /** Runs a follow-up refresh after a successful change; failures only set [lastError]. */
    private suspend fun refreshQuietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            _lastError.value = e.userMessage
        }
    }
}
