package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.demo.DemoDatabase
import com.comedyplatform.mictracker.core.demo.DemoDatabaseException
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.MicPlan
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.ScrapeLogEntry
import com.comedyplatform.mictracker.core.model.ScrapedMic
import com.comedyplatform.mictracker.core.model.SetEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Demo mode's backend (spec 8): the bundled sample data, kept in memory.
 *
 * The data is read and its relative dates resolved (with :core's
 * [DemoDatabase]) the first time anything asks for it. Every change works
 * like the real table / RPC would, but only in memory — a new instance (or a
 * relaunch) starts from the bundled file again.
 *
 * @param loadJson returns the demo file's text (AppContainer reads it from
 *   the app's assets); called once, off the main thread
 * @param today the device's local date, for resolving "2 weeks ago" etc.
 */
class DemoMicBackend(
    private val loadJson: () -> String,
    private val today: () -> LocalDate,
) : MicBackend {

    private val loadMutex = Mutex()

    @Volatile
    private var database: DemoDatabase? = null

    /** The demo database, loading it on first use. */
    private suspend fun database(): DemoDatabase {
        database?.let { return it }
        return loadMutex.withLock {
            database ?: withContext(Dispatchers.IO) {
                try {
                    DemoDatabase.fromJson(loadJson(), today())
                } catch (e: Exception) {
                    throw ApiException("Couldn't load the demo data.", cause = e)
                }
            }.also { database = it }
        }
    }

    /** Runs [block] on the demo database; its errors become [ApiException]s like a server's would. */
    private suspend fun <T> demo(block: DemoDatabase.() -> T): T {
        val db = database()
        return try {
            db.block()
        } catch (e: DemoDatabaseException) {
            throw ApiException(e.message ?: "Something went wrong.", cause = e)
        }
    }

    override suspend fun fetchMics(): List<Mic> = demo { mics() }

    override suspend fun fetchSets(): List<SetEntry> = demo { sets() }

    override suspend fun fetchPlans(from: LocalDate, to: LocalDate): List<MicPlan> = demo { plans(from, to) }

    override suspend fun fetchPendingScraped(): List<ScrapedMic> = demo { pendingScraped() }

    override suspend fun fetchScrapeLog(): List<ScrapeLogEntry> = demo { scrapeLog() }

    override suspend fun insertMic(payload: JsonObject): Mic = demo { insertMic(payload) }

    override suspend fun updateMic(micId: Int, patch: JsonObject): Mic? = demo { updateMic(micId, patch) }

    override suspend fun insertSet(payload: JsonObject): SetEntry = demo { insertSet(payload) }

    override suspend fun updateSet(setId: Int, patch: JsonObject): SetEntry? = demo { updateSet(setId, patch) }

    override suspend fun deleteSet(setId: Int) {
        demo { deleteSet(setId) }
    }

    override suspend fun setPlanStatus(micId: Int, date: LocalDate, status: PlanStatus) =
        demo { setPlanStatus(micId, date, status.raw) }

    override suspend fun clearPlan(micId: Int, date: LocalDate) = demo { clearPlan(micId, date) }

    override suspend fun deletePlannedSet(setId: Int): Boolean = demo { deletePlannedSet(setId) }

    override suspend fun deleteMic(micId: Int) = demo { deleteMic(micId) }

    override suspend fun importScrapedMic(scrapedId: Long): Int = demo { importScrapedMic(scrapedId) }

    override suspend fun dismissScrapedMic(scrapedId: Long) = demo { dismissScrapedMic(scrapedId) }
}
