package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicPlan
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.model.SetEntry
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Spec 3.5: decoding must never crash on nulls, missing keys, floats, blanks or junk. */
class ModelDecodingTest {

    private val json = CoreJson.json

    @Test
    fun micDecodesFullRow() {
        val mic = json.decodeFromString(
            Mic.serializer(),
            """
            {"id": 7, "name": "Grisly Pear", "venue": "Grisly Pear", "address": "107 MacDougal St",
             "neighborhood": "Greenwich Village", "borough": "Manhattan", "day_of_week": "Monday",
             "start_time": "19:00", "display_time": "7:00 PM", "end_time": null, "cost": "${'$'}5",
             "set_length_min": 5, "signup_method": "online", "signup_url": "https://example.com",
             "urgency": "high", "advance_days": 2, "mic_rating": 7.5, "latitude": 40.73,
             "longitude": -74.0, "is_active": true, "is_biweekly": false,
             "created_at": "2026-01-01T00:00:00", "some_new_column": {"x": 1}}
            """.trimIndent(),
        )
        assertEquals(7, mic.id)
        assertEquals("Grisly Pear", mic.name)
        assertEquals(5, mic.setLengthMin)
        assertEquals(7.5, mic.micRating!!, 0.0001)
        assertEquals(true, mic.isActive)
        assertEquals(false, mic.isBiweekly)
        assertNull(mic.endTime)
        assertEquals("7:00 PM", mic.timeText)
    }

    @Test
    fun micToleratesFloatsStringsBlanksAndMissingKeys() {
        val mic = json.decodeFromString(
            Mic.serializer(),
            """{"id": 12.0, "name": "  ", "venue": "Venue", "set_length_min": 30.0,
               "advance_days": "3", "is_active": 0, "is_biweekly": "1", "mic_rating": "8",
               "latitude": "40.7", "cost": "", "urgency": "whatever"}""",
        )
        assertEquals(12, mic.id)
        assertNull(mic.name)
        assertEquals("Unnamed mic", mic.displayName)
        assertEquals(30, mic.setLengthMin)
        assertEquals(3, mic.advanceDays)
        assertEquals(false, mic.isActive)
        assertFalse(mic.isActiveMic)
        assertEquals(true, mic.isBiweekly)
        assertEquals(8.0, mic.micRating!!, 0.0001)
        assertEquals(40.7, mic.latitude!!, 0.0001)
        assertNull(mic.longitude)
        assertNull(mic.cost)
        assertEquals("Free/TBD", mic.costText)
        assertNull(mic.dayOfWeek)
    }

    @Test
    fun micNullIsActiveCountsAsActive() {
        val mic = json.decodeFromString(Mic.serializer(), """{"id": 1, "is_active": null}""")
        assertNull(mic.isActive)
        assertTrue(mic.isActiveMic)
    }

    @Test
    fun junkValuesBecomeNull() {
        val mic = json.decodeFromString(
            Mic.serializer(),
            """{"id": 3, "set_length_min": "about 5", "latitude": true, "is_active": "maybe",
               "name": ["array"], "mic_rating": "NaN"}""",
        )
        assertNull(mic.setLengthMin)
        assertNull(mic.latitude)
        assertNull(mic.isActive)
        assertNull(mic.name)
        assertNull(mic.micRating)
    }

    @Test
    fun listDecodingSkipsRowsWithoutId() {
        val element = json.parseToJsonElement(
            """[{"id": 1, "name": "A"}, {"name": "no id"}, {"id": null}, {"id": "2", "name": "B"}]""",
        )
        val mics = CoreJson.decodeList(element, Mic.serializer())
        assertEquals(listOf(1, 2), mics.map { it.id })
    }

    @Test
    fun listDecodingOfNonArrayIsEmpty() {
        assertTrue(CoreJson.decodeList(json.parseToJsonElement("""{"id": 1}"""), Mic.serializer()).isEmpty())
        assertTrue(CoreJson.decodeList(null, Mic.serializer()).isEmpty())
    }

