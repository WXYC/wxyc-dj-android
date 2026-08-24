# WXYC DJ (Android) — Claude Code Instructions

A native Kotlin/Compose port of `wxyc-dj-ios`: sign in with dj.wxyc.org credentials, search the WXYC library, read release metadata, keep a per-DJ bin. Read `README.md` for the user-facing tour.

The module is `wxyc-dj-android`; the `applicationId` and `namespace` are both `org.wxyc.dj`, matching the iOS bundle ID (no collision with the listener app's `org.wxyc.WXYCCH`).

## What this port actually is

`wxyc-dj-ios` is ~9.1k LOC of source against ~11.3k LOC of tests. **That ratio is the point: the value in that codebase is disproportionately in the invariants, not the screens.** Almost every one was written to close a specific, reproduced defect, and each is documented at its site with the failure it prevents.

So the rule for this repo is: **port the test cases, not the code.** Before writing the Kotlin for anything on the invariants list, read the iOS source comment for it — in most cases the comment explains a failure mode the code alone does not reveal. The canonical list, with the iOS issue number and the Android mapping for each, lives in the port plan and is restated per-issue on the GitHub issues that carry the work.

The iOS repo's own `CLAUDE.md` is the deepest single reference; it documents the auth state machine, the bin wire contract, and the artwork precedence chain at length.

## Core conventions

- **Kotlin**, Compose + Material3, `StateFlow` + `collectAsStateWithLifecycle` (never `LiveData`).
- `compileSdk` / `targetSdk` **36**, `minSdk` **26**. The floor is a security decision, not a capability one: API 24/25 devices get no security patches and this app holds a live station credential. A DJ on such a phone still has dj.wxyc.org.
- **TDD by default**: failing test, minimum implementation, passing test, then look for the refactor. Each invariant lands as a red test first.
- **Do not introduce third-party dependencies without asking first.**
- **Version catalog entries are added by the PR that first uses them**, never declared ahead of need. An unused alias is never resolved, so a wrong version sits latent until some later PR trips over it.

### File headers

**No filename/author/copyright banner.** The iOS repo mandates one; the payoff there is the one-line purpose, and the rest (filename, date, module) rots on rename and duplicates what git already knows. Kotlin's idiom is a KDoc block on the file's primary declaration, so that is what this repo uses: **every hand-written Kotlin file carries a KDoc on its top-level declaration saying what it is and how it fits.** The bar is the same — a reader should learn the file's job without reading its body. Decided in the scaffold rather than later, because deciding after `:api` exists means retrofitting all of it.

## `:api` is a pure JVM module

`api/build.gradle.kts` applies `org.jetbrains.kotlin.jvm`, **never** `com.android.library`. This is the structural decision the repo is arranged around — the analogue of the iOS split that lets `swift test --package-path Packages/WXYCAPI` run the auth state machine, the DTO decoding, the identifier router and the rotation predicate on the host with no simulator. Here the alternative is an *emulator*, so the payoff is larger: these suites run hundreds of times a day and must run in milliseconds.

The rule that keeps it true:

- **Hilt is Android-only** (`com.google.dagger.hilt.android`), so **every `@Module` lives in `:app`.** `:api` exposes plain factories and constructors for `:app` to wire.
- A platform-backed implementation of an `:api` interface (encrypted token storage, connectivity) lives in `:app` beside its `@Module`, with only the interface and an in-memory implementation in `:api` — exactly as iOS keeps `TokenStorage` + `InMemoryTokenStorage` in-package and lets the Keychain implementation ride along only because Security.framework exists on macOS too. That accident does not transfer.
- **`:api` stays SDK-free** — no Sentry, no PostHog, no analytics of any kind, ever. Package-level errors already surface at app-layer call sites, which is where any future capture belongs.

`api/src/test/kotlin/org/wxyc/dj/api/PureJvmModuleTest.kt` is the tripwire: it fails if `android.content.Context` ever resolves from `:api`.

## No secrets, deliberately

There is **no `secrets.properties` in this repo, and none should be added.** `buildConfig` is switched off in `app/build.gradle.kts` for the same reason. This app's base URLs are public hostnames and its only credential is the DJ's own session, so `Configuration` carries plain `.production` / `.localDevelopment` presets, exactly as `Packages/WXYCAPI/Sources/WXYCAPI/Configuration.swift` does.

That property is what makes a public repo safe here, so protect it:

- **Never commit a credential literal** — no API key, secret, token, password or keystore, in source, in a test fixture, or in a comment. Not even a "temporary" one, and not even one already in a gitignored file. `WXYC-Android` has a live example of exactly that failure ([#38](https://github.com/WXYC/WXYC-Android/issues/38)): a Discogs key hardcoded in a header literal *beside* a working `BuildConfig` wiring for the same value, committed to a public repo, where rotation is now the only remedy.
- If a genuine third-party credential ever becomes necessary, that is a design conversation first — not a `secrets.properties` added in passing.

## App shell (`:app`, issue #7)

The Hilt graph, the encrypted token store, the auth gate, and the nav skeleton all landed in one PR so #8-#11 (login, search, detail, bin) could build in parallel against a stable shell. Four things worth knowing before touching any of it:

**Hilt module layout.** Every `@Module`/`@EntryPoint` lives in `app/src/main/kotlin/org/wxyc/dj/di/`, per "`:api` is a pure JVM module" above — Hilt is Android-only, so `:api` can expose only plain constructors for these to wire:

- `ApiModule` — `:api`'s session layer: `AuthService` and `ApiClient`.
- `NetworkModule` — `Configuration` (`.production` only; `.localDevelopment` is unwired pending a debug-only cleartext allowance), the no-cookie `CookielessHttpClient` (via `:api`'s `CookielessHttpClientFactory`, never constructed directly), and Coil's `ImageLoader` (built over that same client as its `Call.Factory`, so Coil never spins up a second, cookie-armed `OkHttpClient`).
- `TokenStorageModule` — the encrypted `TokenStorage` binding. See the next section for what it actually does.
- `AppEntryPoint` — a Hilt `@EntryPoint`, not a `@Module`, and its only consumer is `HiltGraphDeviceTest`: it exposes every top-level binding above so an instrumented test can resolve the real production graph on a real Keystore/DataStore without an `@AndroidEntryPoint` host.

**DataStore + Tink storage, and the backup-exclusion tie-in.** `token/EncryptedTokenStorage.kt` stores ciphertext in a `DataStore<Preferences>`; a Tink `Aead` does the encrypting, over a random AES256-GCM data-encryption key that Tink generates once and wraps (never derives — there is no KDF) with an Android Keystore master key. `TokenStorageModule.provideAead` builds that `Aead` via `AndroidKeysetManager`, and injects it into `EncryptedTokenStorage` as a `dagger.Lazy<Aead>` rather than eagerly — deferring the Keystore/Tink I/O off the main thread and out of `hiltViewModel()`'s composition-time resolution (see that file's KDoc for the full chain). A first-read failure (a corrupted keyset blob, or an invalidated Keystore key) is caught, the keyset preferences file is wiped, and the keyset is rebuilt once — degrading to "the DJ signs in again" instead of crashing `MainActivity` on every subsequent launch. `TokenStorageRegenerationDeviceTest` pins that regenerate path against a real, corrupted Keystore-wrapped keyset. Neither the ciphertext DataStore file nor Tink's wrapped-keyset `SharedPreferences` file may ever leave the device: `AndroidManifest.xml`'s `allowBackup="false"` (cloud backup) plus `res/xml/data_extraction_rules.xml` (device-to-device transfer, Android 12+) exclude every `file`/`sharedpref`/`database` domain outright rather than naming either file specifically, so a future store added under a new name doesn't silently fall outside the exclusion.

**The nav-skeleton contract #8-#11 depend on.** `ui/nav/MainScaffold.kt`, `ui/nav/AlbumRoute.kt`, `ui/nav/AlbumRouteFallbackStore.kt`, and `res/values/strings.xml` are the shared shell files — none of the four screen issues should ever need to touch them. Each screen issue owns exactly one placeholder composable and its own strings file:

| Issue | Screen file | Strings file |
|---|---|---|
| #8 | `ui/login/LoginScreen.kt` | `res/values/strings_login.xml` |
| #9 | `ui/search/SearchScreen.kt` | `res/values/strings_search.xml` |
| #10 | `ui/detail/AlbumDetailScreen.kt` | `res/values/strings_detail.xml` |
| #11 | `ui/bin/BinScreen.kt` | `res/values/strings_bin.xml` |

`MainScaffold.kt` already threads what each screen needs to reach the shell without editing it: `SearchScreen`/`BinScreen` receive `onAlbumSelected: (AlbumRoute) -> Unit`, and `AlbumDetailScreen` receives both `route: AlbumRoute` and `onBack: () -> Unit` (the same lambda the top bar's own back arrow calls, so a fallback-less deep link and an in-screen "done" action both just work). The top bar itself is destination-aware — a back arrow and a distinct title appear only on the album-detail destination — specifically so #10 never has to add its own back affordance to this shared file. If a screen issue finds itself needing to edit any of the four shared files above, that is a sign the contract needs revisiting, not a one-off exception.

### Issue #23 — `AlbumRoute` is id-only, and the row travels out of band

`AlbumRoute` carried a hand-`equals`/`hashCode` class shape (`id` plus a serialized `fallback: AlbumSearchResult?`) that ported iOS's id-only identity in a way that turned out to have no production consumer: Navigation Compose keys its back stack on the destination's *encoded route string*, not on `equals`, so two differently-sourced routes for the same album produced two different route strings and two different `NavBackStackEntry`s regardless of what `equals` said. Issue #23 replaced it with `@Serializable data class AlbumRoute(val id: Int)` — id-only in both its Kotlin equality and its route string, so `getBackStackEntry`/`popBackStack` (which match by route string) now genuinely resolve an already-open detail for the same album regardless of source. That also retired `AlbumSearchResultNavType` outright: an `Int`-only route needs no custom `NavType`, so the whole class of route-string-encoding bugs it guarded against (the #7 double-decode that corrupted "C+C Music Factory" and dropped "100% Silk") is now structurally impossible rather than merely tested for.

The row a caller already has in hand — what used to be `AlbumRoute.fallback` — now travels out of band via `ui/nav/AlbumRouteFallbackStore.kt`, a single-slot, last-write-wins holder: the navigating screen stashes it immediately before calling `navController.navigate(...)`, and `AlbumDetailScreen` reads it back exactly once. The real `navigate()`/`getBackStackEntry()`/`popBackStack()`-driven proof of the coalescing property lives in `AlbumRouteNavigationTest.kt`; a `Set`/`equals`-based test (the suite this replaced) cannot see it, because nothing in the app ever puts two `AlbumRoute` instances in a `Set`.

**Forced version pins and workarounds**, each recorded inline where it's declared (`app/build.gradle.kts` / `gradle/libs.versions.toml`) but worth knowing before "helpfully" bumping one:

- **Hilt 2.58, not 2.59+** — the Hilt Gradle plugin requires AGP 9.0 starting at 2.59; this repo pins AGP 8.13.2.
- **Coil 3.3.0, not 3.5.0** — later Coil 3 module metadata hard-requires `kotlin-stdlib 2.4.0` against this repo's Kotlin 2.2.20.
- **`hilt { enableAggregatingTask = false }`** in `app/build.gradle.kts` — works around a real Hilt Gradle plugin bug (google/dagger#4976/#4048: `NoSuchMethodError: ClassName.canonicalName()`, a JavaPoet version mismatch on the aggregating task's own worker classpath) reproduced against Hilt 2.58 on this repo's AGP/Gradle combination. The aggregating task exists to discover `@Module`/`@EntryPoint` types published from a *separate* library module's AAR; `:app` is this repo's only Hilt consumer (`:api` can't use Hilt at all), so disabling it costs nothing here. Revisit when Hilt/AGP move past the versions above.

**`:app:kspDebugKotlin` can fail with `PSI has changed since creation`, and it is not your code.** The error surfaces as `[ksp] [Hilt] Access to invalid ... KotlinAlwaysAccessibleLifetimeToken: PSI has changed since creation` out of Hilt's `ComponentTreeDepsGenerator` — a K2 Analysis API bug, reproducible from a clean build with `--rerun-tasks --no-build-cache`. It is sensitive to **comment text**, which makes it look like nonsense when you hit it. Do not infer a rule from one reproduction: while landing issue #11, a specific comment block reproduced it 100% of the time and the natural conclusions all turned out to be wrong when tested. Block size was excluded (a 50-line KDoc on the same new file builds clean), so was the file being new to KSP, so was a nested `/** */` marker inside the comment, and so was the paragraph that looked most suspicious. **Reword, do not restructure** — and specifically do not downgrade documentation to `//` line comments or trim it on the theory that KDoc or length is the problem. Neither is.

### Search tab (issue #9, `ui/search/`)

`SearchViewModel` owns a debounced live-search pipeline built from stock `Flow` operators (`debounce(300) + flatMapLatest`) rather than iOS's hand-rolled task cancellation — Kotlin's `Flow` already has the debounce-and-switch primitive Swift concurrency lacks, so porting the manual-cancellation shape would have been the wrong move. `SearchUiState.state` (idle/searching/results/empty) is updated synchronously in `onQueryChanged` ahead of the debounce window, so a spinner or an idle reset shows immediately rather than 300ms later; the debounced `queryChanges` stream is what actually fires (or, via `flatMapLatest`'s cancellation, aborts) the network call. There is no on-device catalog clone in v1 (`docs/port-plan.md`'s implementer notes are explicit — "call the client directly"), so a failed search degrades straight to `SearchState.Empty` rather than falling back to a local index the way iOS's `.local`/`.server` split does.

**Rotation badge — no second predicate.** `AlbumSearchResult` (the `GET /library/` row shape) carries only `rotationBin: RotationBin?`, never a kill date — unlike `AlbumInfo.Rotation`, which pairs a bin with `kill_date` and is what `RotationPredicate` (deliberately `internal` to `:api`) actually evaluates. A search row therefore has nothing for the kill-date half of that predicate to compare against; `SearchResultRow.kt` reads `row.rotationBin` directly, mirroring iOS's `SearchResultRow.swift` (`if let bin = row.rotationBin`), which does the same for the identical reason. This is not a second, competing implementation of "is this in rotation" — it's reading the one field this row shape can answer.

**`onQueryChanged` ignores unchanged text, and that guard is load-bearing.** It flips `SearchState.Searching` on synchronously, and the only thing that flips it back off is an emission out of the internal `queryChanges` stream — a `MutableStateFlow`, which drops an assignment equal to the value it already holds. Setting the state without producing the emission that clears it leaves the spinner up forever with no request behind it. Compose's `String`-valued `BasicTextField` filters unchanged text before calling `onValueChange`, so `SearchScreen` can't reach it today; that is a property of the caller, not of the method, and the second call site (a "search this artist" deep link, a retry affordance) would strand the UI with no hint why. Same lesson as issue #8's composition-scope defect: a view model API that works only because of how it happens to be called is one new caller away from a user-visible break. Pinned by `repeating the same query is a no-op, not a stuck spinner`, which strands at `Searching` with the guard removed.

**Add-to-bin is a plain, non-suspend trigger.** `SearchViewModel.addToBin(row)` is not `suspend`; it checks its in-flight gate and flips it to `AddToBinStatus.InFlight` synchronously, then does its network work inside its own `viewModelScope.launch { }`. That sidesteps the composition-scope trap `LoginViewModel`'s `launch { }.join()` pattern exists to fix (see issue #8's post-mortem) by construction — there is no `rememberCoroutineScope()`-owned coroutine in the picture at all, since Compose's `onClick` isn't suspend and never needs to be for a fire-and-forget action like this one.
### Bin tab (issue #11, `ui/bin/`)

`ui/bin/BinViewModel.kt` + `ui/bin/BinUiState.kt` + `ui/bin/BinScreen.kt` port `WXYCDJ/Bin/BinViewModel.swift`/`BinView.swift`. `BinUiState` is a sealed hierarchy (`Loading`/`Populated`/`Empty`/`Error`) rather than one flat state with a `List` and booleans, and `Populated`'s constructor `require`s a non-empty list — the empty-bin-vs-failed-fetch distinction the issue calls out is enforced at construction, not just by a `when` branch a future edit could get wrong. Two deliberate divergences from the iOS source of truth, each flagged at its own site too:

- **Removal is optimistic.** iOS's `remove(_:)` awaits `DELETE /djs/bin` before touching `entries`; this port drops the row from `BinUiState` before the network call and only restores it (re-sorted, via the same dedupe+sort path every fetch goes through) if the call fails, because the issue's own interaction spec says "removal should feel immediate."
- **The `BinEntry -> AlbumSearchResult` detail-header bridge (`ui/bin/BinEntryDetailFallback.kt`) is `ui/bin`-local, not a shared `:api` factory.** iOS solves the identical problem with one `AlbumSearchResult.headerStandIn` factory `CatalogRow` and `BinEntry` both bridge through. This repo has no `CatalogRow`/on-device catalog clone yet to be that factory's second caller, and `AlbumSearchResult` lives in `:api`, outside issue #11's file ownership — so promote this bridge to `:api` alongside the catalog-clone work rather than leaving a second copy of its reasoning to drift.

## Testing

Three tiers, deliberate and plugin-free:

- **`:api` on JUnit 5** (`useJUnitPlatform()`), with kotlinx-coroutines-test and MockWebServer. `MockWebServer` is the `StubRequestSession` analogue.
- **`:app` on JUnit 4**, with Robolectric and Compose UI tests. Compose's `createAndroidComposeRule` is a JUnit4 `TestRule` and Robolectric ships a JUnit4 `Runner`; neither runs on the JUnit 5 platform without `de.mannodermaus.android-junit5`, and adding a plugin to unify them buys less than it costs.
- **`app/src/androidTest` on a real Android runtime**, JUnit 4 via `androidx.test`, run on a Gradle Managed Device (`./gradlew :app:atdApi30DebugAndroidTest`). This is the tier that exists because the other two *cannot be trusted about the platform* — see below.

Fixtures use WXYC-representative artists — Juana Molina / *DOGA*, Jessica Pratt / *On Your Own Love Again*, Chuquimamani-Condori / *Edits* — from `wxyc-shared/src/test-utils/wxyc-example-data.json`. **Do not** substitute mainstream artists. That file's `canonicalArtistNames` key holds the broader names-only pool, including the diacritic-bearing entries (Aşıq Altay, Csillagrablók, GIDEÖN, Hermanos Gutiérrez, Nilüfer Yanya) that the bin collation test needs.

### The instrumented tier, and why it is not optional

**Robolectric cannot answer questions about `java.*`.** Its `SdkSandboxClassLoader` sandboxes only the `android.*` package tree, so a call to `java.text.Collator.getInstance(...)` under a Robolectric runner resolves to the **bootstrap JDK's** `RuleBasedCollator` — the same implementation `:api:test` already exercises. `BinCollationParityTest` was written to check Android's `Collator` and could not. It was green throughout, and `Collator.FULL_DECOMPOSITION` reached `main` behind it: Android's `java.text.Collator` is `android.icu`-backed and its `decompositionMode_Java_ICU(int)` converter throws `IllegalArgumentException` for every mode but `CANONICAL_DECOMPOSITION` and `NO_DECOMPOSITION`, so the first Bin-tab load on any real phone would have crashed. Two host tiers, both green, one guaranteed crash.

So: **a host tier that tests the platform through a shim is evidence about the shim.** When correctness depends on Android runtime behavior — the platform `Collator`, Keystore/Tink, DataStore, the Hilt graph, anything with a `Shadow` — it needs a test in `app/src/androidTest`, not a cleverer Robolectric config. `app/src/androidTest/.../InstrumentedTierTest.kt` is that tier's tripwire (`PureJvmModuleTest`'s counterpart): it asserts `java.vm.name == "Dalvik"`, so any host-JVM-hosted impostor fails rather than quietly passing.

**A second, subtler trap the same incident exposed:** a test that *derives* its expected value from the code under test cannot detect a change to that code. `BinCollationParityTest` reads `strength` and `decomposition` off `BinSorting.newCollator()` to configure its ICU oracle, so mutating `BinSorting` mutates the oracle in lockstep. Measured, not theorized: changing `Collator.PRIMARY` to `Collator.TERTIARY` — which stops "Nilüfer Yanya" and "Nilufer Yanya" filing together, a visible bin defect — left **every** host suite green, because the parity test's oracle moved with it and `BinEntryTest`'s ordering fixtures happen to sort identically at all three strengths. Derive the oracle from a source the mutation cannot reach, or pin the literal.

Note what that gap actually was, because the fix is a one-liner and the wrong lesson is expensive: it was a **missing host assertion**, not a structural limit of the host tier. `BinEntryTest` now restates `Collator.PRIMARY` beside its existing decomposition pin, and the mutation fails on the host in milliseconds. **Do not defer a check to the instrumented tier because it happens to catch something** — the tier costs an emulator boot, and using it as a substitute for an assertion the host can make is how a cheap suite quietly stops carrying its weight. The instrumented tier's irreducible share is the set of claims the host *cannot* make at all: `BinCollationDeviceTest.theRealCollatorFacadeRejectsFullDecomposition` is the current example, and everything Hilt, Keystore, DataStore and Compose will add is the rest.

Set `strength` and `decomposition` explicitly, never defaulted.

### Shared test support

`app/src/test/kotlin/org/wxyc/dj/testing/` holds test helpers more than one screen needs — today just `MainDispatcherRule`, which routes `Dispatchers.Main` (and so every `viewModelScope`) through a `TestDispatcher`. Put a helper here rather than copying it per screen package. iOS's `WXYCDJTests/Support` precedent for *copying* small test support is about its two separate test **bundles**, where sharing costs a whole new SPM target; every `:app` test compiles into one source set, so here sharing costs an import and copying costs a file per screen that can drift.

## CI

`.github/workflows/ci.yml` runs **two jobs** on every PR and every push to `main`, concurrently.

`test-and-lint` is the host tier — run all three locally before pushing, because every push burns CI minutes:

```bash
./gradlew :api:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

`instrumented` boots a headless API 30 `aosp-atd` emulator and runs `app/src/androidTest`:

```bash
./gradlew :app:atdApi30DebugAndroidTest
python3 .github/scripts/assert_instrumented_tests_ran.py
```

It is a **separate job**, not another step, so unit-test feedback never queues behind an emulator boot. Three things about it are load-bearing:

- **A Gradle Managed Device, not a third-party emulator action.** AGP provisions the image and AVD itself, so the command above is identical locally and in CI — which matters more than usual here, because this tier's failures are exactly the ones a maintainer then has to reproduce by hand. `aosp-atd` is Google's headless UI-stripped test image, published for x86_64 and arm64-v8a alike, so one declaration serves the runner and an Apple-silicon laptop.
- **API 30 is the floor, and the floor is the point.** The tier's value is catching runtime behavior the host gets wrong, and that divergence is likeliest at the oldest runtime the app supports (minSdk 26; API 30 is as low as the ATD program goes). A second device at the top of the range is the right call once Compose surfaces land and targetSdk-36 behavior changes become the divergence that matters — that is a `create(...)` block in `app/build.gradle.kts` plus a matching CI task name, not a redesign.
- **The vacuity guard is not ceremony.** With an empty `androidTest` source set AGP skips the task and Gradle reports `BUILD SUCCESSFUL` in under a second, having booted nothing and measured nothing — verified, not assumed. Since `src/test` and `src/androidTest` are one word apart, that is a live way to buy an expensive green light that proves nothing. The guard sums `tests` minus `skipped` across the run's JUnit XML and fails on zero — subtracting skips because a suite silenced with `@Ignore` during a flake hunt reports `tests="7" skipped="7"` and would otherwise read as seven passing tests. It has **no freshness check**: in CI the workspace is a fresh checkout so the only XML present is this run's, but locally a stale `androidTest-results/` satisfies it, so a local green means something only on a clean tree.

`:api:test` is named explicitly rather than folded into `./gradlew check`, so that if it ever stops running that has to be a visible deletion from the workflow rather than a silent consequence of a task-graph change. **`WXYC-Android`'s workflow is `:app`-scoped only**; copying it literally would have left the entire `:api` half — the suites this repo exists to make fast — landing with zero coverage.

Lint runs with `abortOnError = true` against an empty `lint-baseline.xml`. Keep it empty: fix findings rather than baselining them. The two suppressions in `app/lint.xml` and the manifest each carry their reasoning inline, and both are verified false positives rather than deferred work.

## Distribution

Public Play listing, `org.wxyc.dj`, with a **new dedicated upload key** enrolled in Play App Signing — not the `WXYC-Android` key; separate keys keep one compromise from reaching both apps. See `docs/signing.md`, and record every key by SHA-256 fingerprint: `WXYC-Android`'s own release script warns that it accumulated four keystores whose names are "actively misleading."

Two consequences bind the code:

1. **Play reviewers must be able to sign in, and a reviewer cannot receive a mailed one-time code.** That makes the **password fallback load-bearing for store review**: it can never be removed, and must stay reachable in a fixed, documented number of taps from launch. The review account is provisioned out of band and **pre-verified** — `requireEmailVerification: true` refuses an unverified account on the password route, and the OTP route that would normally verify it is the one a reviewer can't use.
2. **The login screen's copy is a store-facing surface**, not decoration. It must state that dj.wxyc.org credentials are required and that the app is for WXYC station staff — that is what disambiguates a public listing for a staff tool.

## Out of scope

Don't add these without asking:

- Playback, flowsheet, schedule, request line — other apps own those.
- Rotation editing — needs an MD/SM role; a separate concept from the personal bin.
- Analytics or crash reporting of any kind, in either module, until the phase-2 telemetry issue lands. When it does, `:api` stays SDK-free and the DJ app gets **its own** PostHog project — `WXYC-Android` currently reports into the iOS project ([#39](https://github.com/WXYC/WXYC-Android/issues/39)), and copying that pattern would compound the mixing.
- KMP or a shared module with the iOS app. The invariants port as *tests*, not as shared code. Re-evaluate after v1 ships, on evidence of actual drift.

## Related repos

- `wxyc-dj-ios` — the app this ports. Its `CLAUDE.md` is the reference for every invariant.
- `Backend-Service` — owns the API (`apps/backend/`) and auth (`apps/auth/`).
- `wxyc-shared/api.yaml` — OpenAPI 3.0 source of truth for DTOs, and the source of the test fixture pool.
- `dj-site` — the web equivalent; reference UX for live-search behavior.
- `WXYC-Android` — the listener app. Conventions borrowed, release train shared with nothing here.
