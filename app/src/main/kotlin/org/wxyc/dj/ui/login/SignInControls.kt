package org.wxyc.dj.ui.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The screen's single error slot (issue #8, port of iOS's `SignInErrorSection`).
 * One composable rather than a copy per [LoginStage], so every stage renders
 * [LoginUiState.errorMessage] identically -- see [LoginViewModel]'s KDoc for
 * why that single field, read the same way everywhere, is what keeps a
 * resend failure from being able to erase a message a different call
 * produced.
 */
@Composable
fun SignInErrorMessage(message: String?) {
    if (message != null) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The full-width primary button each [LoginStage] submits with (issue #8,
 * port of iOS's `PrimaryActionButton`). One composable so the busy/idle
 * swap can't drift out of sync between stages the way iOS's KDoc notes an
 * earlier per-stage copy once did (a missing `frame(maxWidth: .infinity)`
 * on one stage's spinner let the button visibly change size while a
 * request was in flight).
 */
@Composable
fun PrimaryActionButton(
    text: String,
    isBusy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !isBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
        } else {
            Text(text)
        }
    }
}
