package org.wxyc.dj.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.AuthState

/**
 * Backs [AuthGate] (issue #7). Exposes [AuthService.state] and kicks off
 * `restoreSession()` from `init`, mirroring `WXYCDJApp`'s launch `.task` on
 * iOS.
 *
 * **Not "once per process."** A [ViewModel] survives configuration changes,
 * but it is scoped to its owning [androidx.lifecycle.ViewModelStoreOwner]
 * (the hosting Activity here) -- `init` runs again on any recreation that
 * clears that store, which is a narrower guarantee than the process
 * lifetime. What actually makes a repeated `restoreSession()` call harmless
 * is `:api`'s own guard: `AuthService.restoreSession()` returns immediately
 * unless `_state.value == AuthState.Unknown`, so calling it more than once
 * is inert by construction rather than merely unlikely in practice.
 *
 * [signOut] is exposed here, not on a separate view model, because there is
 * only **one** `hiltViewModel()` call site for this class -- in [AuthGate] --
 * and its result's [signOut] method reference is what [AuthGate] passes down
 * as `MainScaffold`'s `onSignOut` parameter. `MainScaffold` never resolves
 * its own instance of this class; it receives a plain function value.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authService: AuthService,
) : ViewModel() {

    val state: StateFlow<AuthState> = authService.state

    init {
        viewModelScope.launch { authService.restoreSession() }
    }

    fun signOut() {
        viewModelScope.launch { authService.signOut() }
    }
}
