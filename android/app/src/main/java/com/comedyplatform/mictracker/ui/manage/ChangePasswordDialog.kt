package com.comedyplatform.mictracker.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.comedyplatform.mictracker.core.rules.PasswordRules
import com.comedyplatform.mictracker.ui.components.SmallSpinner

/**
 * Manage → Account → Change password (spec 9): current, new and confirm,
 * the footnote, an inline error, and Cancel / Save.
 *
 * The passwords are kept with plain `remember` (not `rememberSaveable`), so
 * they're never written into the saved-instance-state Bundle, and they're
 * dropped — the fields cleared — the moment the dialog closes.
 *
 * @param onSave runs the checks and the server calls (ManageViewModel.changePassword)
 * @param onInputChanged the user typed: hide the previous error
 * @param onDismiss Cancel / back (ignored by the ViewModel while saving)
 */
@Composable
fun ChangePasswordDialog(
    state: ChangePasswordState,
    onSave: (current: String, new: String, confirm: String) -> Unit,
    onInputChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val canSave = PasswordRules.canSave(current, new, confirm) && !state.isSaving
    val save = { if (canSave) onSave(current, new, confirm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap outside shouldn't throw away what was typed; back and Cancel still close.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(PasswordRules.TITLE) },
        text = {
            // Scrolls when the keyboard leaves little room (small phones, landscape).
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PasswordField(
                    value = current,
                    onValueChange = {
                        current = it
                        onInputChanged()
                    },
                    label = "Current password",
                    isNewPassword = false,
                    enabled = !state.isSaving,
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = new,
                    onValueChange = {
                        new = it
                        onInputChanged()
                    },
                    label = "New password",
                    isNewPassword = true,
                    enabled = !state.isSaving,
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        onInputChanged()
                    },
                    label = "Confirm new password",
                    isNewPassword = true,
                    enabled = !state.isSaving,
                    imeAction = ImeAction.Done,
                    onImeAction = save,
                )
                Text(
                    text = PasswordRules.FOOTNOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = canSave) {
                if (state.isSaving) {
                    SmallSpinner()
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…")
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("Cancel") }
        },
    )
}

/**
 * A password field: dots by default, an eye button to show / hide the text,
 * the password keyboard without autocorrect or auto-capitals, and an autofill
 * hint so the phone's password manager can fill the current password
 * ([AutofillType.Password]) or suggest a new one ([AutofillType.NewPassword]).
 *
 * @param isNewPassword true for "New" and "Confirm", false for "Current"
 */
@OptIn(ExperimentalComposeUiApi::class) // Compose 1.7's autofill API is still marked experimental.
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isNewPassword: Boolean,
    enabled: Boolean,
    imeAction: ImeAction,
    /** Runs on the keyboard's Done key (the last field uses it to save). */
    onImeAction: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }

    // Autofill: register this field with the dialog's autofill tree, keep its
    // on-screen bounds current, and ask the autofill service for suggestions
    // when the field gets focus.
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val autofillNode = remember(isNewPassword) {
        val type = if (isNewPassword) AutofillType.NewPassword else AutofillType.Password
        AutofillNode(autofillTypes = listOf(type), onFill = { latestOnValueChange(it) })
    }
    DisposableEffect(autofillTree, autofillNode) {
        autofillTree += autofillNode
        onDispose { autofillTree.children.remove(autofillNode.id) }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        // Only Done is handled here; Next keeps its default "move to the next field".
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { autofillNode.boundingBox = it.boundsInWindow() }
            .onFocusChanged { focus ->
                // Android needs the field's bounds before it can show suggestions.
                if (autofill == null || autofillNode.boundingBox == null) return@onFocusChanged
                if (focus.isFocused) {
                    autofill.requestAutofillForNode(autofillNode)
                } else {
                    autofill.cancelAutofillForNode(autofillNode)
                }
            },
    )
}
