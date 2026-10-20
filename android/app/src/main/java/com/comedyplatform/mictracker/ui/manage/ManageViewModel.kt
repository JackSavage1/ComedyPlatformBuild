package com.comedyplatform.mictracker.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.data.ApiException
import com.comedyplatform.mictracker.data.GeocodingService
import com.comedyplatform.mictracker.data.MicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sub-pages of Manage (Add a Mic and Account live directly on the menu). */
enum class ManageSection(val title: String) {
    IMPORT("📥 Import from Scrapers"),
    HISTORY("📋 Scrape History"),
    MICS("🔧 Manage Mics"),
    GEOCODING("📍 Geocoding"),
}

/** Progress of "Geocode with device". */
data class GeocodeProgress(
    val isRunning: Boolean = false,
    /** Lookups finished so far. */
    val done: Int = 0,
    /** Mics being looked up (missing coordinates and have an address). */
    val total: Int = 0,
    val found: Int = 0,
    /** The mic being looked up right now. */
    val currentName: String? = null,
    /** "Geocoded N of M" once finished (or stopped). */
    val resultText: String? = null,
) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** "Add all" progress for one source. */
data class AddAllProgress(val sourceKey: String, val done: Int, val total: Int)

/** A one-off snackbar message; [id] makes repeated identical messages show again. */
data class ManageMessage(val text: String, val id: Long = System.nanoTime())

data class ManageUiState(
    val email: String? = null,
    val activeMics: List<Mic> = emptyList(),
    // ---- Import --------------------------------------------------------------
    val scrapedGroups: List<ManageRules.SourceGroup> = emptyList(),
    val scrapedCount: Int = 0,
    /** True once the review queue and scrape log have been fetched at least once. */
    val reviewLoaded: Boolean = false,
    val isLoadingReview: Boolean = false,
    /** True when the last attempt to load the review queue failed. */
    val reviewFailed: Boolean = false,
    /** Scraped rows whose Add / Dismiss is working. */
    val busyScrapedIds: Set<Long> = emptySet(),
    val addAll: AddAllProgress? = null,
    // ---- Scrape history -------------------------------------------------------
    val scrapeLog: List<ScrapeLogEntry> = emptyList(),
    // ---- Manage mics ------------------------------------------------------------
    val searchQuery: String = "",
    val searchResults: List<Mic> = emptyList(),
    // ---- Geocoding ------------------------------------------------------------------
    val coordinatesSummary: String = "",
    val missingCoordinates: List<Mic> = emptyList(),
    /** Missing mics that have an address (what "Geocode with device" will try). */
    val geocodableCount: Int = 0,
    val geocoderAvailable: Boolean = true,
    val geocode: GeocodeProgress = GeocodeProgress(),
    // ---- Account / general --------------------------------------------------------------
    val isSigningOut: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val message: ManageMessage? = null,
) {
    /** The latest scrape-log row (for the menu's subtitle). */
    val lastScrape: ScrapeLogEntry? get() = scrapeLog.firstOrNull()

    /** True while any Add / Dismiss / Add all is running (one change at a time keeps things simple). */
    val isImporting: Boolean get() = busyScrapedIds.isNotEmpty() || addAll != null
}

/**
 * State and actions for Manage (spec 6.8): the scraper review queue, scrape
 * history, the searchable mic list, device geocoding and sign-out.
 *
 * @param email the signed-in account's email (from the auth session)
 * @param performSignOut signs out; runs in the app-wide scope so it finishes even
 *   though this screen disappears as soon as the session is cleared
 */
