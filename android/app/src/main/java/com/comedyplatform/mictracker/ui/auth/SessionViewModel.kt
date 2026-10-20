package com.comedyplatform.mictracker.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comedyplatform.mictracker.AppContainer
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.core.model.Rpc
import com.comedyplatform.mictracker.data.ApiException
import com.comedyplatform.mictracker.data.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the app shows before the tabs (spec section 5):
 * Not configured → Sign in → (checking) → Not authorized → tabs.
 */
sealed interface GateState {
    /** SUPABASE_URL / SUPABASE_ANON_KEY missing from the build. */
    data object NotConfigured : GateState

    /** No stored session — show the sign-in form. */
    data object SignedOut : GateState

    /** Signed in; asking the server `is_app_admin`. */
    data class Checking(val email: String?) : GateState

    /** The admin check itself failed (offline, migration not applied, ...). */
    data class CheckFailed(val email: String?, val message: String) : GateState

    /** Signed in, but this account isn't in `app_admins`. */
    data class NotAuthorized(val email: String?) : GateState

    /** Signed in as an app admin — show the tabs. */
    data class Authorized(val email: String?) : GateState
}

/**
 * Drives the gate and the sign-in form.
 *
 * After every sign-in (and on launch with a stored session) it calls RPC
 * `is_app_admin` (spec 3.2). Token refreshes don't re-run the check — only a
 * change of user does.
 */
class SessionViewModel(private val container: AppContainer) : ViewModel() {

    private val auth = container.authRepository

    private sealed interface AdminCheck {
        data object Pending : AdminCheck
        data object Admin : AdminCheck
        data object NotAdmin : AdminCheck
        data class Failed(val message: String) : AdminCheck
    }

    private val adminCheck = MutableStateFlow<AdminCheck>(AdminCheck.Pending)

    val state: StateFlow<GateState> = combine(auth.session, adminCheck) { session, check ->
        gateFor(session, check)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, gateFor(auth.session.value, adminCheck.value))

    // ---- Sign-in form state -------------------------------------------------

    private val _signInBusy = MutableStateFlow(false)
    val signInBusy: StateFlow<Boolean> = _signInBusy.asStateFlow()

    private val _signInError = MutableStateFlow<String?>(null)
    val signInError: StateFlow<String?> = _signInError.asStateFlow()

    init {
        // Re-check admin status whenever the signed-in USER changes.
        viewModelScope.launch {
            auth.session
                .map { session -> session?.let { it.userId ?: it.email ?: SIGNED_IN_UNKNOWN_USER } }
                .distinctUntilChanged()
                .collect { identity ->
                    if (identity == null) {
                        adminCheck.value = AdminCheck.Pending
                        container.micRepository.clear()
                    } else {
                        checkAdmin()
                    }
                }
        }
    }

    fun signIn(email: String, password: String) {
        if (_signInBusy.value) return
        if (email.isBlank() || password.isEmpty()) {
            _signInError.value = "Enter your email and password."
            return
        }
        viewModelScope.launch {
            _signInBusy.value = true
            _signInError.value = null
            try {
                auth.signIn(email, password)
                // The session flow above triggers the admin check.
            } catch (e: ApiException) {
                _signInError.value = e.userMessage
            } finally {
                _signInBusy.value = false
            }
        }
    }

    /** "Retry" on the Not authorized / check-failed screens. */
    fun retryAdminCheck() {
        viewModelScope.launch { checkAdmin() }
    }

    fun signOut() {
        viewModelScope.launch {
            container.signOut()
            adminCheck.value = AdminCheck.Pending
        }
    }

    fun clearSignInError() {
        _signInError.value = null
    }

    private suspend fun checkAdmin() {
        if (auth.session.value == null) return
        adminCheck.value = AdminCheck.Pending
        adminCheck.value = try {
            val result = container.apiClient.rpc(Rpc.IS_APP_ADMIN)
            if (JsonValues.boolean(result) == true) AdminCheck.Admin else AdminCheck.NotAdmin
        } catch (e: ApiException) {
            AdminCheck.Failed(e.userMessage)
        }
    }

    private fun gateFor(session: AuthSession?, check: AdminCheck): GateState {
        if (!container.config.isConfigured) return GateState.NotConfigured
        if (session == null) return GateState.SignedOut
        val email = session.email
        return when (check) {
            AdminCheck.Pending -> GateState.Checking(email)
            AdminCheck.Admin -> GateState.Authorized(email)
            AdminCheck.NotAdmin -> GateState.NotAuthorized(email)
            is AdminCheck.Failed -> GateState.CheckFailed(email, check.message)
        }
    }

    private companion object {
        const val SIGNED_IN_UNKNOWN_USER = "signed-in"
    }
}
