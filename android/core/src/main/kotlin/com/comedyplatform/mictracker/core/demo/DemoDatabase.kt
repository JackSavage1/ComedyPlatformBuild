package com.comedyplatform.mictracker.core.demo

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicPlan
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.model.SetEntry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToLong

/** An error the real database would have raised; [message] is safe to show. */
class DemoDatabaseException(message: String) : Exception(message)

/**
 * A tiny in-memory stand-in for the Supabase tables, used by demo mode
 * (spec 8.2). Rows are kept as JSON objects with the database's column names,
 * so the app's normal insert/update payloads (MicDraft, SetDraft, ...) apply
 * to it exactly as PostgREST would apply them:
 *
 *   insert  → the payload + a new id (+ the column DEFAULTs)
 *   update  → the row with the patch's keys replaced (an explicit null clears)
 *
 * The RPC functions copy the SQL in supabase/migrations/…_mobile_api.sql
 * (spec 3.4): Going creates a skeletal set, delete_planned_set removes the
 * plan too, import copies a scraped mic into the mic list, and so on.
 * New ids continue from the largest existing id of each table.
 *
 * Nothing is saved anywhere: a new instance starts from the bundled data.
 * Every public function is @Synchronized, so it's safe to call from any thread.
 */
class DemoDatabase(tables: DemoTables) {

    private val mics = tables.mics.toMutableList()
    private val sets = tables.sets.toMutableList()
    private val plans = tables.plans.toMutableList()
    private val scraped = tables.scrapedMics.toMutableList()
    private val scrapeLog = tables.scrapeLog.toMutableList()

    // ------------------------------------------------------------------
    // Reads (same order as the app's PostgREST queries)
    // ------------------------------------------------------------------

    /** Every open_mics row, active or not. */
    @Synchronized
    fun mics(): List<Mic> = decode(mics, Mic.serializer()).sortedBy { it.id }

    /** Every my_sets row, newest first (date desc, id desc). */
    @Synchronized
    fun sets(): List<SetEntry> = decode(sets, SetEntry.serializer())
        .sortedWith(compareByDescending<SetEntry> { it.datePerformed ?: LocalDate.MIN }.thenByDescending { it.id })

    /** mic_plans dated [from]..[to] (inclusive), by date then id. */
    @Synchronized
    fun plans(from: LocalDate, to: LocalDate): List<MicPlan> = decode(plans, MicPlan.serializer())
        .filter { plan -> plan.planDate?.let { it in from..to } == true }
        .sortedWith(compareBy<MicPlan> { it.planDate }.thenBy { it.id })

    /** scraped_mics still waiting for review (status = pending), by source then id. */
    @Synchronized
    fun pendingScraped(): List<ScrapedMic> = decode(scraped, ScrapedMic.serializer())
        .filter { it.status == STATUS_PENDING }
        .sortedWith(compareBy<ScrapedMic> { it.source ?: "" }.thenBy { it.id })

    /** scrape_log, newest first (rows without a time last), then id desc. */
    @Synchronized
    fun scrapeLog(): List<ScrapeLogEntry> = decode(scrapeLog, ScrapeLogEntry.serializer())
        .sortedWith(
            compareBy<ScrapeLogEntry> { it.lastScraped == null }
                .thenByDescending { it.lastScraped ?: "" }
                .thenByDescending { it.id },
        )

    // ------------------------------------------------------------------
    // Table writes (POST / PATCH / DELETE)
    // ------------------------------------------------------------------

    /** POST open_mics: [payload] + a new id + column defaults. */
    @Synchronized
    fun insertMic(payload: JsonObject): Mic {
        return decodeRow(insertMicRow(payload), Mic.serializer())
    }

    /** PATCH open_mics?id=eq.[micId]; null when no row matched (PostgREST returns `[]`). */
    @Synchronized
    fun updateMic(micId: Int, patch: JsonObject): Mic? =
        updateRow(mics, micId.toLong(), patch)?.let { decodeRow(it, Mic.serializer()) }

    /** POST my_sets: [payload] + a new id + column defaults. */
    @Synchronized
    fun insertSet(payload: JsonObject): SetEntry {
        return decodeRow(insertSetRow(payload), SetEntry.serializer())
    }