    @Test
    fun setEntryDecodesDatesWithoutShifting() {
        val set = json.decodeFromString(
            SetEntry.serializer(),
            """{"id": 5, "open_mic_id": 7, "date_performed": "2026-01-01", "set_rating": 8.0,
               "crowd_rating": null, "tags": "killed, crowd_work,", "would_return": 1}""",
        )
        assertEquals(LocalDate.of(2026, 1, 1), set.datePerformed)
        assertEquals(8, set.setRating)
        assertTrue(set.isLogged)
        assertFalse(set.isPlanned)
        assertNull(set.crowdRating)
        assertEquals(listOf("killed", "crowd_work"), set.tagList)
        assertEquals(true, set.wouldReturn)
        assertEquals("01/01/2026", set.dateText)
    }

    @Test
    fun plannedSetHasNoRating() {
        val set = json.decodeFromString(
            SetEntry.serializer(),
            """{"id": 6, "open_mic_id": 7, "date_performed": "2026-10-20T00:00:00"}""",
        )
        assertTrue(set.isPlanned)
        assertEquals(LocalDate.of(2026, 10, 20), set.datePerformed)
        assertEquals("📋", set.ratingEmoji)
        assertEquals("PLANNED — tap to log details", set.summaryText)
    }

    @Test
    fun badDateBecomesNull() {
        val set = json.decodeFromString(SetEntry.serializer(), """{"id": 6, "date_performed": "yesterday"}""")
        assertNull(set.datePerformed)
    }

    @Test
    fun micPlanStatusParsing() {
        val plans = CoreJson.decodeList(
            json.parseToJsonElement(
                """[{"id": 1, "open_mic_id": 2, "plan_date": "2026-10-21", "status": "going"},
                    {"id": 2, "open_mic_id": 2, "plan_date": "2026-10-22", "status": "CANCELLED"},
                    {"id": 3, "open_mic_id": 2, "plan_date": "2026-10-23", "status": "maybe"}]""",
            ),
            MicPlan.serializer(),
        )
        assertEquals(PlanStatus.GOING, plans[0].planStatus)
        assertEquals(PlanStatus.CANCELLED, plans[1].planStatus)
        assertNull(plans[2].planStatus)
        assertEquals(LocalDate.of(2026, 10, 21), plans[0].planDate)
    }

    @Test
    fun scrapedMicUsesLongIds() {
        val scraped = json.decodeFromString(
            ScrapedMic.serializer(),
            """{"id": 9007199254740993, "source": "badslava", "name": "Open Mic", "venue": "Bar",
               "day_of_week": "Tuesday", "payload": {"name": "x"}, "status": "pending"}""",
        )
        assertEquals(9007199254740993L, scraped.id)
        assertEquals("Bad Slava", scraped.sourceDisplayName)
        assertEquals("Tuesday — Open Mic @ Bar", scraped.title)
    }

    @Test
    fun scrapeLogTimestampText() {
        val entry = json.decodeFromString(
            ScrapeLogEntry.serializer(),
            """{"id": 1, "source": "firemics", "last_scraped": "2026-10-20T14:07:33.123456", "status": "ok"}""",
        )
        assertEquals("2026-10-20 14:07", entry.lastScrapedText)
        assertEquals("FireMics", entry.sourceDisplayName)
    }

    @Test
    fun jsonValuesBooleansAndErrors() {
        assertEquals(true, JsonValues.boolean(JsonPrimitive("yes")))
        assertEquals(false, JsonValues.boolean(JsonPrimitive(0)))
        assertEquals(true, JsonValues.boolean(JsonPrimitive(true)))
        assertNull(JsonValues.boolean(JsonPrimitive("perhaps")))
        val error = json.parseToJsonElement("""{"code": "42501", "message": "Not authorized", "details": null}""")
        assertEquals("Not authorized", JsonValues.errorMessage(error))
        val goTrue = json.parseToJsonElement("""{"error": "invalid_grant", "error_description": "Invalid login credentials"}""")
        assertEquals("Invalid login credentials", JsonValues.errorMessage(goTrue))
    }
}
