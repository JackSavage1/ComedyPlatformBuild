package com.comedyplatform.mictracker.ui.manage

import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.ScrapeSources
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.rules.TextRules

/**
 * The small, pure calculations behind Manage (spec 6.8). Plain Kotlin —
 * no Android or Compose imports.
 */
object ManageRules {

    // ---------------------------------------------------------------------
    // Import from Scrapers
    // ---------------------------------------------------------------------

    /** Pending scraped mics from one scraper. [key] is the raw source ("badslava"), or "" when unknown. */
    data class SourceGroup(val key: String, val title: String, val mics: List<ScrapedMic>)

    /** Known scrapers first (Bad Slava, FireMics), then any others alphabetically. */
    private val sourceOrder = listOf("badslava", "firemics")

    /** Groups pending rows by source, keeping the repository's order inside each group. */
    fun groupBySource(scraped: List<ScrapedMic>): List<SourceGroup> =
        scraped.groupBy { TextRules.trimmedOrNull(it.source)?.lowercase() ?: "" }
            .map { (key, mics) -> SourceGroup(key, ScrapeSources.displayName(key), mics) }
            .sortedWith(
                compareBy<SourceGroup> { group ->
                    val index = sourceOrder.indexOf(group.key)
                    if (index >= 0) index else sourceOrder.size
                }.thenBy { it.title.lowercase() },
            )

    /** "7:00 PM · $5" — the time and cost line of an import row (blank parts skipped). */
    fun scrapedDetails(mic: ScrapedMic): String =
        TextRules.joinNonBlank(listOf(mic.timeText, mic.cost), separator = " · ")

    /** "Added Comedy Night @ The Club!" */
    fun addedMessage(mic: ScrapedMic): String {
        val venue = TextRules.trimmedOrNull(mic.venue)
        return if (venue == null) "Added ${mic.displayName}!" else "Added ${mic.displayName} @ $venue!"
    }

    /** "Added 5 mics from Bad Slava" / "Added 1 mic from FireMics". */
    fun addAllMessage(count: Int, sourceTitle: String): String =
        "Added $count ${if (count == 1) "mic" else "mics"} from $sourceTitle"

    // ---------------------------------------------------------------------
    // Manage Mics
    // ---------------------------------------------------------------------

    /** "Mon — Name @ Venue". */
    fun manageLabel(mic: Mic): String {
        val day = mic.shortDayText
        val nameAndVenue = if (mic.venueName.isEmpty()) mic.displayName else "${mic.displayName} @ ${mic.venueName}"
        return if (day.isEmpty()) nameAndVenue else "$day — $nameAndVenue"
    }

    /**
     * Case-insensitive search over name, venue, neighborhood, borough and day.
     * Every word must match somewhere ("brooklyn tue" works).
     */
    fun searchMics(mics: List<Mic>, query: String): List<Mic> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return mics
        return mics.filter { mic ->
            val haystack = listOfNotNull(mic.name, mic.venue, mic.neighborhood, mic.borough, mic.dayOfWeek)
                .joinToString(" ")
                .lowercase()
            words.all { haystack.contains(it) }
        }
    }

    // ---------------------------------------------------------------------
    // Geocoding
    // ---------------------------------------------------------------------

    /** Active mics without a map pin. */
    fun missingCoordinates(activeMics: List<Mic>): List<Mic> = activeMics.filter { !it.hasCoordinates }

    /** The ones of [missing] we can actually look up (they have an address). */
    fun geocodable(missing: List<Mic>): List<Mic> = missing.filter { TextRules.hasText(it.address) }

    /** "Mics with coordinates 40 / 52". */
    fun coordinatesSummary(activeMics: List<Mic>): String =
        "Mics with coordinates ${activeMics.count { it.hasCoordinates }} / ${activeMics.size}"

    /** "Geocoded 7 of 9". */
    fun geocodeResult(found: Int, attempted: Int): String = "Geocoded $found of $attempted"
}