    /** PATCH my_sets?id=eq.[setId]; null when no row matched. */
    @Synchronized
    fun updateSet(setId: Int, patch: JsonObject): SetEntry? =
        updateRow(sets, setId.toLong(), patch)?.let { decodeRow(it, SetEntry.serializer()) }

    /** DELETE my_sets?id=eq.[setId]. Returns whether a row was deleted. */
    @Synchronized
    fun deleteSet(setId: Int): Boolean = sets.removeAll { idOf(it) == setId.toLong() }

    // ------------------------------------------------------------------
    // RPC functions (spec 3.4)
    // ------------------------------------------------------------------

    /**
     * set_plan_status: upsert the (mic, date) plan. "going" also inserts a
     * skeletal set `{open_mic_id, date_performed}` unless one already exists.
     */
    @Synchronized
    fun setPlanStatus(micId: Int, date: LocalDate, status: String) {
        if (PlanStatus.entries.none { it.raw == status }) {
            throw DemoDatabaseException("Invalid plan status \"$status\" (expected going or cancelled).")
        }
        requireMic(micId)
        val index = plans.indexOfFirst { isFor(it, micId, date, dateColumn = "plan_date") }
        if (index >= 0) {
            plans[index] = JsonObject(plans[index] + ("status" to JsonPrimitive(status)))
        } else {
            plans += JsonObject(
                mapOf(
                    "id" to JsonPrimitive(nextId(plans)),
                    "open_mic_id" to JsonPrimitive(micId),
                    "plan_date" to JsonPrimitive(date.toString()),
                    "status" to JsonPrimitive(status),
                ),
            )
        }
        if (status == PlanStatus.GOING.raw && sets.none { isFor(it, micId, date, dateColumn = "date_performed") }) {
            insertSetRow(
                JsonObject(mapOf("open_mic_id" to JsonPrimitive(micId), "date_performed" to JsonPrimitive(date.toString()))),
            )
        }
    }

    /** clear_plan: delete the (mic, date) plan. A skeletal set is left alone (same as the web). */
    @Synchronized
    fun clearPlan(micId: Int, date: LocalDate) {
        plans.removeAll { isFor(it, micId, date, dateColumn = "plan_date") }
    }

    /**
     * delete_planned_set: only when that set has no rating, delete it and the
     * matching plan. Returns whether it deleted.
     */
    @Synchronized
    fun deletePlannedSet(setId: Int): Boolean {
        val row = sets.firstOrNull { idOf(it) == setId.toLong() } ?: return false
        if (JsonValues.int(row["set_rating"]) != null) return false
        sets.remove(row)
        val micId = JsonValues.int(row["open_mic_id"])
        val date = JsonValues.date(row["date_performed"])
        if (micId != null && date != null) {
            plans.removeAll { isFor(it, micId, date, dateColumn = "plan_date") }
        }
        return true
    }

    /** delete_mic: soft delete (is_active = false) and remove all of the mic's plans. */
    @Synchronized
    fun deleteMic(micId: Int) {
        updateRow(mics, micId.toLong(), JsonObject(mapOf("is_active" to JsonPrimitive(false))))
        plans.removeAll { JsonValues.int(it["open_mic_id"]) == micId }
    }

