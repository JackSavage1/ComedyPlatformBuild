package com.comedyplatform.mictracker.core.model

import com.comedyplatform.mictracker.core.json.BlankAsNullStringSerializer
import com.comedyplatform.mictracker.core.json.LenientBooleanSerializer
import com.comedyplatform.mictracker.core.json.LenientDoubleSerializer
import com.comedyplatform.mictracker.core.json.LenientIdSerializer
import com.comedyplatform.mictracker.core.json.LenientIntSerializer
import com.comedyplatform.mictracker.core.json.LenientLocalDateSerializer
import com.comedyplatform.mictracker.core.json.LenientLongIdSerializer
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.InstagramRules
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.SetRules
import com.comedyplatform.mictracker.core.rules.SignupDeadline
import com.comedyplatform.mictracker.core.rules.SignupRules
import com.comedyplatform.mictracker.core.rules.TagRules
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

// =============================================================================
// Data models — one class per Supabase table (spec 3.3 / 3.4).
//
// DEFENSIVE BY DESIGN: every column is nullable except `id`, and each property
// uses a lenient serializer so that null, missing keys, blank strings, numbers
// sent as floats (30.0) or booleans sent as 0/1 never crash decoding.
// Blank / whitespace-only text is decoded as null (spec 3.5 "Blank = null").
//
// The computed properties (no backing field, so they're not serialized) apply
// the business rules from :core's `rules` package.
// =============================================================================

/** A row of `open_mics`. */
@Serializable
data class Mic(
    @Serializable(with = LenientIdSerializer::class)
    val id: Int,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val name: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val venue: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val address: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val neighborhood: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val borough: String? = null,
    @SerialName("day_of_week")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val dayOfWeek: String? = null,
    @SerialName("start_time")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val startTime: String? = null,
    @SerialName("display_time")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val displayTime: String? = null,
    @SerialName("end_time")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val endTime: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val cost: String? = null,
    @SerialName("set_length_min")
    @Serializable(with = LenientIntSerializer::class)
    val setLengthMin: Int? = null,
    @SerialName("signup_method")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val signupMethod: String? = null,
    @SerialName("signup_url")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val signupUrl: String? = null,
    @SerialName("signup_notes")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val signupNotes: String? = null,
    @SerialName("venue_url")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val venueUrl: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val instagram: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val urgency: String? = null,
    @SerialName("urgency_note")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val urgencyNote: String? = null,
    @SerialName("advance_days")
    @Serializable(with = LenientIntSerializer::class)
    val advanceDays: Int? = null,
    @SerialName("mic_rating")
    @Serializable(with = LenientDoubleSerializer::class)
    val micRating: Double? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val notes: String? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val latitude: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val longitude: Double? = null,
    @SerialName("is_active")
    @Serializable(with = LenientBooleanSerializer::class)
    val isActive: Boolean? = null,
    @SerialName("is_biweekly")
    @Serializable(with = LenientBooleanSerializer::class)
    val isBiweekly: Boolean? = null,
) {
    /** The mic's name, never blank. */
    val displayName: String get() = name ?: "Unnamed mic"

    /** The venue, or "" when unknown. */
    val venueName: String get() = venue ?: ""

    /** Spec 3.5: "active" = `is_active != false` (null counts as active). */
    val isActiveMic: Boolean get() = isActive != false

    val isBiweeklyMic: Boolean get() = isBiweekly == true

    /** Parsed `day_of_week`, or null when it isn't a weekday. */
    val weekday: DayOfWeek? get() = Weekdays.parse(dayOfWeek)

    /** "Mon" (or the raw text if it isn't a weekday). */
    val shortDayText: String get() = weekday?.let { Weekdays.shortName(it) } ?: (dayOfWeek ?: "")

    /** Rule 1: display time, else start time. */
    val timeText: String get() = MicRules.timeText(displayTime, startTime)

    /** Rule 2: cost or "Free/TBD". */
    val costText: String get() = MicRules.costText(cost)

    /** Rule 2: 🆓 / 🍺 / 💵 bucket. */
    val costKind: CostKind get() = CostKind.of(cost)

    /** Rule 3. */
    val urgencyLevel: Urgency get() = Urgency.from(urgency)

    /** Rule 3: the mic's own urgency note, or the default for its level (null for normal). */
    val urgencyMessage: String?
        get() = TextRules.trimmedOrNull(urgencyNote) ?: urgencyLevel.defaultNote

    /** Rule 10. */
    val setLengthText: String get() = MicRules.setLengthText(setLengthMin)

    /** Start time in minutes after midnight (for sorting), or null. */
    val startMinutes: Int? get() = MicRules.minutesSinceMidnight(startTime)

    /** "In Person", "Instagram DM", or the raw value prettified; null when unknown. */
    val signupMethodText: String? get() = SignupMethod.displayText(signupMethod)

    /** Rule 8: a link for the Sign Up button, or null. */
    val signupLink: String? get() = SignupRules.signupUrl(signupUrl)

    /** Rule 8: signup text that isn't a link (shown as a note instead of a button). */
    val signupUrlNote: String? get() = SignupRules.signupNote(signupUrl)

    /** Rule 9: valid Instagram handle (no "@"), or null. */
    val instagramHandle: String? get() = InstagramRules.handle(instagram)

    /** The venue website normalized like a sign-up link, or null. */
    val venueLink: String? get() = SignupRules.signupUrl(venueUrl)

    val hasCoordinates: Boolean get() = latitude != null && longitude != null

    /** "East Village, Manhattan" (either part may be missing; "" if both are). */
    val locationText: String get() = TextRules.joinNonBlank(listOf(neighborhood, borough))

    /** "Mondays at 7:00 PM" (falls back to the raw day text). */
    val scheduleText: String
        get() {
            val dayText = weekday?.let { Weekdays.pluralName(it) } ?: (dayOfWeek ?: "")
            return when {
                dayText.isEmpty() -> timeText
                timeText.isEmpty() -> dayText
                else -> "$dayText at $timeText"
            }
        }

    /** Rule 17. */
    val boroughColorArgb: Long get() = Boroughs.colorArgb(borough)

    /** Rule 7 for this mic happening on [date]. */
    fun signupDeadline(date: LocalDate, today: LocalDate): SignupDeadline? =
        SignupDeadline.compute(advanceDays, date, today)

    /** Rule 16: the next date (today or later) this mic happens, or null if its day is unknown. */
    fun nextOccurrence(today: LocalDate): LocalDate? = weekday?.let { DateRules.nextOccurrence(it, today) }
}

