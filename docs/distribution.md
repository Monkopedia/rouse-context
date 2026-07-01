# Distribution

Rouse Context ships in two distributions, built from the same source tree and
selected by a Gradle build flag (`-Pgoogle`), not a product flavor (issue #467).
The bare build is the **FOSS** distribution; `-Pgoogle` opts into the
credentialed **Google** build. See `app/build.gradle.kts`
(`val googleBuild = project.hasProperty("google")`).

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
is cutting the `v1.0.5` release (versionCode 6), which is the first version
submitted to F-Droid, and opening the fdroiddata merge request.
