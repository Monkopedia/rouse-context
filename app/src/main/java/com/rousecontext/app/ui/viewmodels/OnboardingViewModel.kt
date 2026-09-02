package com.rousecontext.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.ui.format.DisplayDateFormat
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OnboardingState {
    data object Checking : OnboardingState
    data object NotOnboarded : OnboardingState

    /**
     * Onboarding is in flight. [step] tells the UI which phase to label:
     * initial relay registration, or the longer ACME cert provisioning hop.
     */
    data class InProgress(val step: OnboardingStep) : OnboardingState
    data object Onboarded : OnboardingState
    data class Failed(val message: String) : OnboardingState
    data class RateLimited(val retryDate: String) : OnboardingState
}

/**
 * The step currently running inside [OnboardingState.InProgress]. The UI maps
 * these to different copy ("Registering" vs "Provisioning certificates") so
 * the user sees progress during the multi-second ACME hop added by #389.
 */
enum class OnboardingStep {
    Registering,
    ProvisioningCerts
}

/**
 * Determines whether the device is onboarded and drives the onboarding flow.
 *
 * On init, checks [CertificateStore.getSubdomain] to decide the initial route.
 * When the user taps "Get Started", we transition to
 * [OnboardingState.InProgress] while Firebase auth, relay registration, and
 * ACME cert provisioning (#389) run on the Application-scoped coroutine. Only
 * after the full flow succeeds do we advance to [OnboardingState.Onboarded]
 * so navigation goes to Home. Failures land in [OnboardingState.Failed] or
 * [OnboardingState.RateLimited] with a retry entry point rather than silently
 * dropping the user onto a half-configured dashboard.
 *
 * Both broad catches in [performRegistration] are preceded by a
 * `catch (e: CancellationException) { throw e }` (issue #667). Those guards are
 * defence in depth, not live-bug fixes, and the reason is worth writing down
 * because it is not visible from the catch clause:
 *
 *  - [performRegistration] only ever runs inside [appScope], which is
 *    `CoroutineScope(SupervisorJob() + Dispatchers.Main)` in `RouseApplication`.
 *    Nothing in production cancels it — there is no `appScope.cancel()` outside
 *    test sources and `RouseApplication` overrides only `attachBaseContext` and
 *    `onCreate`.
 *  - The guarded calls cannot manufacture a `CancellationException` on a live
 *    job either — but NOT because nothing here produces a *cancelled* `Task`.
 *    That is the tempting argument and it does not hold; the one that does is
 *    reachability. See the block below, and do not shorten it back.
 *
 * INVARIANT, measured against artifacts and expiring with them.
 *
 * Both guarded calls bottom out in `Task.await()`. In
 * `kotlinx-coroutines-play-services` `TasksKt.awaitImpl`, `Task.getException()`
 * is read and rethrown UNWRAPPED *before* `Task.isCanceled` is ever consulted —
 * on the already-complete fast path (`getException` at offset 7, `ifnonnull 62`
 * at 12, `athrow` at 62-63, with the `isCanceled` branch stranded at 16) and
 * again on the `OnCompleteListener` slow path that a real async Task takes
 * (`TasksKt$awaitImpl$2$1.onComplete`: `getException` at 0, `ifnonnull 56`,
 * `resumeWith(Result.failure(e))` at 56-77). So
 * `TaskCompletionSource.setException(CancellationException)` delivers a bare
 * `CancellationException` into a fully live job with `isCanceled == false`.
 * "Nothing produces a cancelled Task" does not clear these calls.
 *
 * Two producers of exactly that shape sit on the `-Pgoogle` runtime classpath.
 * The clearance is that neither is REACHABLE from here:
 *
 *  - `recaptcha` `internal/zzar.invoke` does
 *    `if (t is CancellationException) tcs.setException(t)`. Its only caller is
 *    the `Deferred`→`Task` bridge `internal/zzas.zza`, whose only callers are
 *    `RecaptchaTasksClient` / `Recaptcha.getTasksClient` — the reCAPTCHA
 *    Enterprise surface. firebase-auth reaches that solely through
 *    `internal/zzbx.zza(provider, …, RecaptchaAction)`, and every call site
 *    passes `EMAIL_PASSWORD_PROVIDER` or `PHONE_PROVIDER` with one of six
 *    actions (`getOobCode`, `signInWithPassword`, `signUpPassword`,
 *    `sendVerificationCode`, `mfaSmsEnrollment`, `mfaSmsSignIn`). By contrast
 *    `FirebaseAuth.signInAnonymously()` jumps straight to
 *    `firebase-auth-api/zzacq.zza(FirebaseApp, zzl, String)`, and none of the
 *    1194 `firebase-auth-api` classes backing it — or `getIdToken(false)` —
 *    reference recaptcha at all.
 *  - `play-services-base` `common/api/internal/zacc.onDestroy()` does
 *    `trySetException(CancellationException("Host activity was destroyed …"))`.
 *    `zacc` is Activity-bound (`zacc.zaa(Activity)`) and its only caller is
 *    `GoogleApiAvailability.makeGooglePlayServicesAvailable(Activity)`, which
 *    this app never calls. `signInAnonymously()`, `user.getIdToken(false)` and
 *    `FirebaseMessaging.getToken()` take no Activity.
 *
 * The `isCanceled` route is separately empty: firebase-auth and
 * firebase-messaging contain no reference to `CancellationException`,
 * `CancellationToken(Source)` or `Tasks.forCanceled` in any class file. That
 * empty result is a real absence rather than a broken scan — the same scan
 * finds `TaskCompletionSource` in 69/1402 and 6/75 class files respectively.
 * Note that `TaskCompletionSource` has NO `setCancelled`/`trySetCancelled` in
 * any version (only `setResult`/`trySetResult`/`setException`/`trySetException`),
 * so grepping for those two names can never hit; the real `isCanceled`-producing
 * surface is `Tasks.forCanceled()`, `TaskCompletionSource(CancellationToken)` +
 * `CancellationTokenSource.cancel()`, a custom `Task` overriding `isCanceled()`,
 * or package-private `zzw.zze()`.
 *
 * Resolved artifacts this was measured against, and expires with: firebase-bom
 * 34.12.0 (firebase-auth 24.0.1, firebase-messaging 25.0.1), recaptcha 18.6.1,
 * play-services-base 18.1.0, play-services-tasks 18.4.0,
 * kotlinx-coroutines-play-services 1.10.2.
 * `FirebaseCancellationClearanceTest` (src/testGoogle) pins those versions and
 * re-runs the scan, so a `firebase-bom` bump reddens instead of silently arming
 * a user-visible surface: the `fail(…)` calls below publish
 * `OnboardingState.Failed`, which renders as "Authentication error: … / Retry"
 * (`OnboardingDestination.kt`). If cancellation ever became deliverable here the
 * rethrow would replace that with a spinner whose cancel is a no-op.
 *
 * The `foss` bindings cannot throw at all (`KeypairDeviceCredentialProvider`
 * catches internally; `NoOpFcmTokenProvider` returns `""`).
 *
 * So no reachable path delivers cancellation to these catches today, and adding
 * the guards changes no error surface. They still earn their place: the safety
 * above is a claim about *callers*, and the version pin does not cover callers.
 * It expires silently the first time somebody cancels [appScope] or swaps in a
 * credential source that can be cancelled. The guard is cheaper than re-deriving
 * the argument above every time either changes.
 */
