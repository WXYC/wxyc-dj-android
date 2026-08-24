package org.wxyc.dj.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.wxyc.dj.R
import org.wxyc.dj.api.LoginCodeDestination

/**
 * The sign-in screen (issue #8): [org.wxyc.dj.ui.AuthGate]'s
 * [org.wxyc.dj.api.AuthState.SignedOut] branch, not a `NavHost` destination
 * -- there is nowhere to navigate *from* before a DJ is signed in. Leads
 * with the mailed one-time code (ADR 0006 on iOS) and keeps the password
 * form one tap away via "Sign in with password instead" -- load-bearing for
 * Play review, since a reviewer's App Access demo credentials can only be
 * typed, never mailed. Port of `WXYCDJ/Auth/LoginView.swift` +
 * `OTPCodeView.swift`, collapsed into one file since Compose has no
 * `NavigationStack`-per-stage idiom to mirror.
 *
 * The header text is a store-facing requirement, not decoration: the Play
 * listing is public, so this screen is what disambiguates a staff tool from
 * a listener app. It renders on every [LoginStage] rather than only the
 * first, since a reviewer or a DJ can land on any of the three.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.login_app_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.login_staff_notice), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        SignInErrorMessage(state.errorMessage)
        Spacer(Modifier.height(8.dp))

        when (val stage = state.stage) {
            LoginStage.Identifier -> IdentifierStage(state, viewModel, scope)
            is LoginStage.AwaitingCode -> CodeStage(state, stage.destination, viewModel, scope)
            LoginStage.Password -> PasswordStage(state, viewModel, scope)
        }
    }
}

/** The path the screen leads with: one field, "Send login code". */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IdentifierStage(state: LoginUiState, viewModel: LoginViewModel, scope: CoroutineScope) {
    // One field for either credential, as dj.wxyc.org has -- the email
    // keyboard puts "@" and "." on the primary layer and serves a username
    // just as well, mirroring iOS's identical choice in LoginView.swift.
    OutlinedTextField(
        value = state.identifier,
        onValueChange = viewModel::onIdentifierChanged,
        label = { Text(stringResource(R.string.login_identifier_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { scope.launch { viewModel.requestCode() } }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Username },
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.login_code_footer), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))

    PrimaryActionButton(
        text = stringResource(R.string.login_send_code_button),
        isBusy = state.isSendingCode,
        enabled = state.canRequestCode,
        onClick = { scope.launch { viewModel.requestCode() } },
    )
    TextButton(onClick = viewModel::usePassword, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.login_use_password_button))
    }
}

/**
 * The second step: enter the 6 digits WXYC just mailed, with a
 * cooldown-gated resend and a way back to fix a mistyped identifier.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CodeStage(
    state: LoginUiState,
    destination: LoginCodeDestination,
    viewModel: LoginViewModel,
    scope: CoroutineScope,
) {
    val displayTarget = displayTarget(destination, stringResource(R.string.login_generic_email_target))

    OutlinedTextField(
        value = state.code,
        onValueChange = viewModel::onCodeChanged,
        label = { Text(stringResource(R.string.login_code_label)) },
        singleLine = true,
        // The server's alphabet is digits only; NumberPassword also avoids
        // predictive-text suggestions the way `.keyboardType(.numberPad)`
        // does on iOS.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { scope.launch { viewModel.submitCode() } }),
        // Lets the platform offer the code straight from the mail
        // notification -- the Android analogue of iOS's
        // `.textContentType(.oneTimeCode)`.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.SmsOtpCode },
    )
    Spacer(Modifier.height(8.dp))
    // Naming the destination is the DJ's only way to catch a mistyped
    // email: the server answers "code sent" for an address matching no
    // account (`disableSignUp: true`), so nothing downstream can report it.
    Text(
        stringResource(R.string.login_code_sent_footer, displayTarget),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))

    PrimaryActionButton(
        text = stringResource(R.string.login_submit_button),
        isBusy = state.isSigningIn,
        enabled = state.canSubmitCode,
        onClick = { scope.launch { viewModel.submitCode() } },
    )
    // Gated on a cooldown, not just politeness: better-auth allows 3 sends
    // per 60s and Backend-Service 10 requests per 15 minutes per *IP*,
    // which the control room shares.
    TextButton(
        onClick = { scope.launch { viewModel.resendCode() } },
        enabled = state.canResendCode,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.login_resend_button))
    }
    TextButton(onClick = viewModel::changeIdentifier, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.login_change_identifier_button))
    }
}

/**
 * The secondary path, reached via "Sign in with password instead" --
 * load-bearing for Play review (a reviewer cannot receive a mailed code)
 * and reachable in exactly one tap from launch.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PasswordStage(state: LoginUiState, viewModel: LoginViewModel, scope: CoroutineScope) {
    Text(stringResource(R.string.login_password_header), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.identifier,
        onValueChange = viewModel::onIdentifierChanged,
        label = { Text(stringResource(R.string.login_identifier_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Username },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChanged,
        label = { Text(stringResource(R.string.login_password_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { scope.launch { viewModel.submit() } }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
    )
    Spacer(Modifier.height(16.dp))

    PrimaryActionButton(
        text = stringResource(R.string.login_submit_button),
        isBusy = state.isSigningIn,
        enabled = state.canSubmit,
        onClick = { scope.launch { viewModel.submit() } },
    )
    TextButton(onClick = viewModel::useCode, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.login_use_code_button))
    }
}

/**
 * The text [CodeStage] renders for where the code went (issue #8 invariant
 * 11, port of iOS's `OTPCodeView.displayTarget`). Only [LoginCodeDestination.typedEmail]
 * is renderable -- it is `null` in exactly the case where showing the
 * address would disclose one the DJ never typed, since `:api`'s
 * `LoginCodeDestination` constructor is `internal` and cannot be
 * constructed with a fabricated `typedEmail` from this module. [genericWording]
 * is supplied by the caller (rather than hardcoded here) so the composable
 * stays the single place that owns user-facing English, per this repo's
 * string-resource convention -- extracted to a plain function, not left
 * inline in [CodeStage], so this branch is unit-testable without a Compose
 * test rule.
 */
internal fun displayTarget(destination: LoginCodeDestination, genericWording: String): String =
    destination.typedEmail ?: genericWording
