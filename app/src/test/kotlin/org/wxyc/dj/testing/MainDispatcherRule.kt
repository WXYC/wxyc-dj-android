package org.wxyc.dj.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Routes `Dispatchers.Main` -- and therefore any `ViewModel`'s
 * `viewModelScope` -- through a [TestDispatcher] for the duration of one
 * test, so coroutines a view model launches run against `runTest`'s own
 * virtual scheduler instead of needing a real `Looper`. That is what lets
 * `LoginViewModel`'s resend cooldown and `SearchViewModel`'s 300ms search
 * debounce both fast-forward instead of waiting in real time. Standard
 * JUnit4 pattern for `ViewModel` + coroutines tests.
 *
 * Lives in a shared `org.wxyc.dj.testing` package rather than being copied
 * per screen. iOS's `WXYCDJTests/Support` precedent for *copying* small test
 * support applies to its two separate test **bundles**, where sharing would
 * cost a whole new SPM target; here every `:app` test compiles into one
 * source set, so sharing costs an import and copying costs a file per
 * screen that can silently drift.
 */
@ExperimentalCoroutinesApi
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
