package com.comedyplatform.mictracker.ui.sets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.core.model.CrowdSize
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.ui.components.ConfirmDialog
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import com.comedyplatform.mictracker.ui.components.TagChips
import com.comedyplatform.mictracker.ui.components.rememberLinkOpener

/**
 * Set Detail (spec 6.6) as a bottom sheet: every field of a logged set, the
 * set list in monospace, feedback, a "▶️ Watch/Listen" button for the
 * recording, and Edit / Delete (new on mobile).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetDetailSheet(
    item: SetWithMic,
    isDeleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val links = rememberLinkOpener()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val set = item.set

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${set.ratingEmoji} ${set.dateText}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            DetailLine("📅", "Date", set.dateText)
            DetailLine("🎤", "Mic", item.micAndVenue)
            DetailLine("📊", "Set Rating", set.setRating?.let { "$it/10" } ?: "Not rated yet")
            DetailLine("👥", "Crowd Rating", set.crowdRating?.let { "$it/10" } ?: "?")
            DetailLine("👥", "Crowd Size", CrowdSize.displayText(set.crowdSize) ?: "N/A")

            if (set.newMaterial == true) {
                Text("🆕 New material", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            when (set.wouldReturn) {
                false -> Text("❌ Would NOT return", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                true -> Text("✅ Would return", style = MaterialTheme.typography.bodyLarge)
                null -> Unit
            }

            TagChips(tags = set.tagList)

            val setList = TextRules.trimmedOrNull(set.setList)
            if (setList != null) {
                SectionLabel("Set List")
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = setList,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            val notes = TextRules.trimmedOrNull(set.notes)
            if (notes != null) {
                SectionLabel("Notes")
                Text(text = notes, style = MaterialTheme.typography.bodyLarge)
            }

            if (set.gotFeedback == true) {
                val feedback = TextRules.trimmedOrNull(set.feedbackNotes)
                DetailLine("💬", "Feedback", feedback ?: "Got feedback (no notes)")
            }

            // Recording: a button when the link is usable (rule 8), else the raw text.
            val recordingLink = set.recordingLink
            val recordingRaw = TextRules.trimmedOrNull(set.recordingUrl)
            if (recordingLink != null) {
                val type = TextRules.trimmedOrNull(set.recordingType) ?: "recording"
                Button(onClick = { links.openUrl(recordingLink) }, modifier = Modifier.fillMaxWidth()) {
                    Text("▶️ Watch/Listen ($type)")
                }
            } else if (recordingRaw != null) {
                DetailLine("🔗", "Recording", recordingRaw)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onEdit, enabled = !isDeleting, modifier = Modifier.weight(1f)) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isDeleting) {
                        SmallSpinner()
                    } else {
                        Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this set?",
            message = "Delete your ${set.dateText} set at ${item.micAndVenue}? This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "📊 **Set Rating:** 8/10". */
@Composable
private fun DetailLine(emoji: String, label: String, value: String) {
    Text(
        text = buildAnnotatedString {
            append("$emoji ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label: ") }
            append(value)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}
