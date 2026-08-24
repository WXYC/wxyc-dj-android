package org.wxyc.dj.ui.login

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Routes `Dispatchers.Main` -- and therefore [LoginViewModel]'s
 * `viewModelScope` -- through a [TestDispatcher] for the duration of one
 * test, so the resend cooldown's `viewModelScope.launch` coroutine runs
 * against `runTest`'s own virtual scheduler instead of needing a real
 * `Looper`. Standard JUnit4 pattern for `ViewModel` + coroutines tests; not
 * a new project dependency (`kotlinx-coroutines-test` is already on `:api`'s
 * test classpath, added by an earlier issue -- this is `:app`'s first use).
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
