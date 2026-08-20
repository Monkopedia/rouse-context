# Distribution

Rouse Context ships in two distributions, built from the same source tree and
selected by a Gradle build flag (`-Pgoogle`), not a product flavor (issue #467).
The bare build is the **FOSS** distribution; `-Pgoogle` opts into the
credentialed **Google** build. See `app/build.gradle.kts` (the `googleBuild`
resolution at the top of the file).

`-Pgoogle` has to be an explicit choice, so the build rejects the two property
sources nobody can see from the build command — the `ORG_GRADLE_PROJECT_google`
environment variable and `<gradle user home>/gradle.properties` — and logs the
resolved distribution at configuration time. That is a first line of defence,
not the guarantee; the guarantee is
[`scripts/check-apk-distribution.sh`](#verifying-a-built-apk), which reads the
built APK.

## The two distributions

| | **FOSS** (default build) | **Google** (`-Pgoogle`) |
|---|---|---|
| Background wake | [UnifiedPush](https://unifiedpush.org/) | Firebase Cloud Messaging |
| Crash reporting | ACRA → relay `POST /crash` | Firebase Crashlytics |
| Google Play Services / Firebase code | none | yes |
| `google-services.json` required to build | no | yes |
| Build command | `./gradlew :app:assembleRelease` | `./gradlew :app:assembleRelease -Pgoogle` |
| Runtime deps unique to it | UnifiedPush connector 2.5.0, ACRA 5.13.1 | firebase-bom (auth, messaging, crashlytics) |

Both share the same `applicationId` (`com.rousecontext`) and the same app code
behind distribution-agnostic Koin seams (`BackgroundDelivery`, `CrashReporter`,
device-identity providers — see `app/src/{foss,google}`). The FOSS build is the
same app, Firebase-free — not a separate package.

## Where each variant ships

| Channel | Variant | Artifact |
|---|---|---|
| **F-Droid** | FOSS | Built from source by F-Droid; ships our signature via the reproducible-builds path (below). |
| **GitHub Releases** | FOSS | `rouse-context-<version>-foss.apk` — sideload on de-Googled devices. |
| **GitHub Releases** | Google | `rouse-context-<version>-google.apk` — sideload on devices with Google Play Services. |
| **Google Play** | Google | `rouse-context-<version>-google.aab` (App Bundle, Play Console upload only). |

Each tagged `v*` release publishes all of these (see
`.github/workflows/release.yml`).

## Verifying a built APK

The FOSS claim is checked against the artifact, not against the build config
(issue #548). `scripts/check-apk-distribution.sh` unzips an APK and counts
Google/Firebase markers in it:

```bash
scripts/check-apk-distribution.sh --apk <path> --expect foss     # fails if ANY marker is present
scripts/check-apk-distribution.sh --apk <path> --expect google   # fails if NONE is present
```

It scans in two scopes, because the right pattern differs between them:

- **`classes*.dex`** — slash-form class references only (`com/google/firebase`,
  `com/google/android/gms`), because a DEX type descriptor uses slashes. Measured
  on the debug APKs: FOSS 0 / 0, Google 2869 / 6571.
- **everything else** (manifest, `resources.arsc`, `res/`, assets, `META-INF/`)
  — either separator, plus the `google_app_id` / `gcm_defaultSenderId` /
  `firebase_database_url` string resources the google-services plugin generates
  from `google-services.json`. Firebase also ships `res/raw/firebase_*_keep.xml`.
  An APK could in principle carry the credentials without the classes; this
  notices.

Two things would be false positives if the patterns were looser, and both are
present in a genuinely clean FOSS build:

- the substring `firebase` — our own `firebaseToken` wire field name, and
  UnifiedPush's own `…FirebaseReceiver`. Hence the `com.google.` anchor.
- dot-form `com.google.android.gms…` strings *inside* the DEX — four of them,
  all Intent-action and security-provider **name strings** that `androidx.activity`
  and Conscrypt carry whether or not Play Services exists
  (`…gms.provider.action.PICK_IMAGES`, `…gms.org.conscrypt`, and two more). No GMS
  code is linked. Hence the slash-form requirement in the DEX scope. A naive
  whole-APK `com.google.android.gms` grep reads 0 on the minified release APK and
  4 on the debug APK, which is how a gate like this gets "fixed" by being loosened
  into uselessness.

Both directions run in CI. `ci.yml` checks each debug APK immediately after its
build (they share one output path, so order matters), and `release.yml` checks
the **staged** `dist/…-foss.apk` and `dist/…-google.apk` — the exact files that
get uploaded. The `--expect google` direction is the positive control: it fails
if the patterns ever stop matching real Firebase code, so a green FOSS check
cannot mean "the matcher is broken". The script also self-tests every pattern
against a synthetic fixture, and refuses to run on an APK with no
`com/rousecontext` references in its DEX.

## FOSS first-run requirement: a UnifiedPush distributor

The FOSS build has no Firebase Cloud Messaging, so it cannot be woken by Google's
push network. It relies on [UnifiedPush](https://unifiedpush.org/): the user must
have a **UnifiedPush distributor app** installed (for example
[ntfy](https://ntfy.sh/)) for the phone to be woken while Rouse Context is
backgrounded. Without a distributor, the app still runs but cannot be woken
on-demand by an AI client. The Google build has no such requirement — FCM is
built in.

## One relay backend, both distributions

Both distributions talk to the **same** relay (`relay.rousecontext.com`). The
relay does not care which push transport a device uses: each device registers
its own push token / wake channel and its own auth identity at onboarding, so the
relay routes wakes per-device. FOSS devices wake via their UnifiedPush endpoint;
Google devices wake via FCM. There is no separate FOSS relay and no separate
Google relay — the per-device push/auth discriminator is all that differs.

## Signing and identity — what's safe to cross, what isn't

The FOSS release build is **reproducible**: a clean source build matches the
published GitHub Release FOSS APK byte-for-byte except for the v2/v3 APK Signing
Block (its PSS salt differs per signing run), which
[apksigcopier](https://github.com/obfusk/apksigcopier) normalizes away. Because of
that, F-Droid builds from source, confirms reproducibility against our published
asset, and then ships **our** signed APK rather than re-signing with the F-Droid
key (the fdroiddata recipe uses `Binaries:` + `AllowedAPKSigningKeys:`; see
`fdroid/com.rousecontext.yml`).

Consequences for users:

- **GitHub FOSS APK ↔ F-Droid APK: no uninstall.** Same `applicationId`, same
  signing identity. Users can move freely between the two.
- **Google ↔ FOSS: DO NOT cross.** The Google and FOSS builds are different
  signing/identity stories. Switching between them requires an uninstall/reinstall
  (which loses app data). Pick one distribution per device and stay on it.

The release signing certificate SHA-256 (public, used as
`AllowedAPKSigningKeys`) is the certificate that CI signs published releases
with (GitHub Actions `secrets.RELEASE_KEYSTORE`, not the local
`.signing/release.keystore`). Confirmed against the published v1.0.4 FOSS APK
via `apksigner verify --print-certs`:

```
7cc8d2d568eb3d20a5e190e77baa97b3bde80782dd2576f29088a16c4ce47850
```

## F-Droid submission status

The fdroiddata metadata recipe is staged at `fdroid/com.rousecontext.yml`. It is
**draft groundwork** — it has not been submitted to the upstream `fdroiddata`
repository. Submission is a deliberate, owner-gated manual step, performed after
on-device testing of the FOSS build. See that file's header for the submission
checklist.

The reproducible-`Binaries:` + `AllowedAPKSigningKeys:` path the recipe uses is
**proven in production**: the same maintainer's `com.monkopedia.healthdisconnect`
is already live on F-Droid (v1.1.1) shipping its upstream signature via exactly
this path. So the approach below is established, not speculative — what remains
is cutting the `v1.0.6` release (versionCode 7), which is the first version
submitted to F-Droid, and opening the fdroiddata merge request.
