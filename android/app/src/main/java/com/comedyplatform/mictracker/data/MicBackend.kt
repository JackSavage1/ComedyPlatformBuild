package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicPlan
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.Rpc
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.model.SetEntry
import com.comedyplatform.mictracker.core.model.Tables
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Where [MicRepository] reads and changes its data. Two implementations:
 *
 *   RemoteMicBackend  the real Supabase project over REST (normal use)
 *   DemoMicBackend    an in-memory copy of the bundled demo data (spec 8)
 *
 * The repository (and so every screen) works the same with either one —
 * screens never know which backend is active.
 *
 * Every function throws [ApiException] (message safe to show) on failure.
 * Payloads are the app's normal JSON bodies (MicDraft.toInsertPayload(), ...).
 */
interface MicBackend {

    /** Every open_mics row, including inactive ones. */
    suspend fun fetchMics(): List<Mic>

    /** Every my_sets row, newest first (date desc, id desc). */
    suspend fun fetchSets(): List<SetEntry>

    /** mic_plans dated [from]..[to] (inclusive), by date then id. */
    suspend fun fetchPlans(from: LocalDate, to: LocalDate): List<MicPlan>

    /** scraped_mics with status = pending. */
    suspend fun fetchPendingScraped(): List<ScrapedMic>

    /** scrape_log, newest first. */
    suspend fun fetchScrapeLog(): List<ScrapeLogEntry>

    /** INSERT a mic; returns the stored row, or null when nothing came back. */
    suspend fun insertMic(payload: JsonObject): Mic?

    /** UPDATE one mic; returns the updated row, or null when no row matched. */
    suspend fun updateMic(micId: Int, patch: JsonObject): Mic?

    /** INSERT a set; returns the stored row, or null when nothing came back. */
    suspend fun insertSet(payload: JsonObject): SetEntry?

    /** UPDATE one set; returns the updated row, or null when no row matched. */
    suspend fun updateSet(setId: Int, patch: JsonObject): SetEntry?

    /** DELETE one set. */
    suspend fun deleteSet(setId: Int)

    /** RPC set_plan_status ("going" also creates a skeletal set). */
    suspend fun setPlanStatus(micId: Int, date: LocalDate, status: PlanStatus)

    /** RPC clear_plan. */
    suspend fun clearPlan(micId: Int, date: LocalDate)

    /** RPC delete_planned_set; returns whether a row was deleted. */
    suspend fun deletePlannedSet(setId: Int): Boolean

    /** RPC delete_mic (soft delete + remove its plans). */
    suspend fun deleteMic(micId: Int)

    /** RPC import_scraped_mic; returns the new open_mics id (null if none came back). */
    suspend fun importScrapedMic(scrapedId: Long): Int?

    /** RPC dismiss_scraped_mic. */
    suspend fun dismissScrapedMic(scrapedId: Long)
}

/**
 * The real backend: Supabase's PostgREST API through [ApiClient]
 * (spec 3.1 / 3.4). Queries carry the server-side order the repository
 * relies on.
 */
class RemoteMicBackend(private val api: ApiClient) : MicBackend {

    override suspend fun fetchMics(): List<Mic> = api.selectList(Tables.OPEN_MICS, Mic.serializer())

    override suspend fun fetchSets(): List<SetEntry> =
        api.selectList(
            Tables.MY_SETS,
            SetEntry.serializer(),
            listOf("order" to "date_performed.desc,id.desc"),
        )

    override suspend fun fetchPlans(from: LocalDate, to: LocalDate): List<MicPlan> =
        api.selectList(
            Tables.MIC_PLANS,
            MicPlan.serializer(),
            listOf(
                "plan_date" to "gte.$from",
                "plan_date" to "lte.$to",
                "order" to "plan_date.asc,id.asc",
            ),
        )

    override suspend fun fetchPendingScraped(): List<ScrapedMic> =
        api.selectList(
            Tables.SCRAPED_MICS,
            ScrapedMic.serializer(),
            listOf("status" to "eq.pending", "order" to "source.asc,id.asc"),
        )

    override suspend fun fetchScrapeLog(): List<ScrapeLogEntry> =
        api.selectList(
            Tables.SCRAPE_LOG,
            ScrapeLogEntry.serializer(),
            listOf("order" to "last_scraped.desc.nullslast,id.desc"),
        )

    override suspend fun insertMic(payload: JsonObject): Mic? =
        CoreJson.decodeFirst(api.insert(Tables.OPEN_MICS, payload), Mic.serializer())

    override suspend fun updateMic(micId: Int, patch: JsonObject): Mic? =
        CoreJson.decodeFirst(api.update(Tables.OPEN_MICS, listOf("id" to "eq.$micId"), patch), Mic.serializer())

    override suspend fun insertSet(payload: JsonObject): SetEntry? =
        CoreJson.decodeFirst(api.insert(Tables.MY_SETS, payload), SetEntry.serializer())

    override suspend fun updateSet(setId: Int, patch: JsonObject): SetEntry? =
        CoreJson.decodeFirst(api.update(Tables.MY_SETS, listOf("id" to "eq.$setId"), patch), SetEntry.serializer())

    override suspend fun deleteSet(setId: Int) {
        api.delete(Tables.MY_SETS, listOf("id" to "eq.$setId"))
    }

    override suspend fun setPlanStatus(micId: Int, date: LocalDate, status: PlanStatus) {
        api.rpc(Rpc.SET_PLAN_STATUS, Rpc.setPlanStatusArgs(micId, date, status))
    }

    override suspend fun clearPlan(micId: Int, date: LocalDate) {
        api.rpc(Rpc.CLEAR_PLAN, Rpc.clearPlanArgs(micId, date))
    }

    override suspend fun deletePlannedSet(setId: Int): Boolean =
        JsonValues.boolean(api.rpc(Rpc.DELETE_PLANNED_SET, Rpc.deletePlannedSetArgs(setId))) == true

    override suspend fun deleteMic(micId: Int) {
        api.rpc(Rpc.DELETE_MIC, Rpc.deleteMicArgs(micId))
    }

    override suspend fun importScrapedMic(scrapedId: Long): Int? =
        JsonValues.int(api.rpc(Rpc.IMPORT_SCRAPED_MIC, Rpc.importScrapedMicArgs(scrapedId)))

    override suspend fun dismissScrapedMic(scrapedId: Long) {
        api.rpc(Rpc.DISMISS_SCRAPED_MIC, Rpc.dismissScrapedMicArgs(scrapedId))
    }
}
