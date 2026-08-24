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
 * `restoreSession()` once per process: [ViewModel] instances survive
 * configuration changes, so `init` running exactly once per real app launch
 * (not per rotation) is what "call restoreSession() on launch" means here --
 * the direct analogue of `WXYCDJApp`'s launch `.task` on iOS.
 *
 * [signOut] is exposed here, not on a separate view model, so the same
 * Hilt-scoped instance [AuthGate] observes is the one [MainScaffold]'s
 * sign-out action calls -- `hiltViewModel()` with no back-stack-entry key
 * resolves to the nearest [androidx.lifecycle.ViewModelStoreOwner] (the
 * hosting Activity), so both call sites share one instance without any
 * extra plumbing.
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
