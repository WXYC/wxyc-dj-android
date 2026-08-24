package org.wxyc.dj.ui.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.wxyc.dj.R

/**
 * Placeholder for [org.wxyc.dj.api.AuthState.SignedOut] -- issue #7's
 * `AuthGate` renders this today; issue #8 replaces this file's body with the
 * OTP-led sign-in flow (ADR 0006 on iOS), the password fallback, and the
 * store-facing copy required by `docs/port-plan.md`'s "Distribution and
 * store obligations" section. Nothing outside this file needs to change
 * when it does.
 */
@Composable
fun LoginScreen() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.login_screen_placeholder),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
