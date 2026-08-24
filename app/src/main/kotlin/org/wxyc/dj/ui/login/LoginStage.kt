package org.wxyc.dj.ui.login

import org.wxyc.dj.api.LoginCodeDestination

/**
 * Which credential the DJ is partway through presenting on [LoginScreen]
 * (issue #8, port of iOS's `LoginViewModel.Stage`).
 *
 * An explicit three-state sealed type rather than a pair of booleans: the
 * states are mutually exclusive and two of them carry data, so a boolean
 * pair would either need a third flag or admit an unrepresentable
 * combination. It does **not** decide which error the screen shows -- that
 * is [LoginUiState.errorMessage], read the same way from every stage. An
 * earlier iOS design let a stage change leave a stale error from the
 * previous stage on screen; [LoginViewModel] closes that by clearing the
 * error on every transition, not by branching here.
 */
sealed interface LoginStage {
    /** The default. One field; the DJ asks for a mailed code. */
    data object Identifier : LoginStage

    /**
     * A code is in the DJ's inbox. Carries [destination] whole rather than
     * restating its two fields, so the "only [LoginCodeDestination.typedEmail]
     * is renderable" rule stays a property of one type.
     */
    data class AwaitingCode(val destination: LoginCodeDestination) : LoginStage

    /** The secondary path, reached via "Sign in with password instead". */
    data object Password : LoginStage
}
