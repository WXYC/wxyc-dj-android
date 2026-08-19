# WXYC DJ (Android)

The Android build of the WXYC DJ tool: sign in with your dj.wxyc.org credentials, search the WXYC library with live results, read release metadata, and keep a personal bin of records.

This is an **internal tool for WXYC station staff**, not a listener app. The listener app is [WXYC-Android](https://github.com/WXYC/WXYC-Android). It is the Android counterpart of `wxyc-dj-ios`, ported natively rather than shared — see [CLAUDE.md](CLAUDE.md) for why, and for the conventions the port holds to.

## Status

Scaffold. The v1 online core — sign-in (mailed one-time code, with a password fallback), library search, album detail with metadata enrichment, and the per-DJ bin — is being built out issue by issue. The offline half (catalog clone, on-device search index, background refresh, system-search integration) is phase 2.

## Layout

```
api/    pure Kotlin/JVM library — networking, auth, DTOs, pure logic. No Android SDK.
app/    Android app — Compose UI, view models, DI, platform-backed storage.
```

`:api` is a plain JVM module on purpose, so its suites run on the host in milliseconds instead of in an emulator. That constraint is load-bearing and is spelled out in `CLAUDE.md`; a test in `api/src/test` fails if the Android SDK ever reaches its classpath.

## Building

Needs JDK 17 (the Gradle toolchain provisions it if absent) and the Android SDK. Point Gradle at the SDK with a `local.properties` — untracked, one line:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :api:test              # host tests, no emulator
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Those three are exactly what CI runs. Run them before pushing.

## Releasing

Release builds are signed from a `keystore.properties` that is never committed and is absent from CI, so its absence cannot break an ordinary build. See [docs/signing.md](docs/signing.md).

## Related

- [`wxyc-dj-ios`](https://github.com/WXYC/wxyc-dj-ios) — the app this ports, and the reference for every behavior in the invariants table.
- [`WXYC-Android`](https://github.com/WXYC/WXYC-Android) — the public listener app. Different product, different release train.
- [`Backend-Service`](https://github.com/WXYC/Backend-Service) — the API and auth service this talks to.
- [`dj-site`](https://github.com/WXYC/dj-site) — the web equivalent; reference UX for live-search behavior.
