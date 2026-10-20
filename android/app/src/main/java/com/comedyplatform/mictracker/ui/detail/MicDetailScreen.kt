package com.comedyplatform.mictracker.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.Urgency
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.InstagramRules
import com.comedyplatform.mictracker.core.rules.SignupDeadline
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.ui.components.Callout
import com.comedyplatform.mictracker.ui.components.CalloutTone
import com.comedyplatform.mictracker.ui.components.ConfirmDialog
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LinkOpener
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.PlanStatusBanner
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import com.comedyplatform.mictracker.ui.components.UrgencyDot
import com.comedyplatform.mictracker.ui.components.rememberLinkOpener
import java.time.LocalDate
import java.util.Locale

/**
 * Mic Detail (spec 6.4) — shared by Tonight, Calendar, Map and Manage.
 *
 * @param micId the mic to show
 * @param date the date context ("this mic on Tue 10/21"), or null from Manage.
 *   With a date: status banner, signup deadline, and Going / Cancelled / Clear.
 * @param onBack pop this screen (also called after a successful delete)
 * @param onEdit open the Mic Form for this mic
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicDetailScreen(
    micId: Int,
    date: LocalDate?,
    onBack: () -> Unit,
    onEdit: (micId: Int) -> Unit,
) {
    val viewModel = appViewModel(key = "mic-detail-$micId-$date") { container ->
        MicDetailViewModel(
            repository = container.micRepository,
            micId = micId,
            date = date,
            today = { container.today() },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Go back once the mic has been deleted.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    // One-off success messages.
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.mic?.displayName ?: "Mic",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val mic = state.mic
        when {
            mic == null && !state.hasLoaded -> LoadingBox(modifier = Modifier.padding(padding))
            mic == null -> Column(modifier = Modifier.padding(padding)) {
                EmptyState(
                    title = "Mic not found",
                    message = "This mic may have been removed. Pull down on a list to refresh.",
                    action = { TextButton(onClick = onBack) { Text("Go back") } },
                )
            }
            else -> RefreshableBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                MicDetailContent(
                    mic = mic,
                    state = state,
                    onDismissError = viewModel::dismissError,
                    onGoing = viewModel::markGoing,
                    onCancelled = viewModel::markCancelled,
                    onClear = viewModel::clearPlan,
                    onRatingChange = viewModel::onRatingChange,
                    onSaveRating = viewModel::saveRating,
                    onEdit = { onEdit(mic.id) },
                    onDelete = { confirmDelete = true },
                )
            }
        }
    }

    val micForDialog = state.mic
    if (confirmDelete && micForDialog != null) {
        ConfirmDialog(
            title = "Delete mic?",
            message = "Remove ${micForDialog.displayName} from your database? Your set history is kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteMic()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun MicDetailContent(
    mic: Mic,
    state: MicDetailUiState,
    onDismissError: () -> Unit,
    onGoing: () -> Unit,
    onCancelled: () -> Unit,
    onClear: () -> Unit,
    onRatingChange: (Float) -> Unit,
    onSaveRating: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val links = rememberLinkOpener()
    val busy = state.busyAction != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        ErrorBanner(message = state.error, onDismiss = onDismissError)

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Header ------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                UrgencyDot(mic.urgencyLevel, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = mic.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.date != null) {
                Text(
                    text = "📅 ${DateRules.fullDate(state.date)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!mic.isActiveMic) {
                Callout(
                    text = "This mic is no longer active (it was removed from your list). Your set history is kept.",
                    tone = CalloutTone.INFO,
                )
            }

            // ---- Status + deadline + urgency --------------------------------
            PlanStatusBanner(state.planStatus)
            state.signupDeadline?.let { deadline ->
                Callout(text = deadline.text, tone = deadlineTone(deadline))
            }
            UrgencyCallout(mic)

            // ---- Facts --------------------------------------------------------
            InfoCard(mic = mic, links = links)

            // ---- Links ---------------------------------------------------------
            LinksSection(mic = mic, links = links)

            // ---- Visits + rating ------------------------------------------------
            if (state.visitCount > 0) {
                Text(
                    text = "✅ Been here ${state.visitCount}×",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            RatingCard(
                value = state.ratingValue,
                changed = state.ratingChanged,
                saving = state.busyAction == DetailAction.RATING,
                enabled = !busy,
                onChange = onRatingChange,
                onSave = onSaveRating,
            )

            // ---- Going / Cancelled / Clear ---------------------------------------
            if (state.date != null && mic.isActiveMic) {
                PlanActions(
                    status = state.planStatus,
                    busyAction = state.busyAction,
                    onGoing = onGoing,
                    onCancelled = onCancelled,
                    onClear = onClear,
                )
            }

            // ---- Footer ----------------------------------------------------------
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !busy) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit mic")
                }
                if (mic.isActiveMic) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        if (state.busyAction == DetailAction.DELETE) {
                            SmallSpinner()
                        } else {
                            Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Delete mic")
                    }
                }
            }
        }
    }
}

private fun deadlineTone(deadline: SignupDeadline): CalloutTone = when (deadline) {
    SignupDeadline.Passed -> CalloutTone.ERROR
    SignupDeadline.Today -> CalloutTone.WARNING
    SignupDeadline.Tomorrow -> CalloutTone.WARNING
    is SignupDeadline.Upcoming -> CalloutTone.INFO
}

/** Rule 3: high = warning style, medium = info style, with the note or the default note. */
@Composable
private fun UrgencyCallout(mic: Mic) {
    val message = mic.urgencyMessage ?: return
    when (mic.urgencyLevel) {
        Urgency.HIGH -> Callout(text = "🔴 $message", tone = CalloutTone.WARNING)
        Urgency.MEDIUM -> Callout(text = "🟡 $message", tone = CalloutTone.INFO)
        Urgency.NORMAL -> Unit
    }
}

