# Play Console listing: declarations and store assets

Every answer below has to be given again on each submission, and Play re-opens several of them whenever the app changes. This file exists so the next submission is a diff rather than a re-derivation, and so that drift between the form and the privacy policy is visible in review rather than discovered by a rejection.

**The binding constraint:** the Data safety declaration must match [the privacy policy](https://wxyc.org/privacy). The policy was scoped to cover the DJ apps in [WXYC/website#228](https://github.com/WXYC/website/issues/228); its "The DJ apps" section names exactly four things these apps collect, and the Data safety rows below are that list transposed into Play's vocabulary. If either side changes, change both.

## App identity

| | |
|---|---|
| Package name | `org.wxyc.dj` — permanent, never reusable, matches `applicationId` and the iOS bundle ID |
| App name | WXYC DJ |
| Category | Music & Audio |
| Version | `versionCode 1` / `versionName "1.0"` — a versionCode is spent for good once a bundle carrying it reaches Play |

## Declarations that are simply "no"

Each is a fact about the code, not a judgement, and each is checkable in one grep.

| Declaration | Answer | What makes it true |
|---|---|---|
| Ads | Contains no ads | No ad SDK in `gradle/libs.versions.toml` or either module |
| Advertising ID | Not used | Nothing requests it; there is no ads or analytics SDK to |
| News app | No | |
| Government app | No | |
| Financial features | None | |
| Health apps | No | |

## Target audience and content rating

**Target age: 18 and over.** The app is an internal tool for station staff, who are UNC students. Declaring any under-13 bracket opts the listing into the Families policy programme — a materially larger compliance surface for an audience this app does not have.

**Content rating: Everyone / PEGI 3 expected.** The app authors no content of its own — it displays a station music catalogue and a per-DJ list drawn from it — so violence, sexual content, controlled substances, gambling and simulated gambling are all straightforwardly absent.

**Profanity is the one answer not to give reflexively.** An earlier revision of this file claimed the questionnaire answers were "all negative", which was firmer than the evidence supports. `SearchResultRow.kt` renders `albumTitle` and `artistName` verbatim from the WXYC library, this is freeform college radio, and that catalogue certainly contains records whose titles carry profanity. There is also **no explicit-content field anywhere in the wire model** — checked, not assumed — so the app can neither filter nor label such a title; it shows what the catalogue holds.

That is probably not a rating problem, since the strings are third-party metadata a DJ searched for rather than content the app wrote, and catalogue and reference apps are not generally rated up for it. But it is a judgement about how IARC treats searched third-party metadata, and it should be made deliberately rather than inherited from a sentence in this file. The honest framing at the question: *the app surfaces third-party music metadata in response to a search; it authors none of it.*

Three more that are easy to answer wrongly:

- **"News or Educational" is No.** The wording tempts a yes — the app does present factual information neutrally. But the test is *primary purpose*, and this one's is operational: a staff tool for preparing a show, not a reference product for a reader. The category's own examples are Wikipedia, dictionaries and WebMD, all of which exist to inform their user; this exists to get a DJ to air. It also squares with the Play category being Music & Audio rather than News or Education. Worth knowing why IARC asks at all: the flag contextualises otherwise-objectionable content, so that a medical reference discussing drugs clinically is not rated as drug content. Answering yes is a request for interpretive leniency this app does not need, and claiming it would be a misrepresentation.
- **Users do not interact.** There is no messaging, commenting, or any DJ-to-DJ surface. A DJ sees the shared station library and their own bin, and nothing another DJ authored.
- **No user-generated content is shared.** A bin is private to the account that owns it.

## Data safety

### Collected

All four are transmitted to `api.wxyc.org` over HTTPS. **None is shared with any third party**, and none is used for advertising or marketing — which is the policy's language, not a paraphrase.

| Play data type | What it actually is | Required? | Purpose | Policy clause |
|---|---|---|---|---|
| Personal info → Email address | The identifier a DJ signs in with, and the address a one-time code is mailed to | Required | App functionality, Account management | "Sign-in identifiers" |
| Personal info → User IDs | The username signed in with, plus the account id the access token carries | Required | App functionality, Account management | "Sign-in identifiers", "Session credentials" |
| App activity → In-app search history | What a DJ types into library search, sent to WXYC servers to return results | Required | App functionality | "Library searches" |
| App activity → Other user-generated content | The bin — the albums a DJ has set aside, stored against their account so it follows them across devices | Required | App functionality | "Per-DJ records" |

### Not collected

Answerable "no" across the board, and unusually cleanly so. The app holds **one permission, `INTERNET`** (`app/src/main/AndroidManifest.xml`), and ships **no telemetry SDK of any kind** — no Sentry, PostHog, Firebase or Crashlytics. That is a standing rule in `CLAUDE.md` ("Analytics or crash reporting of any kind, in either module" is out of scope until a phase-2 telemetry issue lands, and `:api` stays SDK-free permanently), not an accident of nothing having been added yet.

So: no location, contacts, photos or videos, audio files, files and docs, calendar, messages, web browsing, device or other IDs, crash logs, or diagnostics.

### Security practices

- **Encrypted in transit — yes.** HTTPS only, to a single host. `Configuration.production` names `https://api.wxyc.org` and nothing else, and there is no cleartext allowance in the manifest.
- **Users can request deletion — yes**, by writing to the address in section 7 of the privacy policy.

  Worth understanding rather than just ticking: Play's *in-app account deletion* requirement keys on apps that let a user **create** an account. This one cannot — Backend-Service sets `disableSignUp: true` and accounts are provisioned by a station manager — so that specific obligation should not attach. Answer the deletion question yes regardless, because the route genuinely exists.

### Two judgement calls, recorded so they are not silently re-litigated

1. **Do not claim search queries are "processed ephemerally."** A targeted search of Backend-Service found no persistence of search terms in the schema or the backend code, but that is a negative result, and HTTP access logs can capture query strings independently of application code. Plain "collected" is accurate either way; "ephemeral" is a claim that would need positive evidence about the whole request path, including nginx.
2. **Passwords are not declared, because Play has no category for them.** The Data safety data-type list does not include credentials. Declaring the email address and user ID is the accepted treatment; there is no row to add.

## App access

Play requires working credentials because the app is entirely behind a login wall, and **the password path is the only one a reviewer can use** — the screen leads with a mailed one-time code, which a reviewer cannot receive. See [#32](https://github.com/WXYC/wxyc-dj-android/issues/32) for the account itself.

**No credential, and no account identifier, belongs in this file or anywhere else in this repo** — it is public. Both live in Play Console's App access section and in the station password manager.

The instructions field is the part that prevents a rejection, and it has to name the fallback explicitly, because the code path is the visually dominant one. It must:

1. State that the app is an internal tool for WXYC station staff requiring a dj.wxyc.org account.
2. Say **not** to use the mailed-code path, and why — the code goes to a station address the reviewer has no access to.
3. Give the tap sequence in the screen's own words: tap **"Sign in with password instead"** (the text button directly below **"Send login code"**, visible at launch with nothing typed), enter the username in **"Username or email"**, the password in **"Password"**, then tap **"Sign In"**.
4. Name the three areas to exercise: Search, the album detail screen, and Bin.

Quote those labels verbatim from `app/src/main/res/values/strings_login.xml`; if that file changes, this text is stale and a reviewer is following directions that no longer match the screen.

## Store assets

| Asset | Spec | Source |
|---|---|---|
| App icon | 512x512, 32-bit PNG, under 1 MB | `tools/render-store-icon.py` (produces ~130 KB) |
| Feature graphic | 1024x500 PNG or JPEG | **Not yet produced** |
| Phone screenshots | 2–8, 16:9 or 9:16, min 320px | **Not yet produced** |

The icon is generated rather than committed, because the render is deterministic and a regenerable 130 KB binary in the tree is one more thing to keep in sync with the launcher icon it has to match:

```sh
python3 tools/render-store-icon.py play-store-icon-512.png
```

It needs `wxyc-dj-ios` checked out beside this repo (or `$WXYC_DJ_IOS` pointing at it) because it reads that app's icon source directly — which is what keeps the two apps' marks from drifting. It renders the **72dp visible aperture** of the adaptive icon rather than the full 108dp canvas, so the listing shows what a home screen shows; see the script's own docstring for why that is the right crop.

## Before each submission

- [ ] Re-confirm the review account still signs in — a dormant or rotated account fails silently until a reviewer hits it ([#32](https://github.com/WXYC/wxyc-dj-android/issues/32))
- [ ] Re-read [the privacy policy](https://wxyc.org/privacy) against the Data safety table above; if the app collects anything new, both change together
- [ ] Confirm the App access instructions still match `strings_login.xml`
- [ ] Confirm no telemetry SDK has been added — it would add Data safety rows and contradict the "not collected" list
- [ ] Bump `versionCode`; the previous value can never be reused
