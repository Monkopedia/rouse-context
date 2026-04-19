package com.rousecontext.work

import android.util.Log
import com.rousecontext.api.CrashReporter
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Observes per-integration enabled-state changes in [IntegrationStateStore] and
 * automatically pushes the current enabled set to the relay via
 * `/rotate-secret`. This is the device-side half of issue #285: without this
 * listener the "Disable integration" UI flips a local DataStore flag but never
 * tells the relay, leaving the per-integration secret valid for any AI client
 * that already has the URL.
 *
 * Behavior:
 *  - Observes every configured integration's `observeUserEnabled` flow.
 *  - Combines them into the current `Set<String>` of enabled IDs.
 *  - Debounces rapid flips so toggling three integrations in a second
 *    coalesces into a single push.
 *  - Only pushes when the enabled set actually changed since the last push
 *    (so we do NOT push on cold-start emissions that reflect persisted state).
 *  - Retries transient failures with exponential backoff (3 attempts,
 *    1s/2s/4s), matching [com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel].
 *  - On success, persists the relay-issued secrets into [CertificateStore].
 *
 * Does not replace the onboarding-time push performed by `IntegrationSetupViewModel`:
 * the VM still handles first-integration setup (which also triggers cert
 * provisioning), this class handles every subsequent enable/disable flip from
 * Settings. Any future deduplication is a separate refactor.
 *
 * Scoping: follows `.claude/rules/coroutines.md` — takes a caller-provided
 * `CoroutineScope` (the app's singleton `appScope`) and never creates a
 * detached scope. Cancellable: when the scope is cancelled, the observer
 * tears down cleanly.
 */
@Suppress("LongParameterList")
class IntegrationSecretsSynchronizer(
    private val integrations: List<McpIntegration>,
    private val stateStore: IntegrationStateStore,
    private val relayApiClient: RelayApiClient,
    private val certStore: CertificateStore,
    private val appScope: CoroutineScope,
    private val crashReporter: CrashReporter = CrashReporter.NoOp,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val backoffFactor: Long = BACKOFF_FACTOR,
    private val pushAttempts: Int = SECRETS_PUSH_ATTEMPTS
) {
    /**
     * Stable ordering of integration IDs used to build the push payload. The
     * relay's valid-secrets map is rebuilt on every push, so list order is
     * not observable to the AI client, but we keep it deterministic for
     * test assertions and relay-side logging.
     */
    private val allIds: List<String> = integrations.map { it.id }

    /**
     * Last enabled set successfully pushed to the relay. `null` means we have
     * not observed any change from the initial cold-start state yet; in that
     * case we suppress the push (the onboarding VM owns the first-ever push).
     */
    @Volatile
    private var lastPushedSet: Set<String>? = null

    private val mutablePushCount = MutableStateFlow(0)

    /**
     * Count of successful pushes this synchronizer has performed in this
     * process. Incremented only on `RelayApiResult.Success` + local store
     * success. Exposed for tests that want to wait for the auto-push to
     * complete rather than poll the relay counter.
     */
    val successfulPushCount: StateFlow<Int> = mutablePushCount.asStateFlow()

    private var job: Job? = null

    /**
     * Start observing state-store flips. Idempotent — subsequent calls are
     * no-ops. Called once during Koin graph construction via
     * `createdAtStart = true`.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        if (job != null) return
        if (integrations.isEmpty()) {
            // Nothing to observe — no flow to combine over. Return early;
            // the synchronizer is effectively idle for this app variant.
            return
        }
        val perIdFlows = integrations.map { integration ->
            stateStore.observeUserEnabled(integration.id)
        }
        job = appScope.launch {
            // combine() emits once all upstream flows have produced a value,
            // then on every subsequent change. We drop the first emission so
            // cold-start state (the persisted on-disk set) does not count as
            // "a change" — we only push on real user flips.
            combine(perIdFlows) { values ->
                allIds
                    .mapIndexedNotNull { index, id -> if (values[index]) id else null }
                    .toSet()
            }
                .distinctUntilChanged()
                .drop(1)
                .debounce(debounceMillis)
                .collect { enabledSet ->
                    if (enabledSet == lastPushedSet) return@collect
                    // If `certStore` already holds secrets for exactly this
                    // enabled set, someone else (the onboarding VM) just
                    // pushed it and we'd only duplicate the call. Mark it
                    // as the last-pushed set and return without re-pushing.
                    // Issue #285 ordering guard: synchronizer is *additive*
                    // to the VM's onboarding push, not a replacement.
                    val storedKeys = certStore.getIntegrationSecrets()?.keys
                    if (storedKeys == enabledSet) {
                        lastPushedSet = enabledSet
                        return@collect
                    }
                    pushWithRetry(enabledSet)
                }
        }
    }

    /**
     * Push [enabledSet] to the relay, retrying transient failures with
     * exponential backoff. Mirrors the `IntegrationSetupViewModel` retry
     * logic (3 attempts, 1s/2s/4s) so disable/enable flips have the same
     * resilience as onboarding pushes.
     */
    @Suppress("NestedBlockDepth")
    private suspend fun pushWithRetry(enabledSet: Set<String>) {
        val subdomain = certStore.getSubdomain()
        if (subdomain == null) {
            Log.w(TAG, "No subdomain provisioned; skipping rotate-secret push")
            return
        }
        val orderedIds = allIds.filter { it in enabledSet }
        var backoffMs = initialBackoffMs
        repeat(pushAttempts) { attempt ->
            when (val result = relayApiClient.updateSecrets(subdomain, orderedIds)) {
                is RelayApiResult.Success -> {
                    try {
                        certStore.storeIntegrationSecrets(result.data.secrets)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist rotated integration secrets", e)
                        crashReporter.logCaughtException(e)
                        return
                    }
                    lastPushedSet = enabledSet
                    mutablePushCount.value += 1
                    Log.i(
                        TAG,
                        "Pushed ${orderedIds.size} integrations to relay " +
                            "(attempt ${attempt + 1})"
                    )
                    return
                }
                is RelayApiResult.RateLimited -> {
                    Log.w(TAG, "rotate-secret rate-limited on attempt ${attempt + 1}")
                }
                is RelayApiResult.Error -> {
                    Log.w(
                        TAG,
                        "rotate-secret error on attempt ${attempt + 1}: " +
                            "${result.statusCode} ${result.message}"
                    )
                }
                is RelayApiResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "rotate-secret network error on attempt ${attempt + 1}",
                        result.cause
                    )
                }
            }
            if (attempt < pushAttempts - 1) {
                delay(backoffMs)
                backoffMs *= backoffFactor
            }
        }
        Log.e(
            TAG,
            "rotate-secret push failed after $pushAttempts attempts; " +
                "relay may accept disabled integrations until next push"
        )
        crashReporter.log(
            "IntegrationSecretsSynchronizer: push failed after $pushAttempts attempts"
        )
    }

    companion object {
        private const val TAG = "IntegrationSecretsSync"
        internal const val DEFAULT_DEBOUNCE_MS = 500L
        internal const val SECRETS_PUSH_ATTEMPTS = 3
        internal const val INITIAL_BACKOFF_MS = 1_000L
        internal const val BACKOFF_FACTOR = 2L
    }
}
