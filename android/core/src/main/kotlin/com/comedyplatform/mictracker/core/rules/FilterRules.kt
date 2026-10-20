package com.comedyplatform.mictracker.core.rules

import com.comedyplatform.mictracker.core.model.Mic

/**
 * Rule 4: the Cost filter.
 */
enum class CostFilter(val label: String) {
    ALL("All"),
    FREE_ONLY("Free only"),
    UNDER_6("Under $6"),
    DRINK_MIN_ONLY("Drink min only");

    fun matches(cost: String?): Boolean {
        val lower = TextRules.trimmedOrNull(cost)?.lowercase()
        return when (this) {
            ALL -> true
            // cost null or contains "free"
            FREE_ONLY -> lower == null || lower.contains("free")
            // FIX vs Streamlit: cost null, contains "free", or the FIRST $ amount is < 6
            // (Streamlit's regex `\$[1-5]` wrongly matched "$10").
            UNDER_6 -> {
                if (lower == null || lower.contains("free")) {
                    true
                } else {
                    val amount = FilterRules.firstDollarAmount(lower)
                    amount != null && amount < 6.0
                }
            }
            DRINK_MIN_ONLY -> lower != null && lower.contains("drink")
        }
    }
}

/**
 * Rule 5: the Signup Type filter.
 */
enum class SignupFilter(val label: String, val raw: String?) {
    ALL("All", null),
    ONLINE("Online", "online"),
    IN_PERSON("In-person", "in_person"),
    EMAIL("Email", "email"),
    INSTAGRAM_DM("Instagram DM", "instagram_dm");

    fun matches(signupMethod: String?): Boolean {
        if (raw == null) return true
        return TextRules.trimmedOrNull(signupMethod)?.lowercase() == raw
    }
}

object FilterRules {

    /** "$5", "$5.50", "$ 5" → the amount; the first one wins. */
    private val dollarAmount = Regex("""\$\s*(\d+(?:\.\d{1,2})?)""")

    /** The first `$N` / `$N.NN` amount in [text], or null when there isn't one. */
    fun firstDollarAmount(text: String?): Double? {
        val match = dollarAmount.find(text ?: return null) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }
}

/**
 * Every filter on the Calendar's filter sheet (spec 6.3), in one value.
 * Empty sets mean "no filter".
 */
data class MicFilters(
    val boroughs: Set<String> = emptySet(),
    val neighborhoods: Set<String> = emptySet(),
    val cost: CostFilter = CostFilter.ALL,
    val signup: SignupFilter = SignupFilter.ALL,
    val onlyUnvisited: Boolean = false,
    val onlyGoing: Boolean = false,
) {
    /** For the filter button's badge. */
    val activeCount: Int
        get() = listOf(
            boroughs.isNotEmpty(),
            neighborhoods.isNotEmpty(),
            cost != CostFilter.ALL,
            signup != SignupFilter.ALL,
            onlyUnvisited,
            onlyGoing,
        ).count { it }

    val isActive: Boolean get() = activeCount > 0

    /**
     * @param visitedMicIds mics with ≥1 logged set (rule 6)
     * @param goingMicIds mics marked "going" for some date in the week being shown
     */
    fun matches(mic: Mic, visitedMicIds: Set<Int>, goingMicIds: Set<Int>): Boolean {
        if (boroughs.isNotEmpty() && TextRules.trimmedOrNull(mic.borough) !in boroughs) return false
        if (neighborhoods.isNotEmpty() && TextRules.trimmedOrNull(mic.neighborhood) !in neighborhoods) return false
        if (!cost.matches(mic.cost)) return false
        if (!signup.matches(mic.signupMethod)) return false
        if (onlyUnvisited && mic.id in visitedMicIds) return false
        if (onlyGoing && mic.id !in goingMicIds) return false
        return true
    }

    fun apply(mics: List<Mic>, visitedMicIds: Set<Int>, goingMicIds: Set<Int>): List<Mic> =
        mics.filter { matches(it, visitedMicIds, goingMicIds) }

    companion object {
        /**
         * Neighborhood options for the sheet: distinct non-blank neighborhoods of
         * [mics], limited to the selected boroughs (all when none are selected), sorted.
         */
        fun neighborhoodOptions(mics: List<Mic>, selectedBoroughs: Set<String>): List<String> =
            mics.asSequence()
                .filter { selectedBoroughs.isEmpty() || TextRules.trimmedOrNull(it.borough) in selectedBoroughs }
                .mapNotNull { TextRules.trimmedOrNull(it.neighborhood) }
                .distinct()
                .sortedBy { it.lowercase() }
                .toList()
    }
}