    /**
     * import_scraped_mic: copy the scraped row into open_mics and return the
     * new mic id. Like the SQL: start from the row's own columns, let the
     * full `payload` win, keep only real open_mics columns, coerce numbers and
     * booleans, leave out null/blank values (so defaults apply), always
     * is_active = true. Then mark the scraped row "added".
     */
    @Synchronized
    fun importScrapedMic(scrapedId: Long): Int {
        val index = scraped.indexOfFirst { idOf(it) == scrapedId }
        if (index < 0) throw DemoDatabaseException("Scraped mic $scrapedId was not found.")
        val row = scraped[index]
        val name = JsonValues.text(row["name"])
        if (JsonValues.text(row["status"]) == STATUS_ADDED) {
            throw DemoDatabaseException("\"$name\" was already added.")
        }

        val source = LinkedHashMap<String, JsonElement>()
        for (column in SCRAPED_DENORMALIZED_COLUMNS) {
            row[column]?.takeUnless { it is JsonNull }?.let { source[column] = it }
        }
        (row["payload"] as? JsonObject)?.forEach { (key, value) ->
            if (value !is JsonNull) source[key] = value
        }

        val clean = LinkedHashMap<String, JsonElement>()
        for ((column, type) in OPEN_MICS_IMPORT_COLUMNS) {
            val value = source[column] ?: continue
            val primitive = value as? JsonPrimitive
            val text = primitive?.content?.trim()
            if (primitive != null && primitive.isString && text.isNullOrEmpty()) {
                // Blank = not provided (a required text column keeps "", like the SQL).
                if (column in OPEN_MICS_REQUIRED) clean[column] = JsonPrimitive("")
                continue
            }
            val coerced: JsonElement? = when (type) {
                // JSON true/false as is; a JSON number → "not zero"; text → one of TRUE_WORDS.
                ColumnType.BOOLEAN -> when {
                    primitive == null -> null
                    !primitive.isString && (text == "true" || text == "false") -> JsonPrimitive(text == "true")
                    !primitive.isString -> JsonValues.double(primitive)?.let { JsonPrimitive(it != 0.0) }
                    else -> JsonPrimitive(text?.lowercase() in TRUE_WORDS)
                }
                ColumnType.INTEGER -> JsonValues.double(value)?.let { JsonPrimitive(it.roundToLong()) }
                ColumnType.REAL -> JsonValues.double(value)?.let { JsonPrimitive(it) }
                // Text columns keep the scraper's string exactly as it was.
                ColumnType.TEXT -> JsonPrimitive(primitive?.content ?: value.toString())
            }
            if (coerced != null) clean[column] = coerced
        }
        clean["is_active"] = JsonPrimitive(true)

        for (column in OPEN_MICS_REQUIRED) {
            if (column !in clean) throw DemoDatabaseException("Can't import \"$name\": the listing has no $column.")
        }

        val mic = insertMicRow(JsonObject(clean))
        scraped[index] = JsonObject(row + ("status" to JsonPrimitive(STATUS_ADDED)))
        return idOf(mic)!!.toInt()
    }

