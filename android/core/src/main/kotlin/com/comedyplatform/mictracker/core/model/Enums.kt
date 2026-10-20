package com.comedyplatform.mictracker.core.model

import com.comedyplatform.mictracker.core.rules.TextRules

// =============================================================================
// Enums for the text "enum" columns. The database stores plain text, and
// scrapers may write values we don't know, so every `from(raw)` is forgiving:
// unknown text maps to a fallback and the UI shows the raw text prettified.
//
// Colors are ARGB Longs (0xAARRGGBB) because :core can't depend on Compose;
// in the app use `Color(urgency.colorArgb)`.
// =============================================================================

/** Spec rule 3. Anything that isn't "high" or "medium" counts as NORMAL. */
enum class Urgency(val raw: String, val dot: String, val label: String, val colorArgb: Long) {
    HIGH("high", "🔴", "High", 0xFFE74C3C),
    MEDIUM("medium", "🟡", "Medium", 0xFFF1C40F),
    NORMAL("normal", "🟢", "Normal", 0xFF2ECC71);

    /** Shown when a mic has no `urgency_note` of its own (null for NORMAL). */
    val defaultNote: String?
        get() = when (this) {
            HIGH -> "Signs up fast — book ahead!"
            MEDIUM -> "Book a few days early"
            NORMAL -> null
        }

    companion object {
        /** Picker order used by the mic form: normal, medium, high. */
        val pickerOrder: List<Urgency> = listOf(NORMAL, MEDIUM, HIGH)

        fun from(raw: String?): Urgency = when (TextRules.trimmedOrNull(raw)?.lowercase()) {
            "high" -> HIGH
            "medium" -> MEDIUM
            else -> NORMAL
        }
    }
}

/** Spec rule 2: the cost bucket behind the little badge next to a mic's cost. */
enum class CostKind(val emoji: String, val label: String) {
    FREE("🆓", "Free"),
    DRINK_MIN("🍺", "Drink min"),
    PAID("💵", "Paid");

    companion object {
        /** cost (null ⇒ "free") lowercased: contains "free" → FREE, "drink" → DRINK_MIN, else PAID. */
        fun of(cost: String?): CostKind {
            val lower = (TextRules.trimmedOrNull(cost) ?: "free").lowercase()
            return when {
                lower.contains("free") -> FREE
                lower.contains("drink") -> DRINK_MIN
                else -> PAID
            }
        }
    }
}

/** How you get on a mic's list. Unknown values become OTHER. */
enum class SignupMethod(val raw: String, val label: String) {
    IN_PERSON("in_person", "In Person"),
    ONLINE("online", "Online"),
    EMAIL("email", "Email"),
    INSTAGRAM_DM("instagram_dm", "Instagram DM"),
    OTHER("", "Other");

    companion object {
        /** The four real methods, in the web form's order. */
        val pickerOptions: List<SignupMethod> = listOf(IN_PERSON, ONLINE, EMAIL, INSTAGRAM_DM)

        /** null for null/blank text, OTHER for unknown text. */
        fun from(raw: String?): SignupMethod? {
            val cleaned = TextRules.trimmedOrNull(raw)?.lowercase() ?: return null
            return entries.firstOrNull { it != OTHER && it.raw == cleaned } ?: OTHER
        }

        /** "In Person", "Instagram DM", or the raw text prettified ("walk_up" → "Walk Up"). */
        fun displayText(raw: String?): String? {
            val method = from(raw) ?: return null
            return if (method == OTHER) TextRules.prettify(raw) else method.label
        }
    }
}

/** `my_sets.crowd_size`. [bubbleSize] is the Plotly marker size the web dashboard used. */
enum class CrowdSize(val raw: String, val label: String, val bubbleSize: Int) {
    EMPTY("empty", "Empty", 10),
    SPARSE("sparse", "Sparse", 20),
    DECENT("decent", "Decent", 35),
    PACKED("packed", "Packed", 50);

    companion object {
        /** Bubble size for an unknown/missing crowd size. */
        const val UNKNOWN_BUBBLE_SIZE = 20

        fun from(raw: String?): CrowdSize? {
            val cleaned = TextRules.trimmedOrNull(raw)?.lowercase() ?: return null
            return entries.firstOrNull { it.raw == cleaned }
        }

        fun bubbleSize(raw: String?): Int = from(raw)?.bubbleSize ?: UNKNOWN_BUBBLE_SIZE

        /** "Decent", or the raw text prettified for unknown values, or null. */
        fun displayText(raw: String?): String? =
            from(raw)?.label ?: TextRules.trimmedOrNull(raw)?.let { TextRules.prettify(it) }
    }
}

/** `my_sets.recording_type`. */
enum class RecordingType(val raw: String, val label: String) {
    NONE("none", "None"),
    VIDEO("video", "Video"),
    AUDIO("audio", "Audio");

    companion object {
        fun from(raw: String?): RecordingType? {
            val cleaned = TextRules.trimmedOrNull(raw)?.lowercase() ?: return null
            return entries.firstOrNull { it.raw == cleaned }
        }
    }
}

/** `mic_plans.status`. */
enum class PlanStatus(val raw: String) {
    GOING("going"),
    CANCELLED("cancelled");

    companion object {
        fun from(raw: String?): PlanStatus? {
            val cleaned = TextRules.trimmedOrNull(raw)?.lowercase() ?: return null
            return entries.firstOrNull { it.raw == cleaned }
        }
    }
}