/** A row of `my_sets`. `set_rating == null` ⇒ a PLANNED (skeletal) row created by "Going". */
@Serializable
data class SetEntry(
    @Serializable(with = LenientIdSerializer::class)
    val id: Int,
    @SerialName("open_mic_id")
    @Serializable(with = LenientIntSerializer::class)
    val openMicId: Int? = null,
    @SerialName("date_performed")
    @Serializable(with = LenientLocalDateSerializer::class)
    val datePerformed: LocalDate? = null,
    @SerialName("set_rating")
    @Serializable(with = LenientIntSerializer::class)
    val setRating: Int? = null,
    @SerialName("crowd_rating")
    @Serializable(with = LenientIntSerializer::class)
    val crowdRating: Int? = null,
    @SerialName("crowd_size")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val crowdSize: String? = null,
    @SerialName("set_list")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val setList: String? = null,
    @SerialName("recording_url")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val recordingUrl: String? = null,
    @SerialName("recording_type")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val recordingType: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val notes: String? = null,
    @SerialName("new_material")
    @Serializable(with = LenientBooleanSerializer::class)
    val newMaterial: Boolean? = null,
    @SerialName("got_feedback")
    @Serializable(with = LenientBooleanSerializer::class)
    val gotFeedback: Boolean? = null,
    @SerialName("feedback_notes")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val feedbackNotes: String? = null,
    @SerialName("would_return")
    @Serializable(with = LenientBooleanSerializer::class)
    val wouldReturn: Boolean? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val tags: String? = null,
) {
    /** A skeletal row created by "Going" — no rating logged yet. */
    val isPlanned: Boolean get() = setRating == null

    /** A real, logged performance. */
    val isLogged: Boolean get() = setRating != null

    /** Rule 14: parsed tags. */
    val tagList: List<String> get() = TagRules.parse(tags)

    /** Rule 15. */
    val ratingEmoji: String get() = SetRules.ratingEmoji(setRating)

    /** "Set: 8/10 · Crowd: 6/10" or "PLANNED — tap to log details". */
    val summaryText: String get() = SetRules.summaryText(this)

    /** "10/20/2026", or "Unknown date". */
    val dateText: String get() = datePerformed?.let { DateRules.usDate(it) } ?: "Unknown date"

    /** A link to the recording, normalized like a sign-up link, or null. */
    val recordingLink: String? get() = SignupRules.signupUrl(recordingUrl)
}