class OnboardingViewModel(
    private val certificateStore: CertificateStore,
    private val onboardingFlow: OnboardingFlow,
    private val registrationStatus: DeviceRegistrationStatus,
    private val credentialProvider: DeviceCredentialProvider,
    private val fcmTokenProvider: FcmTokenProvider,
    private val backgroundDelivery: BackgroundDelivery,
    private val appScope: CoroutineScope
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Checking)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val subdomain = certificateStore.getSubdomain()
            when {
                subdomain != null -> {
                    registrationStatus.markComplete()
                    _state.value = OnboardingState.Onboarded
                }
                // foss deferred activation (#463): a device that finished
                // onboarding but skipped picking a UnifiedPush distributor has
                // no subdomain yet. It is a valid (degraded) onboarded state —
                // land on Home, not back on Welcome forever. Registration fires
                // later when a delivery app is chosen (BackgroundDelivery).
                backgroundDelivery.isSupported && certificateStore.isOnboardingComplete() -> {
                    _state.value = OnboardingState.Onboarded
                }
                else -> _state.value = OnboardingState.NotOnboarded
            }
        }
    }

    /**
     * Flips to [OnboardingState.InProgress] and kicks off the onboarding flow
     * (Firebase auth → relay registration → ACME cert provisioning) on
     * [appScope] so the work survives this ViewModel being cleared if the UI
     * recomposes or navigates. Only advances to [OnboardingState.Onboarded]
     * after the full flow — including cert provisioning — succeeds.
     */
    fun startOnboarding() {
        val current = _state.value
        if (current is OnboardingState.Onboarded) return
        if (current is OnboardingState.InProgress) return

        // foss deferred activation (#463): there is no push endpoint to register
        // with until the user picks a UnifiedPush distributor, and the relay
        // requires a push target to register. So foss onboarding COMPLETES
        // without registering — the device lands on a (possibly degraded) Home,
        // and registration fires later when a delivery app reports its endpoint
        // (BackgroundDelivery). The google flavor registers here as before.
        if (backgroundDelivery.isSupported) {
            _state.value = OnboardingState.InProgress(OnboardingStep.Registering)
            appScope.launch { completeWithoutRegistration() }
            return
        }

        _state.value = OnboardingState.InProgress(OnboardingStep.Registering)

        // Launch on the Application-scoped coroutine so we don't get killed
        // if the user backgrounds the app or navigates around during the
        // several-second ACME hop. viewModelScope would be cancelled if the
        // host composable leaves composition.
        appScope.launch {
            performRegistration()
        }
    }

    /**
     * foss deferred-activation completion (#463): if a distributor was already
     * picked during onboarding its endpoint may have registered the device by
     * now (subdomain present) — treat that as fully onboarded. Otherwise mark
     * onboarding complete and land on a degraded Home; the BackgroundDelivery
     * banner nudges the user to set up a delivery app.
     */
    private suspend fun completeWithoutRegistration() {
        if (certificateStore.getSubdomain() != null) {
            registrationStatus.markComplete()
        } else {
            certificateStore.markOnboardingComplete()
        }
        _state.value = OnboardingState.Onboarded
    }

    private suspend fun performRegistration() {
        // Acquire the device credential (google: Firebase anon sign-in; foss:
        // keypair registration proof). Issue #462.
        val credential = try {
            credentialProvider.forRegistration()
                ?: return fail("Couldn't sign in. Check your connection and try again.")
        } catch (e: CancellationException) {
            // MUST stay above the broad catch (issue #667). CancellationException is an
            // Exception on the JVM and extends IllegalStateException, so either spelling
            // below would swallow it — and Kotlin does not diagnose a cancellation clause
            // placed under a broader one as dead code. See the class KDoc for why this
            // site cannot take delivery of cancellation today, and why the guard stays.
            throw e
        } catch (e: Exception) {
            return fail("Authentication error: ${e.message ?: "unknown"}")
        }

        // Get FCM registration token
        val fcmToken = try {
            fcmTokenProvider.currentToken()
        } catch (e: CancellationException) {
            // MUST stay above the broad catch (issue #667). Same reasoning as the
            // credential fetch above; see the class KDoc.
            throw e
        } catch (e: Exception) {
            return fail("Couldn't reach Firebase Cloud Messaging: ${e.message ?: "unknown"}")
        }

        // scrub: previously logged 20-char token prefixes (see #379). Device
        // credentials and FCM tokens are bearer/identity material -- even
        // prefixes are sensitive because logcat is reachable via adb /
        // READ_LOGS, and FCM tokens can be used to replay wake events.
        Log.i(
            TAG,
            "Starting onboarding, credential=${credential::class.simpleName}, " +
                "fcmToken=${fcmToken.length} chars"
        )

        // Surface the longer cert-provisioning hop to the UI so the user sees
        // progress rather than a stuck spinner.
        _state.value = OnboardingState.InProgress(OnboardingStep.ProvisioningCerts)

        handleResult(
            onboardingFlow.execute(
                credential = credential,
                fcmToken = fcmToken
            )
        )
    }

    @Suppress("LongMethod")
    private fun handleResult(result: OnboardingResult) {
        when (result) {
            is OnboardingResult.Success -> {
                registrationStatus.markComplete()
                _state.value = OnboardingState.Onboarded
            }
            is OnboardingResult.RateLimited -> {
                Log.w(TAG, "Rate limited on register, retryAfter=${result.retryAfterSeconds}")
                _state.value = OnboardingState.RateLimited(
                    retryDate = formatRetryDate(result.retryAfterSeconds)
                )
            }
            is OnboardingResult.RelayError -> {
                Log.e(TAG, "Relay error: ${result.statusCode} - ${result.message}")
                fail("Server error (${result.statusCode}). Please try again.")
            }
            is OnboardingResult.NetworkError -> {
                Log.e(TAG, "Network error", result.cause)
                fail("Network error. Check your connection and try again.")
            }
            is OnboardingResult.StorageFailed -> {
                Log.e(TAG, "Failed to save registration", result.cause)
                fail("Couldn't save registration. Try again.")
            }
            // Cert provisioning failures (#389). Subdomain is persisted but
            // certs are not — retry re-runs the full flow; OnboardingFlow
            // short-circuits on the already-registered device and only
            // re-attempts cert issuance via CertProvisioningFlow's
            // AlreadyProvisioned check.
            is OnboardingResult.CertRateLimited -> {
                Log.w(
                    TAG,
                    "Cert provisioning rate limited, retryAfter=${result.retryAfterSeconds}"
                )
                registrationStatus.markComplete()
                _state.value = OnboardingState.RateLimited(
                    retryDate = formatRetryDate(result.retryAfterSeconds)
                )
            }
            is OnboardingResult.CertRelayError -> {
                Log.e(
                    TAG,
                    "Cert provisioning relay error: ${result.statusCode} - ${result.message}"
                )
                registrationStatus.markComplete()
                fail("Certificate server error (${result.statusCode}). Try again.")
            }
            is OnboardingResult.CertNetworkError -> {
                Log.e(TAG, "Cert provisioning network error", result.cause)
                registrationStatus.markComplete()
                fail("Network error while issuing certificate. Check your connection and retry.")
            }
            is OnboardingResult.CertKeyGenerationFailed -> {
                Log.e(TAG, "Cert provisioning key-gen failed", result.cause)
                registrationStatus.markComplete()
                fail("Couldn't generate device keys. Please try again.")
            }
            is OnboardingResult.CertStorageFailed -> {
                Log.e(TAG, "Cert provisioning storage failed", result.cause)
                registrationStatus.markComplete()
                fail("Couldn't save certificate. Please try again.")
            }
        }
    }

    private fun fail(message: String) {
        _state.value = OnboardingState.Failed(message)
    }

    private fun formatRetryDate(retryAfterSeconds: Long?): String =
        retryAfterSeconds?.let { seconds ->
            DisplayDateFormat.shortDate(
                System.currentTimeMillis() + seconds * MILLIS_PER_SECOND
            )
        } ?: "later"

    fun retry() {
        val current = _state.value
        if (current is OnboardingState.InProgress) return
        if (current is OnboardingState.Onboarded) return
        _state.value = OnboardingState.InProgress(OnboardingStep.Registering)
        appScope.launch {
            performRegistration()
        }
    }

    companion object {
        private const val TAG = "Onboarding"
        private const val MILLIS_PER_SECOND = 1000L
    }
}
