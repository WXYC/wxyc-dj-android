package org.wxyc.dj.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.wxyc.dj.api.AuthState
import org.wxyc.dj.ui.login.LoginScreen
import org.wxyc.dj.ui.nav.MainScaffold

/**
 * The app's root composable (issue #7): switches on [AuthState] between the
 * launch spinner, the login screen, and the signed-in app shell. Mirrors
 * `RootView.swift`'s auth-gate `switch` (trimmed of the Spotlight
 * deep-link/offline-banner machinery, which is out of v1 scope -- see
 * `docs/port-plan.md`'s "Out of v1" list).
 *
 * **Three rendered states, not two** -- [AuthState.Unknown] and
 * [AuthState.SigningIn] both show [LaunchScreen] rather than falling through
 * to [LoginScreen]: an app that cannot yet tell signed-in from signed-out
 * must render neither the login form nor the signed-in shell while
 * `restoreSession()` (kicked off by [AuthViewModel]) is still in flight.
 * Folding [AuthState.Unknown] into [AuthState.SignedOut] would flash a
 * login form on every cold launch, even for a DJ with a valid stored
 * session.
 */
@Composable
fun AuthGate(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state) {
        AuthState.Unknown, AuthState.SigningIn -> LaunchScreen()
        AuthState.SignedOut -> LoginScreen()
        is AuthState.SignedIn -> MainScaffold(onSignOut = viewModel::signOut)
    }
}

/** The pre-restore spinner -- [AuthState.Unknown]'s and [AuthState.SigningIn]'s screen. */
@Composable
private fun LaunchScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
