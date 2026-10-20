package com.comedyplatform.mictracker.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.comedyplatform.mictracker.ui.components.SmallSpinner

/** Shared layout for the gate screens: centered, scrollable, keyboard-aware. */
@Composable
private fun GateColumn(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // safeDrawingPadding keeps clear of the status bar, navigation bar and keyboard.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun AppTitle() {
    Text(text = "🎤", style = MaterialTheme.typography.displayMedium)
    Text(
        text = "Mic Tracker",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
}

/** Spec 6.1: app name + 🎤, email, password, Sign in, error text. */
@Composable
fun SignInScreen(
    busy: Boolean,
    error: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onInputChanged: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    GateColumn {
        AppTitle()
        Text(
            text = "NYC open mics, your sets, your stats.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                onInputChanged()
            },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                onInputChanged()
            },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSignIn(email, password) }),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = { onSignIn(email, password) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
        ) {
            if (busy) {
                SmallSpinner()
                Spacer(Modifier.width(8.dp))
                Text("Signing in…")
            } else {
                Text("Sign in")
            }
        }
        Text(
            text = "No sign-up here — create your account in the Supabase dashboard (see docs/MOBILE_SETUP.md).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shown when the build has no Supabase URL / key (spec section 2). */
@Composable
fun NotConfiguredScreen() {
    GateColumn {
        AppTitle()
        Text(
            text = "Not configured",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This build doesn't know which Supabase project to use. Create the file " +
                "android/secrets.properties (copy android/secrets.example.properties) with:",
            textAlign = TextAlign.Center,
        )
        CodeBlock("SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co\nSUPABASE_ANON_KEY=eyJ...your anon key...")
        Text(
            text = "Then rebuild and run the app. Step-by-step instructions: docs/MOBILE_SETUP.md, step 7.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Signed in; waiting for `is_app_admin`. */
@Composable
fun CheckingAccessScreen(email: String?) {
    GateColumn {
        AppTitle()
        CircularProgressIndicator()
        Text(
            text = if (email != null) "Checking access for $email…" else "Checking access…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Spec 3.2 "Account not authorized": the signed-in email, the SQL the owner
 * must run, Retry and Sign out. Also used (with [errorMessage]) when the
 * check itself failed.
 */
@Composable
fun AccessProblemScreen(
    email: String?,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    GateColumn {
        AppTitle()
        if (errorMessage == null) {
            Text(
                text = "Account not authorized",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "You're signed in as ${email ?: "an unknown email"}, but this account isn't an app admin yet. " +
                    "In the Supabase dashboard open SQL Editor → New query, run this, then tap Retry:",
                textAlign = TextAlign.Center,
            )
            CodeBlock(adminSql(email))
        } else {
            Text(
                text = "Couldn't check your access",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Text(
                text = "If this mentions is_app_admin, the database migration hasn't been applied yet " +
                    "(docs/MOBILE_SETUP.md, step 2).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

/** The exact SQL from docs/MOBILE_SETUP.md step 3, with the email filled in. */
fun adminSql(email: String?): String {
    val safeEmail = (email ?: "YOUR_EMAIL").replace("'", "''")
    return "insert into app_admins (user_id)\nselect id from auth.users where email = '$safeEmail';"
}

/** Monospace, selectable text in a tinted box. */
@Composable
private fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
