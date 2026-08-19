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

## Testing

Two frameworks, one per module — deliberate, and plugin-free:

- **`:api` on JUnit 5** (`useJUnitPlatform()`), with kotlinx-coroutines-test and MockWebServer. `MockWebServer` is the `StubRequestSession` analogue.
- **`:app` on JUnit 4**, with Robolectric and Compose UI tests. Compose's `createAndroidComposeRule` is a JUnit4 `TestRule` and Robolectric ships a JUnit4 `Runner`; neither runs on the JUnit 5 platform without `de.mannodermaus.android-junit5`, and adding a plugin to unify them buys less than it costs.

Fixtures use WXYC-representative artists — Juana Molina / *DOGA*, Jessica Pratt / *On Your Own Love Again*, Chuquimamani-Condori / *Edits* — from `wxyc-shared/src/test-utils/wxyc-example-data.json`. **Do not** substitute mainstream artists. That file's `canonicalArtistNames` key holds the broader names-only pool, including the diacritic-bearing entries (Aşıq Altay, Csillagrablók, GIDEÖN, Hermanos Gutiérrez, Nilüfer Yanya) that the bin collation test needs.

**One host-vs-device trap to know about:** `java.text.Collator` on Android delegates to `android.icu`; the desktop JVM's does not, and default collation rules are not guaranteed to agree. That is the one place the pure-JVM split can produce false confidence. Set `strength` and `decomposition` explicitly, never defaulted, and back the `:api` ordering test with a Robolectric or instrumented parity test.

## CI

`.github/workflows/ci.yml` runs three tasks on every PR and every push to `main`:

```bash
./gradlew :api:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Run all three locally before pushing — every push burns CI minutes.

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