    /** dismiss_scraped_mic: mark the row "dismissed" (it stays in the table). */
    @Synchronized
    fun dismissScrapedMic(scrapedId: Long) {
        val index = scraped.indexOfFirst { idOf(it) == scrapedId }
        if (index >= 0) scraped[index] = JsonObject(scraped[index] + ("status" to JsonPrimitive(STATUS_DISMISSED)))
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun idOf(row: JsonObject): Long? = JsonValues.long(row["id"])

    /** "New ids continue from the max existing id" (spec 8.2). */
    private fun nextId(table: List<JsonObject>): Long = (table.mapNotNull { idOf(it) }.maxOrNull() ?: 0L) + 1L

    /** True when [row] belongs to [micId] on [date] (date read from [dateColumn]). */
    private fun isFor(row: JsonObject, micId: Int, date: LocalDate, dateColumn: String): Boolean =
        JsonValues.int(row["open_mic_id"]) == micId && JsonValues.date(row[dateColumn]) == date

    /** The foreign key on open_mic_id: the mic must exist. */
    private fun requireMic(micId: Int) {
        if (mics.none { idOf(it) == micId.toLong() }) throw DemoDatabaseException("Mic $micId doesn't exist.")
    }

    /**
     * INSERT into [rows] (the table called [tableName]): column defaults, then
     * the payload (its own id is ignored), then a new id. A missing NOT NULL
     * column fails like Postgres would.
     */
    private fun insertRow(
        rows: MutableList<JsonObject>,
        tableName: String,
        defaults: Map<String, JsonElement>,
        required: Set<String>,
        payload: JsonObject,
    ): JsonObject {
        val row = JsonObject(defaults + payload.filterKeys { it != "id" } + ("id" to JsonPrimitive(nextId(rows))))
        for (column in required) {
            if (row[column] == null || row[column] is JsonNull) {
                throw DemoDatabaseException("null value in column \"$column\" of relation \"$tableName\" violates not-null constraint")
            }
        }
        rows += row
        return row
    }

    private fun insertMicRow(payload: JsonObject): JsonObject =
        insertRow(mics, "open_mics", OPEN_MICS_DEFAULTS, OPEN_MICS_REQUIRED, payload)

    private fun insertSetRow(payload: JsonObject): JsonObject =
        insertRow(sets, "my_sets", MY_SETS_DEFAULTS, MY_SETS_REQUIRED, payload)

    /** UPDATE … WHERE id = [id]: the patch's keys replace the row's (id can't change). */
    private fun updateRow(table: MutableList<JsonObject>, id: Long, patch: JsonObject): JsonObject? {
        val index = table.indexOfFirst { idOf(it) == id }
        if (index < 0) return null
        val updated = JsonObject(table[index] + patch.filterKeys { it != "id" })
        table[index] = updated
        return updated
    }

    private fun <T> decode(rows: List<JsonObject>, serializer: KSerializer<T>): List<T> =
        CoreJson.decodeList(JsonArray(rows), serializer)

    private fun <T> decodeRow(row: JsonObject, serializer: KSerializer<T>): T =
        CoreJson.decodeOrNull(row, serializer) ?: throw DemoDatabaseException("The demo row couldn't be read.")

    /** Postgres column types that import_scraped_mic coerces differently. */
    private enum class ColumnType { TEXT, INTEGER, REAL, BOOLEAN }

    companion object {
        /** Builds a database from the bundled demo file, resolving its dates for [today]. */
        fun fromJson(jsonText: String, today: LocalDate): DemoDatabase = DemoDatabase(DemoData.resolve(jsonText, today))

        const val STATUS_PENDING = "pending"
        const val STATUS_ADDED = "added"
        const val STATUS_DISMISSED = "dismissed"

        private val TRUE_WORDS = setOf("1", "true", "t", "yes", "y", "on")

        /** open_mics column DEFAULTs (utils/database.py). */
        private val OPEN_MICS_DEFAULTS: Map<String, JsonElement> = mapOf(
            "urgency" to JsonPrimitive("normal"),
            "advance_days" to JsonPrimitive(0),
            "is_active" to JsonPrimitive(true),
            "is_biweekly" to JsonPrimitive(false),
        )

        /** open_mics NOT NULL columns without a default. */
        private val OPEN_MICS_REQUIRED = setOf("name", "venue", "day_of_week", "start_time")

        /** my_sets column DEFAULTs. */
        private val MY_SETS_DEFAULTS: Map<String, JsonElement> = mapOf(
            "new_material" to JsonPrimitive(false),
            "got_feedback" to JsonPrimitive(false),
            "would_return" to JsonPrimitive(true),
        )

        private val MY_SETS_REQUIRED = setOf("date_performed")

        /** scraped_mics' own columns that import starts from (the payload then wins). */
        private val SCRAPED_DENORMALIZED_COLUMNS = listOf(
            "name", "venue", "day_of_week", "address", "display_time", "start_time", "cost", "signup_url",
        )

        /** Every open_mics column import may fill (not id / is_active / created_at / updated_at). */
        private val OPEN_MICS_IMPORT_COLUMNS: List<Pair<String, ColumnType>> = listOf(
            "name" to ColumnType.TEXT,
            "venue" to ColumnType.TEXT,
            "address" to ColumnType.TEXT,
            "neighborhood" to ColumnType.TEXT,
            "borough" to ColumnType.TEXT,
            "day_of_week" to ColumnType.TEXT,
            "start_time" to ColumnType.TEXT,
            "display_time" to ColumnType.TEXT,
            "end_time" to ColumnType.TEXT,
            "cost" to ColumnType.TEXT,
            "set_length_min" to ColumnType.INTEGER,
            "signup_method" to ColumnType.TEXT,
            "signup_url" to ColumnType.TEXT,
            "signup_notes" to ColumnType.TEXT,
            "venue_url" to ColumnType.TEXT,
            "instagram" to ColumnType.TEXT,
            "urgency" to ColumnType.TEXT,
            "urgency_note" to ColumnType.TEXT,
            "advance_days" to ColumnType.INTEGER,
            "mic_rating" to ColumnType.REAL,
            "notes" to ColumnType.TEXT,
            "latitude" to ColumnType.REAL,
            "longitude" to ColumnType.REAL,
            "is_biweekly" to ColumnType.BOOLEAN,
        )
    }
}
