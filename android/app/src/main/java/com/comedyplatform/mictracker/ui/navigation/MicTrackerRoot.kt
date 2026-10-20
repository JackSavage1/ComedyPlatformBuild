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
 *   "Try the demo" → tabs on demo data (spec 8.1)
 *
 * On reaching the tabs it starts the first data load; screens then read the
 * shared MicRepository.
 */
@Composable
fun MicTrackerRoot() {
    val container = appContainer()
    val sessionViewModel = appViewModel { c -> SessionViewModel(c) }
    val gate by sessionViewModel.state.collectAsStateWithLifecycle()

    when (val state = gate) {
        // CI's `screen=sign-in` screenshot (spec 8.3) runs on a build without
        // Supabase settings, so it shows the Sign in form here instead.
        GateState.NotConfigured -> if (container.launchHooks.showsSignIn) {
            SignInGate(sessionViewModel)
        } else {
            NotConfiguredScreen(onTryDemo = sessionViewModel::enterDemo)
        }

        GateState.SignedOut -> SignInGate(sessionViewModel)

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
            // First load for this user (re-runs if a different user signs in).
            LaunchedEffect(state.email) {
                container.micRepository.refreshAll()
            }
            MainScaffold()
        }

        GateState.Demo -> {
            // The demo backend was just switched in (and is fresh); load it.
            LaunchedEffect(Unit) {
                container.micRepository.refreshAll()
            }
            MainScaffold()
        }
    }
}

/** Spec 6.1 Sign in, wired to the session. */
@Composable
private fun SignInGate(sessionViewModel: SessionViewModel) {
    val busy by sessionViewModel.signInBusy.collectAsStateWithLifecycle()
    val error by sessionViewModel.signInError.collectAsStateWithLifecycle()
    SignInScreen(
        busy = busy,
        error = error,
        onSignIn = { email, password -> sessionViewModel.signIn(email, password) },
        onInputChanged = { sessionViewModel.clearSignInError() },
        onTryDemo = sessionViewModel::enterDemo,
    )
}
