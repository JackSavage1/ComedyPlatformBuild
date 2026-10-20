package com.comedyplatform.mictracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.ui.theme.AppColors

/** The look of a [Callout]: like Streamlit's st.success / st.error / st.warning / st.info. */
enum class CalloutTone { SUCCESS, ERROR, WARNING, INFO }

private fun CalloutTone.color(): Color = when (this) {
    CalloutTone.SUCCESS -> AppColors.Success
    CalloutTone.ERROR -> AppColors.Danger
    CalloutTone.WARNING -> AppColors.Warning
    CalloutTone.INFO -> AppColors.Info
}

/** A tinted message box. */
@Composable
fun Callout(text: String, tone: CalloutTone, modifier: Modifier = Modifier) {
    val color = tone.color()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Spec 6.4: "You're going to this mic!" (green) / "This mic is marked as cancelled" (red). */
@Composable
fun PlanStatusBanner(status: PlanStatus?, modifier: Modifier = Modifier) {
    when (status) {
        PlanStatus.GOING -> Callout("✅ You're going to this mic!", CalloutTone.SUCCESS, modifier)
        PlanStatus.CANCELLED -> Callout("❌ This mic is marked as cancelled", CalloutTone.ERROR, modifier)
        null -> Unit
    }
}

/** A section title with an optional caption underneath. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
