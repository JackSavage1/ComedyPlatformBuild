package com.comedyplatform.mictracker.core.rules

/**
 * Small text helpers shared by every rule.
 */
object TextRules {

    /**
     * Spec 3.5 "Blank = null": trimmed text, or null when the value is null or
     * whitespace-only. (The Streamlit app calls this `safe_str`.)
     */
    fun trimmedOrNull(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        return trimmed.ifEmpty { null }
    }

    /** True when [raw] has visible characters. */
    fun hasText(raw: String?): Boolean = trimmedOrNull(raw) != null

    /**
     * Turns a raw enum string into words: `in_person` → `In Person`,
     * `instagram_dm` → `Instagram Dm`, `walk-up list` → `Walk-up List`.
     */
    fun prettify(raw: String?): String {
        val cleaned = trimmedOrNull(raw) ?: return ""
        return cleaned
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
    }

    /** Joins the parts that have text: ("East Village", null, "Manhattan") → "East Village, Manhattan". */
    fun joinNonBlank(parts: List<String?>, separator: String = ", "): String =
        parts.mapNotNull { trimmedOrNull(it) }.joinToString(separator)
}
