package com.comedyplatform.mictracker.core.model

import com.comedyplatform.mictracker.core.rules.TagRules
import com.comedyplatform.mictracker.core.rules.TextRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate

// =============================================================================
// Drafts = what the forms edit, and the JSON payloads built from them.
//
// Spec 3.5:
//   * INSERT payloads OMIT null keys, so the database defaults apply.
//   * UPDATE payloads send an explicit `null` for fields the user cleared.
// We build the JSON by hand with buildJsonObject so we control nulls exactly.
// =============================================================================

/** Puts [value] (or JsonNull) — used by update payloads. */
private fun JsonObjectBuilder.putOrNull(key: String, value: String?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}

private fun JsonObjectBuilder.putOrNull(key: String, value: Number?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}

/** Puts [value] only when it isn't null — used by insert payloads. */
private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putIfPresent(key: String, value: Number?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putBoolean(key: String, value: Boolean) {
    put(key, JsonPrimitive(value))
}

/**
 * Every editable column of `open_mics`, as the Mic Form (spec 6.9) edits them.
 */
data class MicDraft(
    val name: String = "",
    val venue: String = "",
    val address: String? = null,
    val neighborhood: String? = null,
    val borough: String? = null,
    /** "Monday" … "Sunday". */
    val dayOfWeek: String = "",
    /** Zero-padded 24h "HH:mm" (rule 19). */
    val startTime: String = "",
    /** "7:00 PM" — auto-filled from the time picker, editable. */
    val displayTime: String? = null,
    val cost: String? = null,
    /** 0 or null = unknown (stored as null). */
    val setLengthMin: Int? = null,
    /** "in_person", "online", "email", "instagram_dm" (or an unknown raw value kept as-is). */
    val signupMethod: String? = null,
    val signupUrl: String? = null,
    val signupNotes: String? = null,
    val venueUrl: String? = null,
    val instagram: String? = null,
    /** "normal", "medium", "high". */
    val urgency: String = Urgency.NORMAL.raw,
    val urgencyNote: String? = null,
    val advanceDays: Int = 0,
    val isBiweekly: Boolean = false,
    val notes: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    /**
     * Cleaned copy: text trimmed, blank optional text → null, set length 0 →
     * null, negative advance days → 0, unknown urgency → "normal".
     */
    fun normalized(): MicDraft = copy(
        name = name.trim(),
        venue = venue.trim(),
        address = TextRules.trimmedOrNull(address),
        neighborhood = TextRules.trimmedOrNull(neighborhood),
        borough = TextRules.trimmedOrNull(borough),
        dayOfWeek = dayOfWeek.trim(),
        startTime = startTime.trim(),
        displayTime = TextRules.trimmedOrNull(displayTime),
        cost = TextRules.trimmedOrNull(cost),
        setLengthMin = setLengthMin?.takeIf { it > 0 },
        signupMethod = TextRules.trimmedOrNull(signupMethod),
        signupUrl = TextRules.trimmedOrNull(signupUrl),
        signupNotes = TextRules.trimmedOrNull(signupNotes),
        venueUrl = TextRules.trimmedOrNull(venueUrl),
        instagram = TextRules.trimmedOrNull(instagram),
        urgency = Urgency.from(urgency).raw,
        urgencyNote = TextRules.trimmedOrNull(urgencyNote),
        advanceDays = advanceDays.coerceAtLeast(0),
        notes = TextRules.trimmedOrNull(notes),
    )

    /** The exact web message when a required field is missing, else null. */
    val validationError: String?
        get() {
            val n = normalized()
            return if (n.name.isEmpty() || n.venue.isEmpty() || n.dayOfWeek.isEmpty() || n.startTime.isEmpty()) {
                REQUIRED_FIELDS_MESSAGE
            } else {
                null
            }
        }

    /** POST /rest/v1/open_mics — null keys omitted, `is_active: true`. */
    fun toInsertPayload(): JsonObject {
        val d = normalized()
        return buildJsonObject {
            put("name", JsonPrimitive(d.name))
            put("venue", JsonPrimitive(d.venue))
            putIfPresent("address", d.address)
            putIfPresent("neighborhood", d.neighborhood)
            putIfPresent("borough", d.borough)
            put("day_of_week", JsonPrimitive(d.dayOfWeek))
            put("start_time", JsonPrimitive(d.startTime))
            putIfPresent("display_time", d.displayTime)
            putIfPresent("cost", d.cost)
            putIfPresent("set_length_min", d.setLengthMin)
            putIfPresent("signup_method", d.signupMethod)
            putIfPresent("signup_url", d.signupUrl)
            putIfPresent("signup_notes", d.signupNotes)
            putIfPresent("venue_url", d.venueUrl)
            putIfPresent("instagram", d.instagram)
            put("urgency", JsonPrimitive(d.urgency))
            putIfPresent("urgency_note", d.urgencyNote)
            put("advance_days", JsonPrimitive(d.advanceDays))
            putBoolean("is_biweekly", d.isBiweekly)
            putIfPresent("notes", d.notes)
            putIfPresent("latitude", d.latitude)
            putIfPresent("longitude", d.longitude)
            putBoolean("is_active", true)
        }
    }

    /**
     * PATCH /rest/v1/open_mics?id=eq.X — every editable field, with explicit
     * nulls for cleared ones. Coordinates are only sent when
     * [includeCoordinates] is true (the address changed and was re-geocoded),
     * so editing a mic's notes never wipes its map pin.
     */
    fun toUpdatePayload(includeCoordinates: Boolean = false): JsonObject {
        val d = normalized()
        return buildJsonObject {
            put("name", JsonPrimitive(d.name))
            put("venue", JsonPrimitive(d.venue))
            putOrNull("address", d.address)
            putOrNull("neighborhood", d.neighborhood)
            putOrNull("borough", d.borough)
            put("day_of_week", JsonPrimitive(d.dayOfWeek))
            put("start_time", JsonPrimitive(d.startTime))
            putOrNull("display_time", d.displayTime)
            putOrNull("cost", d.cost)
            putOrNull("set_length_min", d.setLengthMin)
            putOrNull("signup_method", d.signupMethod)
            putOrNull("signup_url", d.signupUrl)
            putOrNull("signup_notes", d.signupNotes)
            putOrNull("venue_url", d.venueUrl)
            putOrNull("instagram", d.instagram)
            put("urgency", JsonPrimitive(d.urgency))
            putOrNull("urgency_note", d.urgencyNote)
            put("advance_days", JsonPrimitive(d.advanceDays))
            putBoolean("is_biweekly", d.isBiweekly)
            putOrNull("notes", d.notes)
            if (includeCoordinates) {
                putOrNull("latitude", d.latitude)
                putOrNull("longitude", d.longitude)
            }
        }
    }

    /** True when the address (trimmed, case-insensitive) differs from [other]'s. */
    fun addressDiffersFrom(otherAddress: String?): Boolean =
        TextRules.trimmedOrNull(address)?.lowercase() != TextRules.trimmedOrNull(otherAddress)?.lowercase()

    companion object {
        const val REQUIRED_FIELDS_MESSAGE = "Name, Venue, Day of Week, and Start Time are required!"

        /** Prefill the form from an existing mic (Edit). */
        fun fromMic(mic: Mic): MicDraft = MicDraft(
            name = mic.name ?: "",
            venue = mic.venue ?: "",
            address = mic.address,
            neighborhood = mic.neighborhood,
            borough = mic.borough,
            dayOfWeek = mic.dayOfWeek ?: "",
            startTime = mic.startTime ?: "",
            displayTime = mic.displayTime,
            cost = mic.cost,
            setLengthMin = mic.setLengthMin?.takeIf { it > 0 },
            signupMethod = mic.signupMethod,
            signupUrl = mic.signupUrl,
            signupNotes = mic.signupNotes,
            venueUrl = mic.venueUrl,
            instagram = mic.instagram,
            urgency = mic.urgencyLevel.raw,
            urgencyNote = mic.urgencyNote,
            advanceDays = (mic.advanceDays ?: 0).coerceAtLeast(0),
            isBiweekly = mic.isBiweekly == true,
            notes = mic.notes,
            latitude = mic.latitude,
            longitude = mic.longitude,
        )
    }
}

/**
 * Every editable column of `my_sets`, as the Log a Set form (spec 6.6) edits them.
 */
data class SetDraft(
    val openMicId: Int,
    val datePerformed: LocalDate,
    /** 1–10, default 5. */
    val setRating: Int? = 5,
    /** 1–10, default 5. */
    val crowdRating: Int? = 5,
    /** "empty" / "sparse" / "decent" / "packed" (default decent). */
    val crowdSize: String? = CrowdSize.DECENT.raw,
    val setList: String? = null,
    val recordingUrl: String? = null,
    /** "none" / "video" / "audio" (default none). */
    val recordingType: String? = RecordingType.NONE.raw,
    val notes: String? = null,
    val newMaterial: Boolean = false,
    val gotFeedback: Boolean = false,
    /** Only kept when [gotFeedback] is true. */
    val feedbackNotes: String? = null,
    val wouldReturn: Boolean = true,
    val tags: List<String> = emptyList(),
) {
    /** Cleaned copy: blank text → null; feedback notes dropped unless gotFeedback. */
    fun normalized(): SetDraft = copy(
        crowdSize = TextRules.trimmedOrNull(crowdSize),
        setList = TextRules.trimmedOrNull(setList),
        recordingUrl = TextRules.trimmedOrNull(recordingUrl),
        recordingType = TextRules.trimmedOrNull(recordingType),
        notes = TextRules.trimmedOrNull(notes),
        feedbackNotes = if (gotFeedback) TextRules.trimmedOrNull(feedbackNotes) else null,
        tags = TagRules.parse(TagRules.join(tags)),
    )

    /** POST /rest/v1/my_sets — null keys omitted. */
    fun toInsertPayload(): JsonObject {
        val d = normalized()
        return buildJsonObject {
            put("open_mic_id", JsonPrimitive(d.openMicId))
            put("date_performed", JsonPrimitive(d.datePerformed.toString()))
            putIfPresent("set_rating", d.setRating)
            putIfPresent("crowd_rating", d.crowdRating)
            putIfPresent("crowd_size", d.crowdSize)
            putIfPresent("set_list", d.setList)
            putIfPresent("recording_url", d.recordingUrl)
            putIfPresent("recording_type", d.recordingType)
            putIfPresent("notes", d.notes)
            putBoolean("new_material", d.newMaterial)
            putBoolean("got_feedback", d.gotFeedback)
            putIfPresent("feedback_notes", d.feedbackNotes)
            putBoolean("would_return", d.wouldReturn)
            putIfPresent("tags", TagRules.join(d.tags))
        }
    }

    /** PATCH /rest/v1/my_sets?id=eq.X — explicit nulls for cleared fields. */
    fun toUpdatePayload(): JsonObject {
        val d = normalized()
        return buildJsonObject {
            put("open_mic_id", JsonPrimitive(d.openMicId))
            put("date_performed", JsonPrimitive(d.datePerformed.toString()))
            putOrNull("set_rating", d.setRating)
            putOrNull("crowd_rating", d.crowdRating)
            putOrNull("crowd_size", d.crowdSize)
            putOrNull("set_list", d.setList)
            putOrNull("recording_url", d.recordingUrl)
            putOrNull("recording_type", d.recordingType)
            putOrNull("notes", d.notes)
            putBoolean("new_material", d.newMaterial)
            putBoolean("got_feedback", d.gotFeedback)
            putOrNull("feedback_notes", d.feedbackNotes)
            putBoolean("would_return", d.wouldReturn)
            putOrNull("tags", TagRules.join(d.tags))
        }
    }

    companion object {
        /**
         * Prefill from an existing row. For a PLANNED row (no rating yet) the
         * form defaults apply (ratings 5, crowd decent, recording none).
         */
        fun fromSet(set: SetEntry, fallbackDate: LocalDate): SetDraft {
            val planned = set.isPlanned
            return SetDraft(
                openMicId = set.openMicId ?: 0,
                datePerformed = set.datePerformed ?: fallbackDate,
                setRating = set.setRating ?: 5,
                crowdRating = set.crowdRating ?: 5,
                crowdSize = set.crowdSize ?: if (planned) CrowdSize.DECENT.raw else null,
                setList = set.setList,
                recordingUrl = set.recordingUrl,
                recordingType = set.recordingType ?: RecordingType.NONE.raw,
                notes = set.notes,
                newMaterial = set.newMaterial == true,
                gotFeedback = set.gotFeedback == true,
                feedbackNotes = set.feedbackNotes,
                wouldReturn = set.wouldReturn ?: true,
                tags = set.tagList,
            )
        }
    }
}

/** Small PATCH bodies for single-purpose updates. */
object Payloads {
    /** Mic Detail "Update rating": `{"mic_rating": 7.5}` or `{"mic_rating": null}`. */
    fun micRating(rating: Double?): JsonObject = buildJsonObject { putOrNull("mic_rating", rating) }

    /** Manage → Geocoding: `{"latitude": .., "longitude": ..}`. */
    fun coordinates(latitude: Double?, longitude: Double?): JsonObject = buildJsonObject {
        putOrNull("latitude", latitude)
        putOrNull("longitude", longitude)
    }
}

/** Table names (spec 3.3 / 3.4). */
object Tables {
    const val OPEN_MICS = "open_mics"
    const val MY_SETS = "my_sets"
    const val MIC_PLANS = "mic_plans"
    const val SCRAPE_LOG = "scrape_log"
    const val SCRAPED_MICS = "scraped_mics"
}

/**
 * RPC function names and their JSON arguments (spec 3.4). Arg names are exact.
 * Dates are sent as "YYYY-MM-DD" strings.
 */
object Rpc {
    const val IS_APP_ADMIN = "is_app_admin"
    const val SET_PLAN_STATUS = "set_plan_status"
    const val CLEAR_PLAN = "clear_plan"
    const val DELETE_PLANNED_SET = "delete_planned_set"
    const val DELETE_MIC = "delete_mic"
    const val IMPORT_SCRAPED_MIC = "import_scraped_mic"
    const val DISMISS_SCRAPED_MIC = "dismiss_scraped_mic"

    fun noArgs(): JsonObject = JsonObject(emptyMap<String, JsonElement>())

    fun setPlanStatusArgs(micId: Int, date: LocalDate, status: PlanStatus): JsonObject = buildJsonObject {
        put("p_mic_id", JsonPrimitive(micId))
        put("p_plan_date", JsonPrimitive(date.toString()))
        put("p_status", JsonPrimitive(status.raw))
    }

    fun clearPlanArgs(micId: Int, date: LocalDate): JsonObject = buildJsonObject {
        put("p_mic_id", JsonPrimitive(micId))
        put("p_plan_date", JsonPrimitive(date.toString()))
    }

    fun deletePlannedSetArgs(setId: Int): JsonObject = buildJsonObject {
        put("p_set_id", JsonPrimitive(setId))
    }

    fun deleteMicArgs(micId: Int): JsonObject = buildJsonObject {
        put("p_mic_id", JsonPrimitive(micId))
    }

    fun importScrapedMicArgs(scrapedId: Long): JsonObject = buildJsonObject {
        put("p_id", JsonPrimitive(scrapedId))
    }

    fun dismissScrapedMicArgs(scrapedId: Long): JsonObject = buildJsonObject {
        put("p_id", JsonPrimitive(scrapedId))
    }
}