class ManageViewModel(
    private val repository: MicRepository,
    private val geocoder: GeocodingService,
    email: Flow<String?>,
    private val performSignOut: () -> Unit,
) : ViewModel() {

    /** Things only this screen knows about. */
    private data class LocalState(
        val reviewLoaded: Boolean = false,
        val isLoadingReview: Boolean = false,
        val reviewFailed: Boolean = false,
        val busyScrapedIds: Set<Long> = emptySet(),
        val addAll: AddAllProgress? = null,
        val searchQuery: String = "",
        val geocode: GeocodeProgress = GeocodeProgress(),
        val isSigningOut: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val message: ManageMessage? = null,
    )

    private val local = MutableStateFlow(LocalState())
    private var geocodeJob: Job? = null

    val state: StateFlow<ManageUiState> = combine(
        repository.activeMics,
        repository.scraped,
        repository.scrapeLog,
        email,
        local,
    ) { activeMics, scraped, scrapeLog, accountEmail, ui ->
        val missing = ManageRules.missingCoordinates(activeMics)
        ManageUiState(
            email = accountEmail,
            activeMics = activeMics,
            scrapedGroups = ManageRules.groupBySource(scraped),
            scrapedCount = scraped.size,
            reviewLoaded = ui.reviewLoaded,
            isLoadingReview = ui.isLoadingReview,
            reviewFailed = ui.reviewFailed,
            busyScrapedIds = ui.busyScrapedIds,
            addAll = ui.addAll,
            scrapeLog = scrapeLog,
            searchQuery = ui.searchQuery,
            searchResults = ManageRules.searchMics(activeMics, ui.searchQuery),
            coordinatesSummary = ManageRules.coordinatesSummary(activeMics),
            missingCoordinates = missing,
            geocodableCount = ManageRules.geocodable(missing).size,
            geocoderAvailable = geocoder.isAvailable,
            geocode = ui.geocode,
            isSigningOut = ui.isSigningOut,
            isRefreshing = ui.isRefreshing,
            error = ui.error,
            message = ui.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    init {
        loadReview()
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Fetches the review queue and the scrape log (both admin-only tables). */
    fun loadReview() {
        if (local.value.isLoadingReview) return
        viewModelScope.launch {
            local.update { it.copy(isLoadingReview = true) }
            val error = fetchReview()
            local.update {
                it.copy(
                    isLoadingReview = false,
                    reviewLoaded = true,
                    reviewFailed = error != null,
                    error = error ?: it.error,
                )
            }
        }
    }

    /** Pull-to-refresh: mics, sets, plans, the review queue and the scrape log. */
    fun refresh() {
        if (local.value.isRefreshing) return
        viewModelScope.launch {
            local.update { it.copy(isRefreshing = true, error = null) }
            val ok = repository.refreshAll()
            val reviewError = fetchReview()
            val error = reviewError ?: if (ok) null else repository.lastError.value
            local.update {
                it.copy(isRefreshing = false, reviewLoaded = true, reviewFailed = reviewError != null, error = error)
            }
        }
    }

    /** Loads both lists in parallel; returns an error message or null. */
    private suspend fun fetchReview(): String? = try {
        coroutineScope {
            val scraped = async { repository.loadScraped() }
            val log = async { repository.loadScrapeLog() }
            scraped.await()
            log.await()
        }
        null
    } catch (e: ApiException) {
        e.userMessage
    }

    fun dismissError() = local.update { it.copy(error = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    // ------------------------------------------------------------------
    // Import from Scrapers
    // ------------------------------------------------------------------

    /** Add → RPC import_scraped_mic (copies the row into open_mics). */
    fun importOne(mic: ScrapedMic) = scrapedAction(mic) {
        repository.importScraped(mic.id)
        ManageRules.addedMessage(mic)
    }

    /** Dismiss → RPC dismiss_scraped_mic. */
    fun dismissOne(mic: ScrapedMic) = scrapedAction(mic) {
        repository.dismissScraped(mic.id)
        "Dismissed ${mic.displayName}"
    }

    /** Add all pending mics from one source, one at a time; stops at the first error. */
    fun addAll(group: ManageRules.SourceGroup) {
        if (local.value.addAll != null || local.value.busyScrapedIds.isNotEmpty()) return
        val targets = group.mics.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            local.update { it.copy(addAll = AddAllProgress(group.key, 0, targets.size), error = null) }
            var added = 0
            try {
                for (mic in targets) {
                    repository.importScraped(mic.id)
                    added += 1
                    local.update { it.copy(addAll = AddAllProgress(group.key, added, targets.size)) }
                }
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update {
                    it.copy(
                        addAll = null,
                        message = if (added > 0) ManageMessage(ManageRules.addAllMessage(added, group.title)) else it.message,
                    )
                }
            }
        }
    }

    private fun scrapedAction(mic: ScrapedMic, block: suspend () -> String) {
        val current = local.value
        if (current.addAll != null || mic.id in current.busyScrapedIds) return
        viewModelScope.launch {
            local.update { it.copy(busyScrapedIds = it.busyScrapedIds + mic.id, error = null) }
            try {
                val text = block()
                local.update { it.copy(message = ManageMessage(text)) }
            } catch (e: ApiException) {
                local.update { it.copy(error = e.userMessage) }
            } finally {
                local.update { it.copy(busyScrapedIds = it.busyScrapedIds - mic.id) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Manage Mics
    // ------------------------------------------------------------------

    fun setSearchQuery(query: String) = local.update { it.copy(searchQuery = query) }

    // ------------------------------------------------------------------
    // Geocoding
    // ------------------------------------------------------------------

    /**
     * "Geocode with device": for each active mic without coordinates that has
     * an address, ask the phone's geocoder (which waits ≥ 1 s between
     * requests and only accepts NYC results), then PATCH latitude/longitude.
     */
    fun geocodeMissing() {
        if (local.value.geocode.isRunning) return
        val targets = ManageRules.geocodable(ManageRules.missingCoordinates(repository.activeMics.value))
        if (targets.isEmpty()) {
            local.update { it.copy(geocode = GeocodeProgress(resultText = "Nothing to geocode — every mic with an address has coordinates.")) }
            return
        }
        geocodeJob = viewModelScope.launch {
            var done = 0
            var found = 0
            local.update { it.copy(geocode = GeocodeProgress(isRunning = true, total = targets.size), error = null) }
            try {
                for (mic in targets) {
                    local.update { it.copy(geocode = it.geocode.copy(currentName = mic.displayName)) }
                    val point = geocoder.geocodeMic(mic.address, mic.borough)
                    if (point != null) {
                        repository.updateCoordinates(mic.id, point.latitude, point.longitude)
                        found += 1
                    }
                    done += 1
                    local.update { it.copy(geocode = it.geocode.copy(done = done, found = found)) }
                }
                local.update {
                    it.copy(
                        geocode = GeocodeProgress(
                            done = done,
                            total = targets.size,
                            found = found,
                            resultText = ManageRules.geocodeResult(found, targets.size),
                        ),
                    )
                }
            } catch (e: ApiException) {
                local.update {
                    it.copy(
                        error = e.userMessage,
                        geocode = GeocodeProgress(
                            done = done,
                            total = targets.size,
                            found = found,
                            resultText = "Stopped after an error. " + ManageRules.geocodeResult(found, done),
                        ),
                    )
                }
            }
        }
    }

    /** "Stop" while geocoding. The mics already geocoded keep their coordinates. */
    fun stopGeocoding() {
        val job = geocodeJob ?: return
        val progress = local.value.geocode
        if (!progress.isRunning) return
        job.cancel()
        local.update {
            it.copy(
                geocode = GeocodeProgress(
                    done = progress.done,
                    total = progress.total,
                    found = progress.found,
                    resultText = "Stopped. " + ManageRules.geocodeResult(progress.found, progress.done),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    fun signOut() {
        if (local.value.isSigningOut) return
        geocodeJob?.cancel()
        local.update { it.copy(isSigningOut = true) }
        performSignOut()
    }
}