/** One labelled line: emoji + text, optionally tappable (shown as a link). */
@Composable
private fun InfoLine(emoji: String, text: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = emoji, modifier = Modifier.width(28.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (onClick != null) TextDecoration.Underline else null,
        )
    }
}

@Composable
private fun InfoCard(mic: Mic, links: LinkOpener) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (mic.venueName.isNotEmpty()) InfoLine("🏠", mic.venueName)

            val address = mic.address
            if (address != null) {
                InfoLine("📍", address, onClick = { links.openMap(mapQuery(mic, address)) })
            } else {
                InfoLine("📍", "Address TBD")
            }

            if (mic.locationText.isNotEmpty()) InfoLine("🗽", mic.locationText)

            val biweekly = if (mic.isBiweeklyMic) " · 🔄 Biweekly" else ""
            val schedule = mic.scheduleText
            if (schedule.isNotEmpty() || biweekly.isNotEmpty()) InfoLine("🕖", schedule + biweekly)

            InfoLine("💰", mic.costText)
            InfoLine("⏱️", mic.setLengthText)

            val method = mic.signupMethodText
            if (method != null) InfoLine("📝", "Signup: $method")
            val signupNotes = TextRules.trimmedOrNull(mic.signupNotes)
            if (signupNotes != null) InfoLine("📌", signupNotes)
            val advance = mic.advanceDays
            if (advance != null && advance > 0) {
                InfoLine("🗓️", "Sign up $advance day${if (advance == 1) "" else "s"} ahead")
            }
        }
    }
}

/** "{address}, {borough}, New York, NY" — what we hand to the maps app. */
private fun mapQuery(mic: Mic, address: String): String {
    val borough = TextRules.trimmedOrNull(mic.borough)
    return if (borough != null) "$address, $borough, New York, NY" else "$address, New York, NY"
}

@Composable
private fun LinksSection(mic: Mic, links: LinkOpener) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val notes = TextRules.trimmedOrNull(mic.notes)
        if (notes != null) {
            Text(
                text = "💡 $notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Rule 9: Instagram
        val handle = mic.instagramHandle
        val rawInstagram = TextRules.trimmedOrNull(mic.instagram)
        if (handle != null) {
            InfoLine("📸", "@$handle", onClick = { links.openUrl(InstagramRules.profileUrl(handle)) })
        } else if (rawInstagram != null) {
            InfoLine("📸", rawInstagram)
        }

        // Rule 8: Sign Up button, or the raw text as a note
        val signupLink = mic.signupLink
        if (signupLink != null) {
            Button(onClick = { links.openUrl(signupLink) }, modifier = Modifier.fillMaxWidth()) {
                Text("📝 Sign Up →")
            }
        } else {
            val note = mic.signupUrlNote
            if (note != null) InfoLine("📝", note)
        }

        val venueLink = mic.venueLink
        if (venueLink != null) {
            OutlinedButton(onClick = { links.openUrl(venueLink) }, modifier = Modifier.fillMaxWidth()) {
                Text("🌐 Venue website")
            }
        }
    }
}

/** "My rating" slider 0–10 in 0.5 steps with an "Update rating" button. */
@Composable
private fun RatingCard(
    value: Float,
    changed: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "⭐ My rating",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = ratingLabel(value),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = 0f..10f,
                // 0, 0.5, 1, …, 10 → 21 positions → 19 steps between the ends.
                steps = 19,
                enabled = enabled,
            )
            Button(
                onClick = onSave,
                enabled = enabled && changed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    SmallSpinner()
                    Spacer(Modifier.width(8.dp))
                }
                Text("Update rating")
            }
        }
    }
}

/** "Not rated" at 0, else "7.5/10" / "8/10". */
private fun ratingLabel(value: Float): String {
    if (value <= 0f) return "Not rated"
    val whole = value % 1f == 0f
    return if (whole) "${value.toInt()}/10" else String.format(Locale.US, "%.1f/10", value)
}

/** Going (primary) · Cancelled · Clear — only the ones that change the state. */
@Composable
private fun PlanActions(
    status: PlanStatus?,
    busyAction: DetailAction?,
    onGoing: () -> Unit,
    onCancelled: () -> Unit,
    onClear: () -> Unit,
) {
    val busy = busyAction != null
    Column {
        Text(text = "Your plan for this date", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status != PlanStatus.GOING) {
                Button(onClick = onGoing, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (busyAction == DetailAction.GOING) SmallSpinner() else Text("Going")
                }
            }
            if (status != PlanStatus.CANCELLED) {
                OutlinedButton(onClick = onCancelled, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (busyAction == DetailAction.CANCELLED) SmallSpinner() else Text("Cancelled")
                }
            }
            if (status != null) {
                OutlinedButton(onClick = onClear, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (busyAction == DetailAction.CLEAR) SmallSpinner() else Text("Clear")
                }
            }
        }
    }
}
