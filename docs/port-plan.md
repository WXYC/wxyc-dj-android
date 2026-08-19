# wxyc-dj-android — porting the DJ tool to Android

Port `wxyc-dj-ios` (SwiftUI DJ tool: dj.wxyc.org sign-in, live library search, release metadata, per-DJ bin) to Android as a **new repo, `wxyc-dj-android`**, written as a **native Kotlin/Compose port** (no KMP), shipping an **online-only core first** and deferring the offline/indexing half to a second project.

Status: **approved plan**, in progress. The scaffold (PR 1) has landed; the remaining rows of the phasing table are filed as GitHub issues under the [v1 epic](https://github.com/WXYC/wxyc-dj-android/issues/1).

This is the reference document for the port. The invariants table below is the part that matters most — each row is a behavior that closed a specific reproduced defect on iOS, and each issue restates the rows it owns.

## Decisions already taken

These were settled before this plan was written; they are recorded here so a reader doesn't relitigate them.

| Decision | Choice | Reasoning |
|---|---|---|
| Home | New `wxyc-dj-android` repo | Mirrors the `wxyc-dj-ios` ↔ `wxyc-ios-64` separation. `WXYC-Android` is the **listener** app — the analogue of `wxyc-ios-64`, not of this — with playback, ExoPlayer, a spectrum analyzer, PostHog, and **no auth layer at all**. A `:dj` module there would share Gradle config and a theme while coupling a credentialed internal tool's release train to a public listener app's. |
| v1 scope | Online core | Sign-in (OTP-led + password), library search, album detail with LML enrichment, per-DJ bin. |
| Sharing | Native Kotlin port | The iOS package's invariants port as **tests**, not as shared code. KMP would make this a rewrite of the iOS app too, and block Android v1 on Swift-side decisions. Re-evaluate after v1 ships, on evidence of actual drift. |

## What is actually being ported

`wxyc-dj-ios` is ~9.1k LOC of source (3.1k app target / 6.0k `WXYCAPI` package) against ~11.3k LOC of tests. That test-to-source ratio is the important number: **the value in this codebase is disproportionately in the invariants, not the screens.** Most of them were each written to close a specific, reproduced defect, and each is documented at its site with the failure it prevents.

Roughly half of `WXYCAPI` is out of v1 scope — the offline catalog clone, the Spotlight indexer, the thumbnail cache, the connectivity latch, background refresh. The v1 slice is about **5,300 LOC of Swift**, distributed:

| Area | Swift LOC | Notes |
|---|---|---|
| Auth (`AuthService` 809, `SignInIdentifier`, `OTPSignIn`, `OfflineSessionPolicy`, `JWTPayload`, `TokenStorage`, `KeychainTokenStorage`, `RequestSession`/`CookielessSession`) | ~1,600 | The single hardest subsystem, and the one codegen cannot help with at all. |
| `APIClient` minus the catalog leg | ~300 | |
| DTOs (`AlbumSearchResult` 297, `AlbumInfo` 286, `BinEntry` 140, `AlbumMetadata`, `TrackMatchHint`, `RotationPredicate`, `JSONCoders`) | ~1,000 | |
| UI (login 4 files, search 4 files, detail 747, bin 2 files, root/shell) | ~2,450 | |

## Module structure

```
wxyc-dj-android/
├── api/           pure Kotlin/JVM library — networking, auth, DTOs, pure logic
├── app/           Android app — Compose UI, ViewModels, DI, platform storage
└── gradle/libs.versions.toml
```

`:api` as a **pure JVM module** (`org.jetbrains.kotlin.jvm`, not `com.android.library`) is the single most valuable structural decision in this plan, and it is the direct analogue of the iOS split that lets `swift test --package-path Packages/WXYCAPI` run the auth state machine, the DTO decoding, the identifier router, and the rotation predicate on the host with no simulator. On Android the equivalent payoff is larger, because the alternative is an *emulator*: the auth tests are the ones you will run hundreds of times, and they must run in milliseconds.

The cost is that anything touching Android APIs cannot live in `:api`. That is a smaller set than it looks:

- **`TokenStorage`** stays an interface in `:api` with the in-memory implementation beside it (exactly as iOS keeps the protocol + `InMemoryTokenStorage` in the package); the encrypted implementation lives in `:app`. iOS can keep `KeychainTokenStorage` in-package only because Security.framework exists on macOS too — that accident doesn't transfer, and the interface seam already exists to absorb it.
- **Connectivity** is phase 2, and lands the same way (`PathProvider`-style interface in `:api`, `ConnectivityManager` implementation in `:app`).
- OkHttp, kotlinx.serialization, and coroutines are all plain JVM. Nothing else in the v1 `:api` slice needs the platform.
- **Hilt is Android-only** (`com.google.dagger.hilt.android`), so every `@Module` lives in `:app`. `:api` exposes plain factories and constructors for `:app` to wire. This is a constraint on layering, not a limitation: it is what keeps `:api` runnable under a bare JVM test task.
- **`:api` stays SDK-free** — no Sentry, no PostHog, no analytics of any kind. This mirrors the rule `wxyc-dj-ios`'s `TELEMETRY_PLAN.md` makes central (*"`WXYCAPI` (and `WXYCAPIModels`) stay SDK-free"*), and it exists for the same reason the pure-JVM split does: an SDK dependency breaks host-testability and the layering at once. Decide it now rather than retrofit it — package-level errors already surface at app-layer call sites, which is where any future capture belongs.

## Tech choices

| Concern | iOS | Android | Note |
|---|---|---|---|
| HTTP | `URLSession` | **OkHttp + Retrofit** | Matches `WXYC-Android`. Retrofit handles the typed methods; response-header capture and verbatim conditional-GET headers ride on raw `Response` returns. **The 401-retry is not an interceptor or an `Authenticator`** — it lives in `APIClient`, per invariant 19, matching iOS. Two reasons: the status policy belongs to each caller (the phase-2 catalog leg also accepts 304), and an `Authenticator` would need `AuthService`, which needs the client — a DI cycle. The cookieless factory stays credential-unaware. |
| JSON | `Codable` + custom `JSONCoders` | **kotlinx.serialization** | Not Gson (which `WXYC-Android` uses). The `generate:kotlin` config in `wxyc-shared` already targets kotlinx.serialization, and `explicitNulls = false` / `ignoreUnknownKeys = true` map cleanly onto what `JSONCoders` does today. The wire mixes snake_case and camelCase, so **explicit `@SerialName` on every field** — no global naming strategy, same rule as the iOS "explicit `CodingKeys`, never `convertFromSnakeCase`" convention, and for the same reason. |
| Cookies | `CookielessSession` decorator | **`CookieJar.NO_COOKIES`** | See the invariants table — this maps *better* than the iOS version. |
| Token storage | Keychain | **spike required** — see Open questions | |
| Observable state | `@Observable` + SwiftUI | **`StateFlow` + `collectAsStateWithLifecycle`** | |
| DI | hand-written `AppDependencies` composition root | **Hilt** | Matches `WXYC-Android`. Use **KSP**, not the kapt that repo is still on. |
| Navigation | `NavigationStack` + `AlbumRoute` | **Navigation Compose, type-safe routes** | `@Serializable` route classes. `AlbumRoute`'s id-only equality maps directly and matters for the same reason. |
| Images | `AsyncImage` | **Coil** | Already in `WXYC-Android`. Its error callback is where the #86 classification hooks in. |
| Search debounce | `Task` + `sleep` + cancel-on-keystroke | **`debounce(300)` + `flatMapLatest`** | Strictly more natural than the iOS version: `flatMapLatest` gives newest-query-wins for free instead of hand-rolled task cancellation. |
| Tests | Swift Testing | **`:api` on JUnit5** + kotlinx-coroutines-test + MockWebServer; **`:app` on JUnit4** + Robolectric/Compose | `MockWebServer` replaces `StubRequestSession`. Two frameworks, one per module — deliberate and plugin-free. Compose's `createAndroidComposeRule` is a JUnit4 `TestRule` and Robolectric ships a JUnit4 `Runner`; neither runs on the JUnit5 platform without `de.mannodermaus.android-junit5`. `WXYC-Android` is JUnit4/Mockito, so there's no org precedent either way. Standardizing on JUnit4 everywhere is the acceptable alternative; leaving it unstated is not, and surfaces as a blocked PR 7. |

Target SDK levels: `compileSdk 36`, `targetSdk 36`, matching `WXYC-Android`. **`minSdk 26`** (Android 8.0), settled 2026-08-19. Not a capability floor — nothing in the v1 stack (Compose, DataStore, Tink, OkHttp) needs past API 21, and phase-2 FTS5 is decoupled from the platform by `BundledSQLiteDriver`. It's a **security** floor: API 24/25 devices are roughly ten years old and receive no security patches, and this app holds a live station credential. A DJ on such a phone has a real fallback in dj.wxyc.org, so the exclusion costs a login rather than their show.

**Token storage: DataStore + Google Tink.** `EncryptedSharedPreferences` is deprecated as of `security-crypto:1.1.0-alpha07` — main-thread StrictMode violations and keyset-corruption crashes on some OEMs — so the Keychain analogue is DataStore with Tink handling key derivation and nonce management over a Keystore-wrapped master key. Tink is a deliberate new dependency (per the no-third-party-packages-without-asking rule), chosen because credential-at-rest crypto is where bespoke AES-GCM goes silently wrong. **The DataStore file must be excluded from Android Auto Backup** (`android:allowBackup="false"` or a `dataExtractionRules` exclusion): Keystore keys are non-exportable, but a backed-up ciphertext blob is still a credential artifact leaving the device, and that is exactly what iOS's `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` prevents.

## The invariants that must port

This is the part of the plan that distinguishes a port from a rewrite. Each row is a behavior that closed a specific reproduced defect on iOS. The iOS source documents each one at its site; the porting agent should read that comment before writing the Kotlin, because in most cases the comment explains a failure mode the code alone does not reveal.

| # | Invariant | iOS issue | Android mapping |
|---|---|---|---|
| 1 | **Cookie suppression.** better-auth's `bearer()` adds `set-auth-token` *without* stripping the `Set-Cookie` it rides alongside. A default client stores the session cookie and replays it — including on the **next sign-in** — and `originCheckMiddleware` refuses a cookie-bearing native request with `403 MISSING_OR_NULL_ORIGIN` *before any credential check*. Suppression is load-bearing, not hygiene. | #99, #52 | **`CookieJar.NO_COOKIES`**, set in a **factory in `:api`** whose only public entry point applies it (constructor `internal`, no builder exposed); `:app` holds the Hilt `@Module`/`@Provides` that calls the factory, because Hilt is Android-only and cannot live in a pure-JVM module. The split is what preserves the property: a bare `@Provides` in `:app` alone would still leave an `:api` consumer free to construct a raw client. Done this way it is *stronger* than the iOS shape — a client-level property rather than a per-request flag, so the #99 failure (a third consumer added over a raw session, cookie handling at its default, nothing catching it) can't recur *within the module*. **It is not an app-wide guarantee**, and the iOS source says so about its own version: `RequestSession.swift` scopes the policy to "requests this package issues" and notes `AsyncImage` on `URLSession.shared` is uncovered. Coil is the same hole in the same place — it builds its own `OkHttpClient` unless handed one — so PR 7 must construct its `ImageLoader` over the `:api` factory's client (or give it `CookieJar.NO_COOKIES` directly). Latent today, since cover URLs are third-party CDN hosts; fatal the day art is proxied through `api.wxyc.org`. Pin with a test: sign in twice against MockWebServer, assert no `Cookie` header on the second request. |
| 2 | **Transient/terminal JWT split.** The session token is the credential; the JWT is a derived, re-mintable artifact. Sign-in and restore are two legs. A JWT-leg failure that is transient (5xx / network / undecodable 2xx) **keeps** the session and enters a pending window, re-minting lazily. Only a **401** on `/auth/token` is terminal. | #53 | Direct port. Model the state as a sealed interface over a `StateFlow`. |
| 3 | **Session-generation guard on the refresh success path.** A monotonic counter, bumped on `clearLocalSession` and on establishing a new token but **not** on a `set-auth-token` rotation, captured at the top of `refreshJWT` and re-checked before persisting — so a `signOut` landing during an in-flight refresh can't be resurrected by the rotated bearer being written back to storage. Without it: `state == signedOut` but a live token on disk that the next cold launch silently signs back in. | #66 | Direct port. |
| 4 | **Token-identity guard on the 401 demotion.** `currentJWT()`'s 401 demotes to signed-out **only if the rejected bearer is still the current session token**, so a stale in-flight refresh resuming after a concurrent re-sign-in can't clobber the new session. | #53 | Direct port. Interactive callers and (phase 2) background refresh share one auth service, so this is not hypothetical. |
| 5 | **Offline cold-launch grace window.** 30 days, anchored on **last confirmed server contact**, never the JWT `exp`; exclusive at the boundary; **fails closed on a non-finite anchor** (a tampered `+∞` would otherwise grant grace forever). | #57 | Pure logic — ports near-verbatim, as do its tests. Keep it in v1: it is ~60 lines and it is the difference between a DJ in a basement studio getting the app and getting a login screen. |
| 6 | **Identifier routing on `@`.** `/sign-in/username` validates the identifier's *shape* before any user lookup, and better-auth's default `/^[a-zA-Z0-9_.]+$/` rejects an `@` with `422 INVALID_USERNAME` ("Username is invalid") — which is why every email sign-in failed at any password before #97. Route on the `@` specifically, **not** an email regex: `@` is the exact complement of the failure, and a typo'd `dj@wxyc` gets a useful `400 INVALID_EMAIL` from the email route instead of a misleading 422 from the username route. | #97 | Pure function. Ports verbatim with its test table. |
| 7 | **OTP is a third credential onto the same lifecycle.** Two-leg send (`/auth/wxyc/lookup-email` → `/auth/email-otp/send-verification-otp`), then `/auth/sign-in/email-otp`. Both halves of signing in are **shared**, not restated — one orchestrator owns the state transitions and one wire helper owns the header capture and status table; each route is a single call passing its leg-1 closure. | #100 | Direct port, and the structural discipline matters more here than the code: the split exists because a second route free to restate those invariants is a second route free to restate one *wrongly*. |
| 8 | **Two error vocabularies on three endpoints.** better-auth's routes answer `{message, code}`; Backend-Service's own `/auth/wxyc/lookup-email` and the Express limiter answer `{error: …}`, which the `{message}` decoder cannot read at all — so that leg maps by **status only**. `{"email": null}` is the one failure this flow can name precisely ("No account matches that username" — never "or email", since an `@` skips the lookup). | #100 | Direct port. Conflating the vocabularies produces wrong copy. |
| 9 | **429 is rate-limited on every credential route.** Reachable in ordinary use: the limiter keys on `X-Real-IP` (10/15 min) and the control room shares an egress address, while an OTP sign-in spends three requests to a password's one. | #100 | Direct port. Note the JWT leg deliberately still renders 429 as a server failure. |
| 10 | **400/403 render the server's stated reason verbatim**; only 401 means "wrong credentials". Folding 403 into invalid-credentials is what made the origin-check refusal read as a bad password — i.e. invisible. | #100 | Direct port. |
| 11 | **A looked-up email is never displayed.** The lookup route is a mild enumeration vector accepted because it is rate-limited; rendering its answer would put it on screen. The destination type carries the keying address beside a separately-nullable *typed* address, with an internal constructor, so the leak is unrepresentable rather than resting on discipline. | #100 | Direct port. In Kotlin, `internal` on the constructor plus a module boundary does the same work. |
| 12 | **Resend cooldown is state flipped by a task, not a clock read.** 30 s, starts on **failure too** (a 429 is the case most worth throttling), and **survives a stage change** (otherwise "send → different account → send" loops past a budget that is per-IP and therefore not refilled by switching accounts). | #100 | The specific iOS trap — SwiftUI doesn't re-render because time passed — does not exist under Compose+Flow, but the *policy* (start-on-failure, survive-stage-change) is what matters and must port. Don't drop the flag for a timestamp comparison. |
| 13 | **Submitting a code is blocked while a send is in flight.** No `resendStrategy` server-side means a resend **replaces** the stored OTP; verifying the first mail's code mid-resend fails `INVALID_OTP` on a correctly-read code *and* burns one of 5 attempts. | #100 | Direct port. |
| 14 | **Rotation predicate, shared by every row type that carries a rotation record.** Any non-null bin counts (forward-compat for a cohort added server-side); kill-date compare is **strict** and lexicographic over fixed-width `YYYY-MM-DD`; an unreadable kill date is **expired, not un-expiring** (`"not-a-date"` sorts above every real day, so failing open would leave dead records on the shelf forever). | #93 | Pure logic, ports verbatim. Keep it in one place for the same reason: the online and cloned answers are alternatives, not complements, so divergence reads as the app contradicting itself. |
| 15 | **Bin wire shape.** `GET /djs/bin` returns a **bare array** of denormalized library rows, not the envelope api.yaml still declares. The **album is the key** (no per-row bin id), and there is **no added-at**, so the client sorts (filing name, then title) — server order is arbitrary and would reshuffle between refreshes. | #77, #80 | Direct port. `localizedStandardCompare` has no exact Kotlin analogue — use `java.text.Collator` with **`strength` and `decomposition` set explicitly, never defaulted**, and pin the ordering with a test over diacritic-bearing names — which the three headline fixtures don't supply, so draw them from the `canonicalArtistNames` key of `wxyc-example-data.json` (Aşıq Altay, Csillagrablók, GIDEÖN, Hermanos Gutiérrez, Nilüfer Yanya). **This is the one place the pure-JVM split can produce false confidence:** Android's `java.text.Collator` delegates to `android.icu`, the desktop JVM's does not, and default collation rules are not guaranteed to agree — so precisely these names are the likeliest to diverge between a green `:api:test` and the device. Rule-pinning removes most of the risk; a small Robolectric or instrumented parity test asserting the same ordering closes it. |
| 16 | **`[]` and `null` are different answers from `/djs/bin`.** `[]` is an authoritative empty bin; `null` is not a bin and must throw. dj-site coerces; we must not. | #60 | Port the throw. Its full payoff needs the phase-2 offline snapshot, but establishing the contract in v1 costs nothing and avoids retrofitting it under a coercing decoder later. |
| 17 | **Artwork precedence and dead-URL fallthrough.** `/library/info` → live row → (phase 2: clone) → LML, with LML strictly last because it can resolve to a **label logo** instead of the cover. Failures are recorded **by URL**, and only after classification: a connectivity-class error indicts the *link*, not the URL, and must not permanently retire a healthy cover. | #83, #86 | The classification is pure and ports verbatim. On Android the recorder hooks Coil's error callback instead of `AsyncImage`'s phase switch. **Do not simplify to "any network exception is transient"** — that swallows the decode-failure case, which is what a CDN 403/404 error page looks like by the time it reaches an image decoder, and silently disables the whole fallthrough. |
| 18 | **Detail fan-out branches on whether a fallback row exists.** With one (every tab push), LML runs **concurrently** with `/library/info` off the fallback's artist/title. Without one, `/library/info` is the only source of an artist name, so it is awaited first. | — | Direct port. In v1 there is no clone, so the no-fallback branch is only reachable if a route is constructed without one. |
| 19 | **`APIClient` 401 → invalidate → single retry**, with the status policy left to each caller rather than baked into the transport. | — | Direct port. In v1 only the 2xx policy exists; the split earns its keep when the catalog leg (which also accepts 304) lands in phase 2. |

## Screens

Four, plus the auth gate.

- **Login** — leads with the mailed one-time code; password behind "Sign in with password instead" (ADR 0006). An explicit three-state stage machine (`identifier` / `awaitingCode(destination)` / `password`), not booleans: two of the states carry data, and the stage is what keeps the screen's error surfaces from colliding. **One error surface, not one per credential** — every credential failure reports the same way, because the earlier design (code request reporting only by throwing) produced a real defect where a failed resend went unrendered *and* wiped the message already on screen. Every stage change clears the error. Code field normalizes to digits, caps at 6, and uses the platform's SMS/OTP autofill hint.
- **Search** — debounced (300 ms, ≥2 chars), rows showing artwork, title, artist, call number, format capsule, rotation badge, track-match badge, and an inline add-to-bin. Newest query wins.
- **Detail** — header (artwork + title/artist/label), catalog section, then LML-fed sections (release, genres/styles, listen, links, tracklist) and a rotation section. LML is **best-effort**: a 404, decode failure, or rate limit becomes a faint inline footer note, never a red banner — the catalog row still renders.
- **Bin** — sorted list, swipe-to-remove, pull-to-refresh, distinct empty vs. error states.

## Codegen: recommend deferring to phase 2

`wxyc-shared` already has `generate:kotlin` (openapi-generator, kotlinx.serialization, package `org.wxyc.api.models`), but its output is gitignored and **has never been vendored into any repo** — the config's own comment notes `WXYC-Android` hand-maintains its types. A DJ Android app would be the first real consumer, which argues for doing it here.

Against that, the honest yield for v1:

- **The entire auth surface is unmodeled.** Only 5 of api.yaml's 62 paths are auth, and they are the `/auth/device/*` ones. Every route this app's ~1,600-LOC auth subsystem calls — the three sign-in routes, the OTP send, the lookup, `/auth/token`, sign-out — has **no schema at all**. The single hardest part of v1 gets zero help.
- **Most library DTOs are hand-authored on iOS for verified reasons**, several of which transfer (`AlbumInfo` is missing four fields the detail screen reads; `AlbumMetadata`'s api.yaml cognate is a *different schema* than its name suggests; the `/djs/bin` schema models a shape no handler emits).

That leaves roughly two of seven types generatable, against a vendored models module, a regenerate script, a verify script, and a CI drift workflow.

**Recommendation: hand-author the v1 DTOs; file phase-2 issues for the vendored models module.** Two things should trigger revisiting: [wxyc-shared#344](https://github.com/WXYC/wxyc-shared/issues/344) landing (which makes the bin type generatable), and the phase-2 DTO count growing with the catalog export.

**When it is revisited, port the infrastructure rather than re-deriving it.** `wxyc-dj-ios` already ships the whole pattern and `wxyc-ios-64` runs it too: `Packages/WXYCAPIModels/` (264 tracked files), `contract-version.json` as the exact-commit pin, `scripts/regenerate-api-types.sh`, `scripts/verify-api-types.sh`, and `.github/workflows/verify-api-types.yml` (which fires on both `pull_request` touching the vendored tree *and* `push` to the default branch — two individually-green PRs can merge into a drifted tree). Two curation lessons transfer directly, because `wxyc-shared/openapi-config/kotlin.yaml` has the same shape as the Swift config did: it still emits API clients (its `generateApiTests: false` suppresses only test stubs), and its own comment carries an open "should this be models-only like swift6.yaml" followup. **A second prerequisite, specific to this repo: that config sets `parcelizeModels: true`.** Parcelize needs the `kotlin-parcelize` plugin and `android.os.Parcelable`, which cannot compile in a pure-JVM module — so a models module generated as-configured would force `:api` to become `com.android.library` or force a third module, either of which undoes this plan's central structural decision. Set `parcelizeModels: false` for this consumer and check `useCoroutines: true` the same way. **Resolving that question is a prerequisite**, not a detail — the Swift side found `generateApis: false` doesn't actually suppress the `APIs/` tree under its generator, so the scripts drop it by rsyncing only `Models/` + `Infrastructure/`. Expect to curate `Infrastructure/` empirically by compiling, not by taking the generator's full output.

One caution for whoever does revisit: **re-derive the generated-vs-hand-authored table for Kotlin rather than inheriting iOS's answers.** Several iOS blockers are Swift-specific — an optional `matchedVia` that would push an extra optional layer into SwiftUI is a `?: emptyList()` in Kotlin, and the generated rotation enum's case naming is a `when` mapping rather than a broken exhaustive switch. The verified *server-side* blockers (missing fields, wrong schemas, fictional wire shapes) transfer; the *language ergonomics* ones do not.

And the boundary that guard does not cross transfers exactly: a drift check proves the vendored tree matches api.yaml. It proves **nothing** about whether api.yaml matches the handler that serves the endpoint — which is precisely how the `/djs/bin` failure happened, with the mirror and the spec agreeing and both wrong. Read the handler, not just the schema.

## Distribution and store obligations

**Public Play listing** (settled 2026-08-19). The roster turns over every semester, so self-serve install beats maintaining a tester list and collecting Google accounts for people who cycle out in months. Store ratings and listener confusion are explicitly **not** decision criteria — this is an internal tool. `applicationId` / `namespace` `org.wxyc.dj`, matching the iOS bundle ID; no collision with the listener app's `org.wxyc.WXYCCH`.

The listing is named clearly as a DJ tool and the **login screen carries the explanation** — so its copy is a store-facing surface, not decoration: it must state that the app requires dj.wxyc.org credentials and is for WXYC station staff. That's a requirement on PR 8.

Three obligations follow from choosing a public listing, and two of them are launch blockers outside this repo:

1. **Play reviewers must be able to sign in.** Play requires App Access demo credentials for anything behind a login wall — and a reviewer **cannot receive a mailed one-time code**. This makes the **password fallback load-bearing for store review**: it can never be removed, and must stay reachable in a fixed, documented number of taps from launch. An MD/SM provisions a dedicated account (e.g. `playreview@wxyc.org`) with an ordinary `dj` role, **pre-verified out of band** — `requireEmailVerification: true` refuses an unverified account on the password route, and the OTP route that would normally verify it is the one a reviewer can't use. `disableSignUp: true` means nobody self-registers, so this cannot be done at submission time. Rotate it on a schedule.
2. **The station privacy policy must be amended.** `website/pages/privacy.js` (last updated 2025-03-18) is listener-scoped and states *"This data does not include personally identifiable information."* That is false for an app that authenticates a named individual and stores a session token. Play's Data Safety declaration must match the linked policy, so a DJ-tools section is required: named station staff, email/username + session token collected for account management only, shared with nobody, with the retention/deletion path stated. Cross-repo dependency on `website`. The same disclosure serves `wxyc-dj-ios`'s App Store Privacy Nutrition Labels, so one amendment covers both apps and can't drift.
3. **Signing.** A **new dedicated upload key** for `org.wxyc.dj`, with the app enrolled in **Play App Signing** (Google holds the app signing key; a lost upload key is replaceable, a lost signing key is not). Not the `WXYC-Android` key — separate keys keep one compromise from reaching both apps. Record it by SHA-256 fingerprint in the repo's release docs from day one: `WXYC-Android`'s `release/config.sh` warns that it accumulated four keystores whose *"names are actively misleading"* and that they must be identified by fingerprint. The three-script `release/` flow is worth porting; the key is not.

## Phasing

Eleven PRs, each targeting the org's ≤1000-line guideline. Note the auth work is split across three: at ~1,600 Swift LOC for that subsystem against a repo-wide test-to-source ratio above 1:1, bundling it into one PR would land ~2,500 lines with the ported tests.

| PR | Content |
|---|---|
| 1 | **Repo scaffold.** **Public** repo — matching Backend-Service, dj-site, wxyc-shared, WXYC-Android and the website, and matching its own public listing. Private would conceal nothing: the auth surface this app exercises is already readable in public Backend-Service and dj-site. The precondition holds — **this repo needs no secrets file at all** (see the config note under Open questions), which is exactly what `WXYC-Android` lacked when a Discogs key went in. Carry `LICENSE` + `COMMERCIAL.md` per the public-repo convention, and a `CLAUDE.md` rule banning credential literals. Gradle KTS + version catalog, `:api`/`:app` modules, CI, JDK 17 toolchain, `.gitignore` (including **`.worktrees/`** — every PR from here is worktree-based, per the org git workflow, and `wxyc-dj-ios/.gitignore` carries the same line), `CLAUDE.md`, `README.md`. Set up signing per the Distribution section. Decide the **file-header convention** here too: `wxyc-dj-ios` mandates a header block (filename, module, one-line purpose, copyright) on every hand-written source file, with vendored generator output the sole exception. Adopt an analogue or explicitly drop it — deciding after PR 2 means retrofitting the whole `:api` slice. Decisions stay in the iOS `docs/adr/` set (0002 and 0006 are both cited here); this repo adds a `docs/adr/` tree only when it makes a decision the iOS app didn't. |
| 2 | **`:api` foundations.** `Configuration` with `.production` / `.localDevelopment` presets, the cookieless OkHttp client **factory** (its Hilt provider lands in `:app` with PR 7), `TokenStorage` interface + in-memory impl, JWT decoder. Invariant 1. |
| 3 | **`:api` sign-in state machine.** Password sign-in, `restoreSession`, the transient/terminal split, the generation and token-identity guards, `OfflineSessionPolicy`, identifier routing. Invariants 2–6. |
| 4 | **`:api` OTP path.** Lookup, send, verify, the two error vocabularies, rate limiting. Invariants 7–10. |
| 5 | **`:api` DTOs.** Search / info / metadata / bin shapes, JSON setup, rotation predicate, the bin wire contract. Invariants 14–16. |
| 6 | **`:api` `APIClient`.** Typed methods over the transport, 401 → invalidate → single retry. Invariant 19. |
| 7 | **`:app` shell.** Compose scaffold, Hilt graph, encrypted token storage, auth gate. |
| 8 | **Login screen.** OTP-led, stage machine, resend cooldown, password fallback. Copy must state that dj.wxyc.org credentials are required and the app is for WXYC station staff — this screen is what disambiguates the public listing. The password path stays reachable in a fixed, documented number of taps (Play review depends on it). Invariants 11–13. |
| 9 | **Search tab.** Debounced search, result row, badges, add-to-bin. |
| 10 | **Album detail.** Sections, LML best-effort enrichment, artwork precedence and fallthrough. Invariants 17–18. |
| 11 | **Bin tab.** List, sort, swipe-remove, refresh, empty/error states. |

The same arithmetic that split auth applies to the DTO layer, which is why 5 and 6 are separate: ~1,000 LOC of DTOs plus ~300 of client, against a >1:1 test ratio, is another ~2,600-line PR. **PR 10 deserves a second look on the same grounds** — the iOS detail view is 747 LOC before tests — and should be split if the artwork-precedence tests come in heavy.

### Registration is three separate repos, not part of PR 11

The org map does **not** live in the new repo. `WXYC/CLAUDE.md` and `WXYC/AGENTS.md` (a maintained mirror, differing only in the CLAUDE/AGENTS token) belong to `jakebromberg/wxyc-workspace`, a personal meta-repo over the polyrepo; the "Related repos" cross-link belongs to `wxyc-dj-ios`. Bundling them into a `wxyc-dj-android` PR is not possible. Track them as three follow-up commits:

1. `wxyc-workspace`: add `wxyc-dj-android` to the mobile-apps table in `CLAUDE.md` **and** `AGENTS.md`. **Both files currently have uncommitted working-tree modifications** (an AWS-accounts section and a Pipeline-Hardening status correction), so coordinate rather than committing over an in-flight edit.
2. `wxyc-dj-ios`: add the sibling to the "Related repos" list in **`CLAUDE.md`** (the only file carrying that section — `README.md` has none), so this doesn't repeat the mirror-drift failure named just above.
3. Confirm the two org files stay in sync — they are mirrors, and updating only one is the failure mode.

### CI must run `:api`, which the obvious mirror does not

`WXYC-Android`'s workflow runs `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug` — both **`:app`-scoped**, and `lintDebug` is an Android-only task with no `:api` counterpart. Copying it literally would leave PRs 2 through 5 — the entire `:api` half, and every suite this plan calls the ones you will run hundreds of times — landing with **zero CI coverage**. It also triggers on `push: branches: [master]`, which is wrong for a `main`-default repo.

PR 1's workflow therefore runs `./gradlew :api:test :app:testDebugUnitTest :app:lintDebug` (or plain `./gradlew check`, which picks up `:api:test` automatically) on `branches: [main]`. Worth carrying over from that repo: `permissions: contents: read`, the concurrency group, and the report upload — a lint `textReport` reaches the console but the JUnit XML with real stack traces exists only on the runner.

Two scaffold details that bite on first run: if the lint baseline is to "start empty," commit an `<issues format="6"/>` stub, because AGP writes the file and **aborts the run** when `baseline` points at a nonexistent path. And `LICENSE` / `COMMERCIAL.md` are the *public* repos' convention — `WXYC-Android` carries them, `wxyc-dj-ios` carries neither — so this repo, being public, carries both.

## Testing

Port the iOS test **cases**, not the code — the 11.3k LOC of Swift tests are where the invariants are actually pinned.

**Each invariant lands as a failing test first**, per the repo-wide TDD default: red, minimum implementation, green, then look for the refactor. The invariants table is the source of the red tests, and it is the reason this port is worth doing test-first even where the production code is a mechanical translation — the tests encode failure modes the Kotlin will not otherwise reveal.

Within each PR, port its own highest-value suites first rather than following a global sequence: cookieless session belongs to PR 2, the auth-service and offline-policy suites to PR 3, OTP to PR 4, DTO decoding and rotation to PR 5.

Fixtures use WXYC-representative artists — Juana Molina / *DOGA*, Jessica Pratt / *On Your Own Love Again*, Chuquimamani-Condori / *Edits* — from `wxyc-shared/src/test-utils/wxyc-example-data.json`. Do **not** substitute mainstream artists. That file's `canonicalArtistNames` key holds the broader names-only pool, including the diacritic-bearing entries the collation test needs.

## Out of v1 (phase 2 backlog)

Each of these is a real project, not a follow-up chore:

- Offline catalog clone: conditional `GET /library/catalog`, NDJSON decode, wholesale replace with fail-closed rollback, empty-export refusal.
- Local search: FTS index + the online-first / offline-fallback router, plus the half-open probe that gives the offline latch an exit. **Use `BundledSQLiteDriver` (`androidx.sqlite`, stable since Room 2.7.0), not the platform SQLite.** FTS5 rides SQLite 3.9 and so is *probably* present from API 24, but it is not a guaranteed compile-time option and varies by OEM — which is why Room still ships no `@Fts5` annotation ([issue 146824830](https://issuetracker.google.com/issues/146824830)). Bundling a known build removes the platform variable and buys something better than parity: the *same* SQLite as the iOS clone, so `unicode61 remove_diacritics 2` tokenizes identically rather than merely similarly.
- Connectivity monitor, offline banner, and the request-outcome signal — including the **cancellation carve-out** (a cancelled request is not a connectivity signal; the search debounce cancels on every keystroke).
- System-search indexing — the Spotlight analogue. **Needs a spike before it needs a plan.**
- Background refresh (WorkManager), replacing `BGAppRefreshTask` + `BGProcessingTask`.
- Thumbnail cache for indexed items.
- Offline bin snapshot, with the written-empty vs. never-written distinction.
- Deep-link surface for a system-search tap, with the auth-replay stash.
- **Telemetry** — Sentry error reporting and PostHog analytics, tracking `wxyc-dj-ios`'s `TELEMETRY_PLAN.md` (no identity, no query strings, no session replay, no IP retention; a per-install anonymous id, with the pseudonymity of a ~dozens-strong DJ population stated openly). `WXYC-Android` already ships PostHog, so there is Android precedent to borrow. The `:api`-stays-SDK-free boundary is settled above and constrains PR 2 either way.
- **QR device-authorization sign-in** ([ADR 0002](https://github.com/WXYC/wxyc-dj-ios/blob/main/docs/adr/0002-qr-device-authorization-shared-computer-signin.md), `status: proposed` on iOS, with two unmerged branches). The control-room computer is shared across shows, and this lets a DJ approve a browser session by scanning a QR on their phone instead of typing a credential on a shared keyboard. Two things make it a natural phase-2 item rather than a v1 one: it is **strictly additive** — the phone must *already* be signed in to approve anything, so it presupposes the v1 credential set rather than competing with it — and it is the **one auth route family with real api.yaml schemas** (the `/auth/device/*` paths, 17 vendored `DeviceAuth*` types), so it is the only part of auth where codegen would actually pay. Note the iOS side gates approval behind biometrics and restricts it to `dj` and above; both apply here.

## Open questions — all resolved 2026-08-19

Recorded with their reasoning so they aren't reopened by default.

| Was open | Resolved | How |
|---|---|---|
| Token storage | **DataStore + Tink**, excluded from Auto Backup | Researched: `EncryptedSharedPreferences` deprecated at `security-crypto:1.1.0-alpha07`. |
| `minSdk` | **26** | Revised down from the proposed 29 — a security floor, not a capability one. |
| Distribution | **Public Play listing** | Turnover makes self-serve install worth more than roster control; ratings aren't a criterion. |
| Package identity | **`org.wxyc.dj`** | Verified free — the listener app is `org.wxyc.WXYCCH`. |
| FTS availability | **Moot** | `BundledSQLiteDriver` decouples FTS5 from the platform entirely. |

**`:api` needs no secrets mechanism.** `WXYC-Android` routes base URLs through `secrets.properties` → `BuildConfig` because it holds third-party API keys. This app's base URLs are public hostnames and its only credential is the DJ's own session, so `Configuration` carries hardcoded `.production` / `.localDevelopment` presets exactly as `Packages/WXYCAPI/Sources/WXYCAPI/Configuration.swift` does. **No `secrets.properties`, and none should be added** — that property is what makes a public repo safe here.

## Adjacent findings, unrelated to this port

While reading `WXYC-Android` for conventions: `app/src/main/java/org/wxyc/wxycapp/di/NetworkModule.kt` hardcodes a Discogs API key and secret in an `Authorization` header literal, even though the same values are already wired through `secrets.properties` → `BuildConfig.DISCOGS_API_KEY` / `DISCOGS_SECRET_KEY` in that module's `build.gradle`. **`WXYC/WXYC-Android` is a public repo**, and the literal is committed in history (`b945f02`), so those credentials are exposed and rotating them is not enough on its own. Not part of this work — flagging it for separate triage.

**`WXYC-Android` reports analytics into the wrong PostHog project.** Its `POSTHOG_API_KEY` is `phc_jUWlgO0a…` — the token for the **WXYC iOS** project (134292). Android listener events have been landing in the iOS project, which is why the org has no Android project at all. Confirmed by querying it: 180 days of Android traffic shows 11 people, 9 with a null `$os_version`. Minor for the listener app, but it matters here — phase-2 telemetry for the DJ app must get its own project rather than copying this pattern and compounding the mixing.
