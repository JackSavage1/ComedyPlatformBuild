package com.comedyplatform.mictracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalDate

/**
 * Navigation routes (plain strings with arguments — the long-stable
 * Navigation Compose API).
 */
object Routes {
    const val TONIGHT = "tonight"
    const val CALENDAR = "calendar"
    const val MAP = "map"
    const val SETS = "sets"
    const val STATS = "stats"
    const val MANAGE = "manage"

    /** Mic Detail: required mic id, optional "yyyy-MM-dd" date context. */
    const val MIC_DETAIL = "mic/{micId}?date={date}"
    const val ARG_MIC_ID = "micId"
    const val ARG_DATE = "date"

    /** Mic Form: optional mic id to edit (absent = add a new mic). */
    const val MIC_FORM = "micform?editMicId={editMicId}"
    const val ARG_EDIT_MIC_ID = "editMicId"

    /** "mic/12" or "mic/12?date=2026-10-20". */
    fun micDetail(micId: Int, date: LocalDate?): String =
        if (date == null) "mic/$micId" else "mic/$micId?date=$date"

    /** "micform" or "micform?editMicId=12". */
    fun micForm(editMicId: Int?): String =
        if (editMicId == null) "micform" else "micform?editMicId=$editMicId"
}

/** The 5 bottom tabs, in spec order (section 5). */
enum class TopLevelTab(val route: String, val label: String, val icon: ImageVector) {
    TONIGHT(Routes.TONIGHT, "Tonight", Icons.Filled.Home),
    CALENDAR(Routes.CALENDAR, "Calendar", Icons.Filled.DateRange),
    MAP(Routes.MAP, "Map", Icons.Filled.Map),
    SETS(Routes.SETS, "My Sets", Icons.Filled.Mic),
    STATS(Routes.STATS, "Stats", Icons.Filled.BarChart),
    ;

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
    }
}
