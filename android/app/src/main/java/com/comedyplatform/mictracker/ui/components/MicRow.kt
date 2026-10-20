package com.comedyplatform.mictracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.CostKind
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.Urgency
import com.comedyplatform.mictracker.ui.theme.urgencyColor

/**
 * Rule 3: a small colored dot — red (high), yellow (medium), green (normal).
 */
@Composable
fun UrgencyDot(urgency: Urgency, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(color = urgencyColor(urgency), shape = CircleShape),
    )
}

/**
 * Rule 2: a small pill with the cost bucket — "🆓 Free", "🍺 Drink min", "💵 Paid".
 * Pass [text] to show the actual cost text instead of the bucket label.
 */
@Composable
fun CostBadge(cost: String?, modifier: Modifier = Modifier, text: String? = null) {
    val badge = CostKind.of(cost)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = "${badge.emoji} ${text ?: badge.label}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** "✅ " / "❌ " / "" — the plan-status prefix used on calendar cards. */
fun planStatusPrefix(status: PlanStatus?): String = when (status) {
    PlanStatus.GOING -> "✅ "
    PlanStatus.CANCELLED -> "❌ "
    null -> ""
}

/**
 * A tappable mic row: urgency dot · time · **name** · "@ venue" · cost badge.
 *
 * @param planStatus shows ✅ / ❌ before the time when set
 * @param supportingText an optional extra line (e.g. neighborhood or a signup deadline)
 * @param showCost show the cost text badge on the right
 */
@Composable
fun MicRow(
    mic: Mic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    planStatus: PlanStatus? = null,
    supportingText: String? = null,
    showCost: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UrgencyDot(mic.urgencyLevel)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = planStatusPrefix(planStatus) + mic.timeText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = mic.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (mic.venueName.isNotEmpty()) {
                Text(
                    text = "@ ${mic.venueName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showCost) {
            CostBadge(cost = mic.cost, text = mic.costText, modifier = Modifier.width(IntrinsicCostWidth))
        }
    }
}

/** Keeps long cost texts ("$5 + 1 drink min") from squeezing the mic name. */
private val IntrinsicCostWidth: Dp = 96.dp
