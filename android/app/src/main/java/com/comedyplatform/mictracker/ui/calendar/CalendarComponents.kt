package com.comedyplatform.mictracker.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.SignupDeadline
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.ui.components.CostBadge
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import com.comedyplatform.mictracker.ui.components.UrgencyDot
import com.comedyplatform.mictracker.ui.components.planStatusPrefix
import com.comedyplatform.mictracker.ui.theme.AppColors
import java.time.LocalDate

// =============================================================================
// Pieces of the Calendar screen: week header, Mon–Sun day strip, mic card.
// =============================================================================

/**
 * ◀  This Week  ▶
 *    Oct 20 – Oct 26, 2026   [Today]
 */
@Composable
internal fun WeekHeader(
    title: String,
    rangeText: String,
    showTodayButton: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showTodayButton) {
                    TextButton(onClick = onToday) { Text("Today") }
                }
            }
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
        }
    }
}

/** Seven chips, Mon → Sun: "Mon" / "10/20" / count of filtered mics. */
@Composable
internal fun DayStrip(
    days: List<CalendarDay>,
    selectedDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { day ->
            DayChip(
                day = day,
                selected = day.date == selectedDate,
                onClick = { onSelect(day.date) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Today's chip gets the accent-red outline and text (like the web's red
 * underline); past days are dimmed; the selected chip is filled.
 */
@Composable
private fun DayChip(day: CalendarDay, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val accentText = if (day.isToday) AppColors.Accent else content
    Surface(
        onClick = onClick,
        modifier = modifier.alpha(if (day.isPast && !selected) 0.5f else 1f),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        border = if (day.isToday) BorderStroke(2.dp, AppColors.Accent) else null,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = DateRules.shortWeekday(day.date),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                color = accentText,
                maxLines = 1,
            )
            Text(
                text = DateRules.monthDay(day.date),
                style = MaterialTheme.typography.labelSmall,
                color = accentText,
                maxLines = 1,
            )
            Text(
                text = day.count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * A mic card for the selected day, wrapped in swipe actions:
 *   swipe right → Going, swipe left → Cancelled.
 * Long-press opens the same actions (plus Clear) in a menu; tap opens Mic Detail.
 * The card always springs back — the ✅ / ❌ prefix shows the new status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableMicCard(
    item: CalendarMicItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onGoing: () -> Unit,
    onCancelled: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The swipe state is remembered once per card, so read the latest values
    // through rememberUpdatedState instead of capturing stale ones.
    val currentStatus by rememberUpdatedState(item.planStatus)
    val currentOnGoing by rememberUpdatedState(onGoing)
    val currentOnCancelled by rememberUpdatedState(onCancelled)

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> if (currentStatus != PlanStatus.GOING) currentOnGoing()
                SwipeToDismissBoxValue.EndToStart -> if (currentStatus != PlanStatus.CANCELLED) currentOnCancelled()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // never stay dismissed
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier,
        backgroundContent = { SwipeBackground(direction = swipeState.dismissDirection) },
    ) {
        MicCard(
            item = item,
            busy = busy,
            onOpen = onOpen,
            onGoing = onGoing,
            onCancelled = onCancelled,
            onClear = onClear,
        )
    }
}

/** Green "Going" behind a right swipe, red "Cancelled" behind a left swipe. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(AppColors.Success, "✅ Going", Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> Triple(AppColors.Danger, "❌ Cancelled", Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, "", Alignment.Center)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = color, shape = CardShape)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Text(text = label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/**
 * The card itself:
 *   ✅ 🟢 7:00 PM
 *   **Mic name**
 *   🆓 Free · East Village 🔄
 *   📅 Sign up by Thu 10/23 (3 days)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicCard(
    item: CalendarMicItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onGoing: () -> Unit,
    onCancelled: () -> Unit,
    onClear: () -> Unit,
) {
    val mic = item.mic
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val prefix = planStatusPrefix(item.planStatus)
                        if (prefix.isNotEmpty()) Text(text = prefix, style = MaterialTheme.typography.labelLarge)
                        UrgencyDot(mic.urgencyLevel)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = mic.timeText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = mic.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CostBadge(cost = mic.cost)
                        val details = cardDetails(mic)
                        if (details.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item.deadline?.let { deadline ->
                        Text(
                            text = deadline.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (deadline.isUrgent) FontWeight.Bold else FontWeight.Normal,
                            color = deadlineColor(deadline),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (busy) SmallSpinner(modifier = Modifier.padding(start = 8.dp))
            }
        }

        // Long-press menu: only the actions that change the state (like the web buttons).
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (item.planStatus != PlanStatus.GOING) {
                DropdownMenuItem(
                    text = { Text("✅ Going") },
                    onClick = {
                        menuOpen = false
                        onGoing()
                    },
                )
            }
            if (item.planStatus != PlanStatus.CANCELLED) {
                DropdownMenuItem(
                    text = { Text("❌ Cancelled") },
                    onClick = {
                        menuOpen = false
                        onCancelled()
                    },
                )
            }
            if (item.planStatus != null) {
                DropdownMenuItem(
                    text = { Text("Clear") },
                    onClick = {
                        menuOpen = false
                        onClear()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Details") },
                onClick = {
                    menuOpen = false
                    onOpen()
                },
            )
        }
    }
}

/** "East Village 🔄" — neighborhood plus the biweekly marker. */
private fun cardDetails(mic: Mic): String {
    val neighborhood = TextRules.trimmedOrNull(mic.neighborhood) ?: ""
    val biweekly = if (mic.isBiweeklyMic) "🔄" else ""
    return listOf(neighborhood, biweekly).filter { it.isNotEmpty() }.joinToString(" ")
}

@Composable
private fun deadlineColor(deadline: SignupDeadline): Color = when (deadline) {
    SignupDeadline.Passed -> MaterialTheme.colorScheme.error
    SignupDeadline.Today -> AppColors.UrgencyHigh
    SignupDeadline.Tomorrow -> AppColors.Warning
    is SignupDeadline.Upcoming -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val CardShape = RoundedCornerShape(12.dp)
