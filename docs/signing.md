# Signing and release

## The keys

The app is enrolled in **Play App Signing**: Google holds the *app signing key* and re-signs every upload with it. What we hold is the **upload key** — the thing that proves an upload came from us. That asymmetry is the reason for the setup: a lost or compromised upload key is replaceable through Play support, a lost app signing key is not.

The upload key is **new and dedicated to `org.wxyc.dj`**. It is deliberately not the `WXYC-Android` key. Separate keys mean one compromise reaches one app.

## Identify keys by fingerprint, never by filename

Record every key's **SHA-256 fingerprint** here and in the Play console, and treat the fingerprint as the key's identity. `WXYC-Android`'s own `release/config.sh` carries a warning earned the hard way: that repo accumulated four keystores whose *"names are actively misleading."* A filename tells you nothing you can verify.

To read a fingerprint:

```bash
keytool -list -v -keystore <path-to-keystore> -alias <alias> | grep 'SHA256:'
```

| Key | Alias | SHA-256 fingerprint | Held by |
|---|---|---|---|
| Upload key | _(record on creation)_ | _(record on creation)_ | WXYC MD/SM, offline |
| App signing key | — | _(copy from Play Console → App integrity)_ | Google (Play App Signing) |

Fill both rows in the same change that creates the key. An unrecorded fingerprint is how the misleading-filename problem starts.

## Local configuration

Release builds read `keystore.properties` from the repo root. It is **gitignored, and so are `*.jks` / `*.keystore`** — no signing material enters version control. It is also absent from CI, which is fine: only `assembleRelease` needs it, and `app/build.gradle.kts` skips the signing config entirely when the file is missing, so an ordinary build and the whole CI task set work without it.

```properties
storeFile=/absolute/path/outside/the/repo/wxyc-dj-upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep the keystore itself outside the working tree. Putting it in the repo directory and relying on `.gitignore` is one `git add -f` away from a mistake that rotation is the only remedy for.

## Creating the upload key

```bash
keytool -genkeypair -v \
  -keystore wxyc-dj-upload.jks \
  -alias wxyc-dj-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=WXYC, OU=WXYC 89.3 FM, O=WXYC, L=Chapel Hill, ST=NC, C=US"
```

Then: back the keystore up somewhere the station retains across officer turnover, record the fingerprint in the table above, and enroll the app in Play App Signing at first upload.

## Rotation

Rotate the upload key on a schedule, and immediately if it is ever exposed. Play accepts an upload-key rotation request with the new key's certificate; the app signing key is untouched, so installed apps are unaffected. Record the new fingerprint and keep the old row with the date it was retired — a fingerprint history is what makes "is this build ours?" answerable a year later.
