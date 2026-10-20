package com.comedyplatform.mictracker.ui.theme

import androidx.compose.ui.graphics.Color
import com.comedyplatform.mictracker.core.model.Urgency
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.TagRules

/**
 * Fixed brand / status colors (spec section 1). These don't change between
 * light and dark mode; Material colors that do live in Theme.kt.
 */
object AppColors {
    /** Streamlit's primary red — the app's accent. */
    val Accent = Color(0xFFFF4B4B)

    val UrgencyHigh = Color(0xFFE74C3C)
    val UrgencyMedium = Color(0xFFF1C40F)
    val UrgencyNormal = Color(0xFF2ECC71)

    /** "You're going" green and "cancelled" red. */
    val Success = Color(0xFF2ECC71)
    val Danger = Color(0xFFE74C3C)

    /** Callout backgrounds (used with a low alpha). */
    val Warning = Color(0xFFF39C12)
    val Info = Color(0xFF3498DB)

    // Streamlit-like dark palette
    val DarkBackground = Color(0xFF0E1117)
    val DarkSurface = Color(0xFF161A23)
    val DarkSurfaceVariant = Color(0xFF262730)
    val DarkOnSurface = Color(0xFFFAFAFA)
    val DarkOnSurfaceVariant = Color(0xFFB4B6BE)
    val DarkOutline = Color(0xFF3D4150)

    // Light palette
    val LightBackground = Color(0xFFFFFBFF)
    val LightSurface = Color(0xFFFFFBFF)
    val LightSurfaceVariant = Color(0xFFF0F2F6)
    val LightOnSurface = Color(0xFF1C1B1F)
    val LightOnSurfaceVariant = Color(0xFF50525C)
    val LightOutline = Color(0xFFC7C9D1)
}

/** Rule 3 color for an urgency level. */
fun urgencyColor(urgency: Urgency): Color = Color(urgency.colorArgb)

/** Rule 17 color for a borough (gray for unknown). */
fun boroughColor(borough: String?): Color = Color(Boroughs.colorArgb(borough))

/** Rule 14 color for a set tag (green / red / blue). */
fun tagColor(tag: String): Color = Color(TagRules.colorArgb(tag))
