package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicDraft
import com.comedyplatform.mictracker.core.model.Payloads
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.Rpc
import com.comedyplatform.mictracker.core.model.SetDraft
import com.comedyplatform.mictracker.core.model.SetEntry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Spec 3.5: insert payloads omit nulls; update payloads send explicit nulls. */
class PayloadTest {

    private val draft = MicDraft(
        name = "  Grisly Pear ",
        venue = "Grisly Pear",
        address = "   ",
        dayOfWeek = "Monday",
        startTime = "19:00",
        displayTime = "7:00 PM",
        cost = "$5",
        setLengthMin = 0,
        urgency = "bogus",
        advanceDays = -3,
        notes = "",
    )

    @Test
    fun micInsertOmitsNullsAndSetsActive() {
        val payload = draft.toInsertPayload()
        assertEquals(JsonPrimitive("Grisly Pear"), payload["name"])
        assertEquals(JsonPrimitive("$5"), payload["cost"])
        assertFalse(payload.containsKey("address"))
        assertFalse(payload.containsKey("set_length_min"))
        assertFalse(payload.containsKey("notes"))
        assertFalse(payload.containsKey("latitude"))
        assertEquals(JsonPrimitive("normal"), payload["urgency"])
        assertEquals(JsonPrimitive(0), payload["advance_days"])
        assertEquals(JsonPrimitive(true), payload["is_active"])
        assertEquals(JsonPrimitive(false), payload["is_biweekly"])
        // No null values anywhere in an insert payload.
        assertTrue(payload.values.none { it is JsonNull })
    }

    @Test
    fun micInsertIncludesCoordinatesWhenGeocoded() {
        val payload = draft.copy(latitude = 40.73, longitude = -74.0).toInsertPayload()
        assertEquals(40.73, payload["latitude"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun micUpdateSendsExplicitNulls() {
        val payload = draft.toUpdatePayload()
        assertEquals(JsonNull, payload["address"])
        assertEquals(JsonNull, payload["set_length_min"])
        assertEquals(JsonNull, payload["notes"])
        assertEquals(JsonNull, payload["signup_url"])
        assertFalse("coordinates untouched unless asked", payload.containsKey("latitude"))
        assertFalse(payload.containsKey("is_active"))
        val withCoords = draft.toUpdatePayload(includeCoordinates = true)
        assertEquals(JsonNull, withCoords["latitude"])
        assertEquals(JsonNull, withCoords["longitude"])
    }

    @Test
    fun micValidation() {
        assertNull(draft.validationError)
        assertEquals(
            "Name, Venue, Day of Week, and Start Time are required!",
            draft.copy(venue = "  ").validationError,
        )
        assertEquals(MicDraft.REQUIRED_FIELDS_MESSAGE, MicDraft().validationError)
    }

    @Test
    fun micDraftFromMicRoundTrips() {
        val mic = Mic(
            id = 3, name = "N", venue = "V", dayOfWeek = "Friday", startTime = "20:00",
            setLengthMin = 5, urgency = "HIGH", advanceDays = 2, isBiweekly = true, latitude = 40.7, longitude = -73.9,
        )
        val fromMic = MicDraft.fromMic(mic)
        assertEquals("high", fromMic.urgency)
        assertEquals(true, fromMic.isBiweekly)
        assertEquals(5, fromMic.setLengthMin)
        assertFalse(fromMic.addressDiffersFrom(null))
        assertTrue(fromMic.copy(address = "1 Main").addressDiffersFrom(null))
        assertFalse(fromMic.copy(address = "1 Main ").addressDiffersFrom("1 main"))
    }

    @Test
    fun setInsertAndUpdate() {
        val set = SetDraft(
            openMicId = 7,
            datePerformed = LocalDate.of(2026, 10, 20),
            setRating = 8,
            crowdRating = 6,
            setList = "  ",
            gotFeedback = false,
            feedbackNotes = "ignored because gotFeedback is false",
            tags = listOf("killed", "crowd_work"),
        )
        val insert = set.toInsertPayload()
        assertEquals(JsonPrimitive("2026-10-20"), insert["date_performed"])
        assertEquals(JsonPrimitive(7), insert["open_mic_id"])
        assertEquals(JsonPrimitive("killed,crowd_work"), insert["tags"])
        assertEquals(JsonPrimitive("decent"), insert["crowd_size"])
        assertEquals(JsonPrimitive("none"), insert["recording_type"])
        assertEquals(JsonPrimitive(true), insert["would_return"])
        assertFalse(insert.containsKey("set_list"))
        assertFalse(insert.containsKey("feedback_notes"))

        val update = set.copy(tags = emptyList()).toUpdatePayload()
        assertEquals(JsonNull, update["set_list"])
        assertEquals(JsonNull, update["feedback_notes"])
        assertEquals(JsonNull, update["tags"])
        assertEquals(JsonPrimitive(8), update["set_rating"])
    }

    @Test
    fun setDraftFromPlannedRowUsesFormDefaults() {
        val planned = SetEntry(id = 4, openMicId = 9, datePerformed = LocalDate.of(2026, 10, 18))
        val draft = SetDraft.fromSet(planned, fallbackDate = LocalDate.of(2026, 10, 20))
        assertEquals(9, draft.openMicId)
        assertEquals(5, draft.setRating)
        assertEquals(5, draft.crowdRating)
        assertEquals("decent", draft.crowdSize)
        assertEquals("none", draft.recordingType)
        assertTrue(draft.wouldReturn)
    }

    @Test
    fun smallPayloadsAndRpcArgs() {
        assertEquals(JsonNull, Payloads.micRating(null)["mic_rating"])
        assertEquals(JsonPrimitive(7.5), Payloads.micRating(7.5)["mic_rating"])
        assertEquals(JsonPrimitive(40.7), Payloads.coordinates(40.7, -73.9)["latitude"])

        val args = Rpc.setPlanStatusArgs(12, LocalDate.of(2026, 1, 5), PlanStatus.GOING)
        assertEquals(JsonPrimitive(12), args["p_mic_id"])
        assertEquals(JsonPrimitive("2026-01-05"), args["p_plan_date"])
        assertEquals(JsonPrimitive("going"), args["p_status"])
        assertEquals(setOf("p_mic_id", "p_plan_date"), Rpc.clearPlanArgs(1, LocalDate.of(2026, 1, 5)).keys)
        assertEquals(setOf("p_set_id"), Rpc.deletePlannedSetArgs(3).keys)
        assertEquals(setOf("p_mic_id"), Rpc.deleteMicArgs(3).keys)
        assertEquals(JsonPrimitive(99L), Rpc.importScrapedMicArgs(99L)["p_id"])
        assertEquals(setOf("p_id"), Rpc.dismissScrapedMicArgs(99L).keys)
        assertTrue(Rpc.noArgs().isEmpty())
    }
}
