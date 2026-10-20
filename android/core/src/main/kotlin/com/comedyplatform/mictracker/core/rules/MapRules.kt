package com.comedyplatform.mictracker.core.rules

/**
 * Boroughs, borough colors (rule 17), NYC bounds (rule 18), and the map's
 * zoom presets and styles (spec 6.5).
 */
object Boroughs {

    /** The five boroughs, in the web app's order (pickers and filters). */
    val all: List<String> = listOf("Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island")

    private val colors: Map<String, Long> = mapOf(
        "manhattan" to 0xFFFF6347,
        "brooklyn" to 0xFF1E90FF,
        "queens" to 0xFF32CD32,
        "bronx" to 0xFFFFA500,
        "staten island" to 0xFF9370DB,
    )

    /** Gray for anything else. */
    const val OTHER_COLOR_ARGB: Long = 0xFF808080

    /** Rule 17: borough → ARGB color (case-insensitive; unknown → gray). */
    fun colorArgb(borough: String?): Long {
        val key = TextRules.trimmedOrNull(borough)?.lowercase() ?: return OTHER_COLOR_ARGB
        return colors[key] ?: OTHER_COLOR_ARGB
    }

    /** The borough with the most mics, or "N/A" (the Tonight quick stat). Ties: first in [all] order, then alphabetical. */
    fun mostCommon(boroughs: List<String?>): String {
        val counts = boroughs.mapNotNull { TextRules.trimmedOrNull(it) }.groupingBy { it }.eachCount()
        if (counts.isEmpty()) return "N/A"
        val best = counts.values.max()
        return counts.filterValues { it == best }.keys
            .sortedWith(compareBy<String>({ name -> all.indexOf(name).let { if (it < 0) Int.MAX_VALUE else it } }, { it }))
            .first()
    }
}

/** A latitude/longitude pair. */
data class GeoPoint(val latitude: Double, val longitude: Double)

object GeoRules {

    /** Rule 18: 40.4 < lat < 41.0 and −74.3 < lon < −73.5. */
    fun isInNyc(latitude: Double, longitude: Double): Boolean =
        latitude > 40.4 && latitude < 41.0 && longitude > -74.3 && longitude < -73.5

    fun isInNyc(point: GeoPoint): Boolean = isInNyc(point.latitude, point.longitude)

    /**
     * Spec 6.8: the queries to try, in order, for a mic's address:
     * "{address}, {borough}, New York, NY" then "{address}, New York, NY".
     * Empty when there's no address.
     */
    fun geocodeQueries(address: String?, borough: String?): List<String> {
        val cleanAddress = TextRules.trimmedOrNull(address) ?: return emptyList()
        val cleanBorough = TextRules.trimmedOrNull(borough)
        val queries = mutableListOf<String>()
        if (cleanBorough != null) queries += "$cleanAddress, $cleanBorough, New York, NY"
        queries += "$cleanAddress, New York, NY"
        return queries
    }
}

/** Spec 6.5 zoom presets. */
enum class MapPreset(val label: String, val latitude: Double, val longitude: Double, val zoom: Double) {
    ALL_NYC("All NYC", 40.7128, -73.9500, 10.5),
    MANHATTAN("Manhattan", 40.7580, -73.9855, 12.5),
    BROOKLYN("Brooklyn", 40.6782, -73.9442, 12.0),
    QUEENS_BRONX("Queens/Bronx", 40.8000, -73.8800, 11.0),
}

/** Spec 6.5 Android map styles (Carto GL styles — no API key). */
enum class MapStyle(val label: String, val url: String) {
    VOYAGER("Voyager", "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"),
    LIGHT("Light", "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"),
    DARK("Dark", "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"),
}
