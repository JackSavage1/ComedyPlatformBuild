package com.comedyplatform.mictracker.core.rules

/**
 * Rule 14: set tags.
 *
 * Stored in `my_sets.tags` comma-joined with no spaces, e.g. "killed,crowd_work".
 */
object TagRules {

    /** The fixed options, in the web form's order. */
    val options: List<String> = listOf(
        "killed", "bombed", "new_bit", "crowd_work", "got_laughs",
        "nervous", "confident", "short_set", "long_set", "got_spotted",
    )

    private val positive = setOf("killed", "got_laughs", "confident", "crowd_work", "got_spotted")
    private val negative = setOf("bombed", "nervous")

    /** Green / red / blue. */
    enum class Tone(val colorArgb: Long) {
        POSITIVE(0xFF2ECC71),
        NEGATIVE(0xFFE74C3C),
        NEUTRAL(0xFF3498DB),
    }

    fun tone(tag: String): Tone {
        val key = tag.trim().lowercase()
        return when (key) {
            in positive -> Tone.POSITIVE
            in negative -> Tone.NEGATIVE
            else -> Tone.NEUTRAL
        }
    }

    fun colorArgb(tag: String): Long = tone(tag).colorArgb

    /** Split on ",", trim, drop empties: " killed, ,crowd_work " → [killed, crowd_work]. */
    fun parse(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Comma-joined with no spaces, or null when there are no tags. Keeps order, drops duplicates. */
    fun join(tags: Collection<String>): String? {
        val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (cleaned.isEmpty()) null else cleaned.joinToString(",")
    }

    /** "crowd_work" → "Crowd Work" for chips. */
    fun label(tag: String): String = TextRules.prettify(tag)
}
