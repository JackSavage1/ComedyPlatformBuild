package com.comedyplatform.mictracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appContainer
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.ui.auth.AccessProblemScreen
import com.comedyplatform.mictracker.ui.auth.CheckingAccessScreen
import com.comedyplatform.mictracker.ui.auth.GateState
import com.comedyplatform.mictracker.ui.auth.NotConfiguredScreen
import com.comedyplatform.mictracker.ui.auth.SessionViewModel
import com.comedyplatform.mictracker.ui.auth.SignInScreen

/**
 * The root composable: the gate before the tabs (spec section 5).
 *
 *   Not configured → Sign in → Not authorized → tabs ([MainScaffold])
 *
 * On reaching the tabs it starts the first data load; screens then read the
 * shared MicRepository.
 */
@Composable
fun MicTrackerRoot() {
    val sessionViewModel = appViewModel { container -> SessionViewModel(container) }
    val gate by sessionViewModel.state.collectAsStateWithLifecycle()

    when (val state = gate) {
        GateState.NotConfigured -> NotConfiguredScreen()

        GateState.SignedOut -> {
            val busy by sessionViewModel.signInBusy.collectAsStateWithLifecycle()
            val error by sessionViewModel.signInError.collectAsStateWithLifecycle()
            SignInScreen(
                busy = busy,
                error = error,
                onSignIn = { email, password -> sessionViewModel.signIn(email, password) },
                onInputChanged = { sessionViewModel.clearSignInError() },
            )
        }

        is GateState.Checking -> CheckingAccessScreen(email = state.email)

        is GateState.NotAuthorized -> AccessProblemScreen(
            email = state.email,
            errorMessage = null,
            onRetry = { sessionViewModel.retryAdminCheck() },
            onSignOut = { sessionViewModel.signOut() },
        )

        is GateState.CheckFailed -> AccessProblemScreen(
            email = state.email,
            errorMessage = state.message,
            onRetry = { sessionViewModel.retryAdminCheck() },
            onSignOut = { sessionViewModel.signOut() },
        )

        is GateState.Authorized -> {
            val repository = appContainer().micRepository
            // First load for this user (re-runs if a different user signs in).
            LaunchedEffect(state.email) {
                repository.refreshAll()
            }
            MainScaffold()
        }
    }
}
