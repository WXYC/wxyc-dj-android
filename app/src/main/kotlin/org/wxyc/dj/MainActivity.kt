package org.wxyc.dj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.wxyc.dj.ui.AuthGate

/**
 * The app's single activity and Compose entry point (issue #7). Hosts
 * [AuthGate], the root composable that switches between the launch spinner,
 * the login screen, and the signed-in app shell on [org.wxyc.dj.api.AuthState].
 *
 * `@AndroidEntryPoint` makes this activity (and every composable it hosts,
 * via `hiltViewModel()`) a consumer of the Hilt graph `di/` wires.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate()
                }
            }
        }
    }
}
