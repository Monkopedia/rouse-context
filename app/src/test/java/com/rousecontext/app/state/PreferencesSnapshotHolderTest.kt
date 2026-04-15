package com.rousecontext.app.state

import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import com.rousecontext.mcp.core.McpServerProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that [PreferencesSnapshotHolder] builds a live, reactive
 * [kotlinx.coroutines.flow.StateFlow] over each underlying store as required
 * by issue #136.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesSnapshotHolderTest {

    private lateinit var scope: CoroutineScope
    private lateinit var job: Job

    @Before
    fun setup() {
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Unconfined + job)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `snapshot reflects enabled flip from underlying store`() = runBlocking {
        val stateStore = FakeStateStore()
        val settingsStore = IntegrationSettingsStore(ApplicationProvider.getApplicationContext())
        val notifProvider = FakeNotificationProvider()
        val integrations = listOf(fakeIntegration("health", "/health"))

        val holder = PreferencesSnapshotHolder(
            integrationStateStore = stateStore,
            integrationSettingsStore = settingsStore,
            notificationSettingsProvider = notifProvider,
            integrations = integrations,
            appScope = scope
        )

        // Keep a live subscriber so WhileSubscribed starts collecting.
        scope.launch { holder.snapshot.collect { } }

        // Wait for first non-default emission (maps are non-null).
        withTimeout(TIMEOUT_MS) {
            holder.snapshot.first { it.integrationEnabled.containsKey("health") }
        }
        assertEquals(false, holder.snapshot.value.integrationEnabled["health"])

        stateStore.enabledFor("health").value = true
        withTimeout(TIMEOUT_MS) {
            holder.snapshot.first { it.integrationEnabled["health"] == true }
        }
        assertEquals(true, holder.snapshot.value.integrationEnabled["health"])
    }

    @Test
    fun `snapshot reflects notification settings change`() = runBlocking {
        val stateStore = FakeStateStore()
        val settingsStore = IntegrationSettingsStore(ApplicationProvider.getApplicationContext())
        val notifProvider = FakeNotificationProvider()

        val holder = PreferencesSnapshotHolder(
            integrationStateStore = stateStore,
            integrationSettingsStore = settingsStore,
            notificationSettingsProvider = notifProvider,
            integrations = listOf(fakeIntegration("health", "/health")),
            appScope = scope
        )
        scope.launch { holder.snapshot.collect { } }

        // Seed: wait for initial emission.
        withTimeout(TIMEOUT_MS) {
            holder.snapshot.first { it.integrationEnabled.containsKey("health") }
        }

        notifProvider.flow.value = NotificationSettings(
            postSessionMode = PostSessionMode.SUPPRESS,
            notificationPermissionGranted = true,
            showAllMcpMessages = true
        )
        withTimeout(TIMEOUT_MS) {
            holder.snapshot.first {
                it.notificationSettings.postSessionMode == PostSessionMode.SUPPRESS
            }
        }

        assertEquals(
            PostSessionMode.SUPPRESS,
            holder.snapshot.value.notificationSettings.postSessionMode
        )
        assertEquals(true, holder.snapshot.value.notificationSettings.showAllMcpMessages)
    }

    private fun fakeIntegration(integrationId: String, integrationPath: String): McpIntegration =
        object : McpIntegration {
            override val id: String = integrationId
            override val displayName: String = integrationId
            override val description: String = ""
            override val path: String = integrationPath
            override val onboardingRoute: String = "setup"
            override val settingsRoute: String = "settings"
            override val provider: McpServerProvider = object : McpServerProvider {
                override val id: String = integrationId
                override val displayName: String = integrationId
                override fun register(server: Server) = Unit
            }

            override suspend fun isAvailable(): Boolean = true
        }

    private class FakeStateStore : IntegrationStateStore {
        private val enabled = mutableMapOf<String, MutableStateFlow<Boolean>>()
        private val ever = mutableMapOf<String, MutableStateFlow<Boolean>>()
        private val changeSignal = MutableStateFlow(0)

        fun enabledFor(id: String): MutableStateFlow<Boolean> =
            enabled.getOrPut(id) { MutableStateFlow(false) }

        private fun everFor(id: String): MutableStateFlow<Boolean> =
            ever.getOrPut(id) { MutableStateFlow(false) }

        override suspend fun isUserEnabled(integrationId: String): Boolean =
            enabledFor(integrationId).value

        override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
            if (enabled) everFor(integrationId).value = true
            enabledFor(integrationId).value = enabled
            changeSignal.value++
        }

        override fun observeUserEnabled(integrationId: String): Flow<Boolean> =
            enabledFor(integrationId)

        override suspend fun wasEverEnabled(integrationId: String): Boolean =
            everFor(integrationId).value

        override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
            everFor(integrationId)

        override fun observeChanges(): Flow<Unit> = changeSignal.map { }
    }

    private class FakeNotificationProvider : NotificationSettingsProvider {
        val flow = MutableStateFlow(
            NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = false,
                showAllMcpMessages = false
            )
        )

        override suspend fun settings(): NotificationSettings = flow.value
        override fun observeSettings(): Flow<NotificationSettings> = flow
        override suspend fun setPostSessionMode(mode: PostSessionMode) {
            flow.value = flow.value.copy(postSessionMode = mode)
        }

        override suspend fun setShowAllMcpMessages(enabled: Boolean) {
            flow.value = flow.value.copy(showAllMcpMessages = enabled)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
