package com.comedyplatform.mictracker.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.GeoPoint
import com.comedyplatform.mictracker.core.rules.MapPreset
import com.comedyplatform.mictracker.core.rules.MapStyle
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.data.MicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** One circle on the map: a mic with coordinates, colored by borough (rule 17). */
data class MapPin(
    val micId: Int,
    val latitude: Double,
    val longitude: Double,
    /** "#RRGGBB" — read by the circle layer's color expression. */
    val colorHex: String,
)

/** One legend row: "● Manhattan". */
data class LegendEntry(val label: String, val colorArgb: Long)

data class MapUiState(
    val today: LocalDate,
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** False when no active mic has coordinates (show the "use Geocoding" message). */
    val anyCoordinates: Boolean = false,
    /** Active mics with coordinates that pass the day/borough filters. */
    val visibleMics: List<Mic> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val selectedDays: Set<DayOfWeek> = emptySet(),
    val selectedBoroughs: Set<String> = emptySet(),
    val style: MapStyle = MapStyle.VOYAGER,
    /** The last zoom preset chosen (highlighted chip). */
    val preset: MapPreset = MapPreset.ALL_NYC,
    /** The mic whose card is open, or null. */
    val selectedMic: Mic? = null,
    /** Where the "My location" button last found the phone, or null. */
    val myLocation: GeoPoint? = null,
    /** The camera when the map was last on screen (restored when coming back). */
    val savedCamera: CameraTarget? = null,
    val neighborhoodCount: Int = 0,
    val boroughCount: Int = 0,
    val legend: List<LegendEntry> = MapScreenRules.legend(emptyList()),
    /** A one-off note for a snackbar (location problems). */
    val message: String? = null,
) {
    val filtersActive: Boolean get() = selectedDays.isNotEmpty() || selectedBoroughs.isNotEmpty()

    /** The selected mic's pin (for the highlight ring), or null. */
    val selectedPin: MapPin? get() = selectedMic?.let { mic -> pins.firstOrNull { it.micId == mic.id } }
}

/**
 * State for the Map tab (spec 6.5). Mics come from the shared
 * [MicRepository]; filters, style, preset, selection and the user's location
 * live here so they survive rotation.
 */
class MapViewModel(
    private val repository: MicRepository,
    private val today: () -> LocalDate,
) : ViewModel() {

    private data class Controls(
        val days: Set<DayOfWeek> = emptySet(),
        val boroughs: Set<String> = emptySet(),
        val style: MapStyle = MapStyle.VOYAGER,
        val preset: MapPreset = MapPreset.ALL_NYC,
        val selectedMicId: Int? = null,
        val myLocation: GeoPoint? = null,
        val savedCamera: CameraTarget? = null,
        val message: String? = null,
    )

    private val controls = MutableStateFlow(Controls())

    val state: StateFlow<MapUiState> = combine(
        repository.activeMics,
        repository.hasLoaded,
        repository.isRefreshing,
        repository.lastError,
        controls,
    ) { activeMics, hasLoaded, isRefreshing, error, c ->
        val mapped = MapScreenRules.withCoordinates(activeMics)
        val visible = MapScreenRules.filter(mapped, c.days, c.boroughs)
        MapUiState(
            today = today(),
            hasLoaded = hasLoaded || activeMics.isNotEmpty(),
            isRefreshing = isRefreshing,
            error = error,
            anyCoordinates = mapped.isNotEmpty(),
            visibleMics = visible,
            pins = MapScreenRules.pins(visible),
            selectedDays = c.days,
            selectedBoroughs = c.boroughs,
            style = c.style,
            preset = c.preset,
            selectedMic = c.selectedMicId?.let { id -> visible.firstOrNull { it.id == id } },
            myLocation = c.myLocation,
            savedCamera = c.savedCamera,
            neighborhoodCount = MapScreenRules.distinctCount(visible.map { it.neighborhood }),
            boroughCount = MapScreenRules.distinctCount(visible.map { it.borough }),
            legend = MapScreenRules.legend(visible),
            message = c.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState(today = today()))

    fun toggleDay(day: DayOfWeek) = controls.update { c ->
        c.copy(days = if (day in c.days) c.days - day else c.days + day)
    }

    fun toggleBorough(borough: String) = controls.update { c ->
        c.copy(boroughs = if (borough in c.boroughs) c.boroughs - borough else c.boroughs + borough)
    }

    fun clearFilters() = controls.update { it.copy(days = emptySet(), boroughs = emptySet()) }

    fun setStyle(style: MapStyle) = controls.update { it.copy(style = style) }

    /** Remembers the chosen preset (the screen moves the camera). */
    fun choosePreset(preset: MapPreset) = controls.update { it.copy(preset = preset) }

    /** A pin was tapped (or the map background: null closes the card). */
    fun selectMic(micId: Int?) = controls.update { it.copy(selectedMicId = micId) }

    fun setMyLocation(point: GeoPoint) = controls.update { it.copy(myLocation = point) }

    /** Called as the map leaves the screen, so coming back keeps the same view. */
    fun saveCamera(camera: CameraTarget) = controls.update { it.copy(savedCamera = camera) }

    fun showMessage(message: String) = controls.update { it.copy(message = message) }

    fun messageShown() = controls.update { it.copy(message = null) }

    fun refresh() {
        viewModelScope.launch { repository.refreshAll() }
    }

    fun dismissError() = repository.clearError()
}

/** The pure calculations behind the Map tab (plain Kotlin). */
object MapScreenRules {

    /** Spec 6.5: only active mics with both coordinates are mapped. */
    fun withCoordinates(activeMics: List<Mic>): List<Mic> = activeMics.filter { it.hasCoordinates }

    /** Day (multi) and borough (multi) filters; an empty set means "all". */
    fun filter(mics: List<Mic>, days: Set<DayOfWeek>, boroughs: Set<String>): List<Mic> =
        mics.filter { mic ->
            val dayOk = days.isEmpty() || mic.weekday in days
            val boroughOk = boroughs.isEmpty() || TextRules.trimmedOrNull(mic.borough) in boroughs
            dayOk && boroughOk
        }

    fun pins(mics: List<Mic>): List<MapPin> = mics.mapNotNull { mic ->
        val latitude = mic.latitude ?: return@mapNotNull null
        val longitude = mic.longitude ?: return@mapNotNull null
        MapPin(micId = mic.id, latitude = latitude, longitude = longitude, colorHex = hexColor(mic.boroughColorArgb))
    }

    /** 0xFFFF6347 → "#FF6347". */
    fun hexColor(argb: Long): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFFL)

    /** Distinct non-blank values ("Neighborhoods" / "Boroughs" stats). */
    fun distinctCount(values: List<String?>): Int = values.mapNotNull { TextRules.trimmedOrNull(it) }.distinct().size

    /** The five boroughs, plus "Other" when a visible mic has another (or no) borough. */
    fun legend(visible: List<Mic>): List<LegendEntry> {
        val known = Boroughs.all.map { it.lowercase() }.toSet()
        val entries = Boroughs.all.map { LegendEntry(it, Boroughs.colorArgb(it)) }
        val hasOther = visible.any { mic -> TextRules.trimmedOrNull(mic.borough)?.lowercase() !in known }
        return if (hasOther) entries + LegendEntry("Other", Boroughs.OTHER_COLOR_ARGB) else entries
    }
}