/** A row of `mic_plans`: "going" / "cancelled" for one mic on one date. */
@Serializable
data class MicPlan(
    @Serializable(with = LenientIdSerializer::class)
    val id: Int,
    @SerialName("open_mic_id")
    @Serializable(with = LenientIntSerializer::class)
    val openMicId: Int? = null,
    @SerialName("plan_date")
    @Serializable(with = LenientLocalDateSerializer::class)
    val planDate: LocalDate? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val status: String? = null,
) {
    val planStatus: PlanStatus? get() = PlanStatus.from(status)
}

/** A row of `scraped_mics` — the review queue the nightly scraper fills. */
@Serializable
data class ScrapedMic(
    @Serializable(with = LenientLongIdSerializer::class)
    val id: Long,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val source: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val name: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val venue: String? = null,
    @SerialName("day_of_week")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val dayOfWeek: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val address: String? = null,
    @SerialName("display_time")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val displayTime: String? = null,
    @SerialName("start_time")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val startTime: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val cost: String? = null,
    @SerialName("signup_url")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val signupUrl: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val status: String? = null,
) {
    val displayName: String get() = name ?: "Unnamed mic"

    /** Rule 1 applied to the scraped row. */
    val timeText: String get() = MicRules.timeText(displayTime, startTime)

    /** "Monday — Name @ Venue" (the Import list row title). */
    val title: String
        get() {
            val day = dayOfWeek ?: "?"
            val at = venue?.let { " @ $it" } ?: ""
            return "$day — $displayName$at"
        }

    /** "Bad Slava" / "FireMics" / prettified unknown source. */
    val sourceDisplayName: String get() = ScrapeSources.displayName(source)

    /** A tappable sign-up link (rule 8), or null. */
    val signupLink: String? get() = SignupRules.signupUrl(signupUrl)
}

/** A row of `scrape_log`. `last_scraped` is a Postgres timestamp kept as text. */
@Serializable
data class ScrapeLogEntry(
    @Serializable(with = LenientIdSerializer::class)
    val id: Int,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val source: String? = null,
    @SerialName("last_scraped")
    @Serializable(with = BlankAsNullStringSerializer::class)
    val lastScraped: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val status: String? = null,
    @Serializable(with = BlankAsNullStringSerializer::class)
    val notes: String? = null,
) {
    val sourceDisplayName: String get() = ScrapeSources.displayName(source)

    /** "2026-10-20 14:07" from "2026-10-20T14:07:33.123456" (raw text if it doesn't parse). */
    val lastScrapedText: String
        get() {
            val raw = lastScraped ?: return "Never"
            val normalized = raw.replace('T', ' ')
            return if (normalized.length >= 16 && normalized[10] == ' ') normalized.substring(0, 16) else raw
        }
}

/** Display names for scraper sources. */
object ScrapeSources {
    fun displayName(source: String?): String = when (TextRules.trimmedOrNull(source)?.lowercase()) {
        null -> "Unknown source"
        "badslava" -> "Bad Slava"
        "firemics" -> "FireMics"
        else -> TextRules.prettify(source)
    }
}

/**
 * A set joined with its mic (spec 3.5: sets are joined to mics client-side).
 * [mic] is null when the mic row is missing; inactive mics still resolve
 * because ALL mics (including deactivated ones) are loaded.
 */
data class SetWithMic(val set: SetEntry, val mic: Mic?) {
    val micName: String get() = mic?.displayName ?: "Unknown mic"
    val venue: String get() = mic?.venueName ?: ""

    /** "Mic @ Venue" (or just the mic name). */
    val micAndVenue: String get() = if (venue.isEmpty()) micName else "$micName @ $venue"
}

/** Lookup key for plan status: one mic on one date. */
data class PlanKey(val micId: Int, val date: LocalDate)
