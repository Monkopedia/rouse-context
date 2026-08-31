package com.rousecontext.app.ui.format

import android.app.Application
import app.cash.turbine.test
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.McpUrlProvider
import com.rousecontext.app.auth.DeviceCredentialProvider
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.delivery.NoOpBackgroundDelivery
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.testing.MainDispatcherRule
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupState
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OnboardingState
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.TokenInfo
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.audit.AuditEntry
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.DeviceCredential
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import com.rousecontext.tunnel.RelayApiClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #635 — display date formatters must follow the device.
 *
 * Every formatter in this test renders the user's own data on the user's own
 * device, so the correct product behaviour is device-local: the ambient
 * [TimeZone] and [Locale] at the moment of the *format call*, not the ones
 * that happened to be installed when the enclosing class was initialised.
 *
 * The defect these tests pin down is that a `SimpleDateFormat` held in a
 * companion object (or as a top-level `val`) resolves its zone at
 * *construction*. Within a single process a DST rollover, a device timezone
 * change or a locale change therefore keeps rendering with the captured
 * values until the process restarts — silently, with no error, off by a fixed
 * offset. For the two retry-after sites that can name the wrong calendar day.
 *
 * Shape of every test: format, change the JVM default zone/locale, format
 * again, assert the rendering CHANGED. Nothing is pinned to a fixed zone —
 * that would be the opposite fix and a UX change (see
 * `.claude/rules/ux-changes.md`).
 *
 * The two zones are 26 hours apart so a `MMM d` rendering is guaranteed to
 * land on a different calendar day regardless of when the suite runs, and an
 * `HH:mm` rendering is guaranteed to shift by two hours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DateFormattersFollowDeviceTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreDeviceSettings() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    // --- AuditHistoryViewModel.TIME_FORMAT ("HH:mm") ---------------------

    @Test
    fun `audit history row time follows a device timezone change`() {
        val entry = auditEntry(FIXED_MILLIS)

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_EAST))
        val east = AuditHistoryViewModel.toHistoryEntry(entry).time

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_WEST))
        val west = AuditHistoryViewModel.toHistoryEntry(entry).time

        assertNotEquals(
            "audit-history row time must re-render after a device timezone change",
            east,
            west
        )
    }

    // --- AuditHistoryViewModel day-group label ("MMMM d, yyyy") ----------

    @Test
    fun `audit history day label follows a device locale change`() {
        val entries = listOf(auditEntry(FIXED_MILLIS))

        Locale.setDefault(Locale.US)
        val english = AuditHistoryViewModel.groupByDate(entries).single().dateLabel

        Locale.setDefault(Locale.JAPAN)
        val japanese = AuditHistoryViewModel.groupByDate(entries).single().dateLabel

        assertNotEquals(
            "audit-history day label must re-render after a device locale change",
            english,
            japanese
        )
    }

    @Test
    fun `audit history day label follows a device timezone change`() {
        val entries = listOf(auditEntry(FIXED_MILLIS))

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_EAST))
        val east = AuditHistoryViewModel.groupByDate(entries).single().dateLabel

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_WEST))
        val west = AuditHistoryViewModel.groupByDate(entries).single().dateLabel

        assertNotEquals(
            "audit-history day label must re-render after a device timezone change",
            east,
            west
        )
    }

    // --- AuditDetailScreen.kt DETAIL_TIMESTAMP_FORMAT --------------------
    //
    // `formatTimestamp` is a private top-level function in a Compose screen
    // file, so it is reached reflectively rather than by widening its
    // visibility. Keeping it private keeps this test valid against both the
    // pre-fix and post-fix trees, which is the point of the red-before run.

    @Test
    fun `audit detail timestamp follows a device timezone change`() {
        TimeZone.setDefault(TimeZone.getTimeZone(FAR_EAST))
        val east = auditDetailTimestamp(FIXED_MILLIS)

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_WEST))
        val west = auditDetailTimestamp(FIXED_MILLIS)

        assertNotEquals(
            "audit-detail timestamp must re-render after a device timezone change",
            east,
            west
        )
    }

    // --- IntegrationManageViewModel.DATE_FORMAT ("MMM d, yyyy") ----------

    @Test
    fun `authorized client date follows a device timezone change`() = runTest(testDispatcher) {
        TimeZone.setDefault(TimeZone.getTimeZone(FAR_EAST))
        val east = firstAuthorizedDate()

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_WEST))
        val west = firstAuthorizedDate()

        assertNotEquals(
            "authorized-client date must re-render after a device timezone change",
            east,
            west
        )
    }

    @Test
    fun `authorized client date follows a device locale change`() = runTest(testDispatcher) {
        Locale.setDefault(Locale.US)
        val english = firstAuthorizedDate()

        Locale.setDefault(Locale.JAPAN)
        val japanese = firstAuthorizedDate()

        assertNotEquals(
            "authorized-client date must re-render after a device locale change",
            english,
            japanese
        )
    }

    // --- OnboardingViewModel.DATE_FORMAT ("MMM d") -----------------------
    //
    // This one and the IntegrationSetupViewModel one below render a
    // rate-limit retry-after date. A stale offset here names the wrong
    // calendar day and tells the user to come back at the wrong time.

    @Test
    fun `onboarding retry date follows a device timezone change`() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(FAR_EAST))
        val east = onboardingRetryDate()

        TimeZone.setDefault(TimeZone.getTimeZone(FAR_WEST))
        val west = onboardingRetryDate()

        assertNotEquals(
            "onboarding retry-after date must re-render after a device timezone change",
            east,
            west
        )
        coroutineContext.cancelChildren()
    }

    @Test
    fun `onboarding retry date follows a device locale change`() = runBlocking {
        Locale.setDefault(Locale.US)
        val english = onboardingRetryDate()

        Locale.setDefault(Locale.JAPAN)
        val japanese = onboardingRetryDate()

        assertNotEquals(
            "onboarding retry-after date must re-render after a device locale change",
            english,
            japanese
        )
        coroutineContext.cancelChildren()
    }

    // --- IntegrationSetupViewModel.DATE_FORMAT ("MMM d") -----------------

    @Test
    fun `integration setup retry date follows a device timezone change`() =
        runTest(testDispatcher) {
            TimeZone.setDefault(TimeZone.getTimeZone(FAR_EAST))
            val east = integrationSetupRetryDate()

            TimeZone.setDefault(TimeZone.getTimeZone(FAR_WEST))
            val west = integrationSetupRetryDate()

            assertNotEquals(
                "integration-setup retry-after date must re-render after a timezone change",
                east,
                west
            )
        }

    @Test
    fun `integration setup retry date follows a device locale change`() = runTest(testDispatcher) {
        Locale.setDefault(Locale.US)
        val english = integrationSetupRetryDate()

        Locale.setDefault(Locale.JAPAN)
        val japanese = integrationSetupRetryDate()

        assertNotEquals(
            "integration-setup retry-after date must re-render after a locale change",
            english,
            japanese
        )
    }

    // --- helpers ---------------------------------------------------------

    private fun auditEntry(millis: Long) = AuditEntry(
        id = 1L,
        sessionId = "session-1",
        toolName = "get_steps",
        provider = "health",
        timestampMillis = millis,
        durationMillis = 12L,
        success = true,
        clientLabel = "Claude"
    )

    private fun auditDetailTimestamp(millis: Long): String {
        val method = Class.forName("com.rousecontext.app.ui.screens.AuditDetailScreenKt")
            .getDeclaredMethod("formatTimestamp", Long::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, millis, "unknown") as String
    }

    private suspend fun firstAuthorizedDate(): String {
        val tokens = listOf(
            TokenInfo(
                integrationId = "health",
                clientId = "client-1",
                createdAt = FIXED_MILLIS,
                lastUsedAt = FIXED_MILLIS,
                label = "Claude"
            )
        )
        val stateStore = mockk<IntegrationStateStore> {
            coEvery { isUserEnabled("health") } returns true
            coEvery { wasEverEnabled("health") } returns true
            every { observeChanges() } returns flowOf(Unit)
        }
        val tokenStore = mockk<TokenStore> {
            every { hasTokens("health") } returns true
            every { listTokens("health") } returns tokens
            every { tokensFlow("health") } returns MutableStateFlow(tokens)
        }
        val auditDao = mockk<AuditDao> {
            every { observeByDateRange(any(), any(), any()) } returns flowOf(emptyList())
        }
        val vm = IntegrationManageViewModel(
            integrations = listOf(fakeIntegration()),
            stateStore = stateStore,
            tokenStore = tokenStore,
            auditDao = auditDao,
            urlProvider = fakeUrlProvider()
        )
        vm.loadIntegration("health")

        var authorized = ""
        vm.state.test {
            awaitItem()
            authorized = awaitItem().authorizedClients.single().authorizedDate
            cancelAndIgnoreRemainingEvents()
        }
        return authorized
    }

    private fun CoroutineScope.buildOnboardingViewModel(): OnboardingViewModel {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns null
        }
        val onboardingFlow = mockk<OnboardingFlow> {
            coEvery {
                execute(any<DeviceCredential>(), any<String>())
            } returns OnboardingResult.RateLimited(retryAfterSeconds = RETRY_AFTER_SECONDS)
        }
        val credentialProvider = object : DeviceCredentialProvider {
            override suspend fun forRegistration(): DeviceCredential =
                DeviceCredential.Firebase("test-token")

            override suspend fun forProvisioning(): DeviceCredential =
                DeviceCredential.Firebase("test-token")
        }
        val fcmProvider = object : FcmTokenProvider {
            override suspend fun currentToken(): String = "test-fcm"
        }
        return OnboardingViewModel(
            certificateStore = certStore,
            onboardingFlow = onboardingFlow,
            registrationStatus = DeviceRegistrationStatus(initiallyRegistered = false),
            credentialProvider = credentialProvider,
            fcmTokenProvider = fcmProvider,
            backgroundDelivery = NoOpBackgroundDelivery,
            appScope = this
        )
    }

    private suspend fun CoroutineScope.onboardingRetryDate(): String {
        val vm = buildOnboardingViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.startOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()
        yield()
        val state = vm.state.value
        check(state is OnboardingState.RateLimited) { "expected RateLimited, got $state" }
        return state.retryDate
    }

    private suspend fun integrationSetupRetryDate(): String {
        val certStore = mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "cool-penguin"
            coEvery { getIntegrationSecrets() } returns emptyMap()
        }
        val certProvisioningFlow = mockk<CertProvisioningFlow> {
            coEvery { execute(any<DeviceCredential>()) } returns
                CertProvisioningResult.RateLimited(retryAfterSeconds = RETRY_AFTER_SECONDS)
        }
        val vm = IntegrationSetupViewModel(
            stateStore = mockk(relaxed = true),
            certProvisioningFlow = certProvisioningFlow,
            lazyWebSocketFactory = mockk<LazyWebSocketFactory>(relaxed = true),
            registrationStatus = DeviceRegistrationStatus(initiallyRegistered = true),
            relayApiClient = mockk<RelayApiClient>(relaxed = true),
            certStore = certStore,
            integrationIds = listOf("health"),
            credentialProvider = { DeviceCredential.Firebase("test-token") }
        )

        var retryDate = ""
        vm.state.test {
            awaitItem()
            vm.startSetup("health")
            testDispatcher.scheduler.advanceUntilIdle()
            var next = awaitItem()
            while (next !is IntegrationSetupState.RateLimited) {
                next = awaitItem()
            }
            retryDate = next.retryDate
            cancelAndIgnoreRemainingEvents()
        }
        return retryDate
    }

    private fun fakeUrlProvider() = McpUrlProvider(
        mockk<CertificateStore> {
            coEvery { getSubdomain() } returns "test-device"
            coEvery { getSecretForIntegration(any()) } returns "test-secret"
        },
        "rousecontext.com"
    )

    private fun fakeIntegration(): McpIntegration = object : McpIntegration {
        override val id = "health"
        override val displayName = "Health Connect"
        override val description = "Health data"
        override val path = "/health"
        override val provider = mockk<McpServerProvider>()
        override suspend fun isAvailable() = true
        override val onboardingRoute = "setup"
        override val settingsRoute = "settings"
    }

    private companion object {
        /** 2023-11-14T22:13:20Z — arbitrary but fixed. */
        const val FIXED_MILLIS = 1_700_000_000_000L

        /** UTC+14, the eastern-most civil zone. */
        const val FAR_EAST = "Pacific/Kiritimati"

        /** UTC-12: 26 hours behind [FAR_EAST], so the calendar day always differs. */
        const val FAR_WEST = "Etc/GMT+12"

        const val RETRY_AFTER_SECONDS = 300L
    }
}
