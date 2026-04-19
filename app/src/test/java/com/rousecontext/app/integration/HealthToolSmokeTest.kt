package com.rousecontext.app.integration

import com.rousecontext.api.McpIntegration
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.integrations.health.HealthConnectMcpServer
import com.rousecontext.integrations.health.HealthConnectRepository
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.mcp.core.TokenStore
import com.rousecontext.notifications.audit.AuditDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Batch B scenario 6 (issue #252): Health Connect integration smoke.
 *
 * Proves the plumbing: a `tools/list` call on the real
 * [HealthConnectIntegration] lights up against the harness's
 * [com.rousecontext.app.integration.harness.HarnessFakeHealthConnectRepository],
 * the request routes through [McpSession], and the audit listener
 * records the call. Does NOT test every health tool — one round-trip is
 * enough to catch wiring regressions (Koin missing the repository
 * binding, [McpIntegration.path] typo, DSL tool registration failure).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HealthToolSmokeTest {

    // Held across the test so we can reach the underlying
    // [com.rousecontext.app.integration.harness.HarnessFakeHealthConnectRepository]
    // via the integration's provider.
    private var fakeRepository: HealthConnectRepository? = null

    private val harness = AppIntegrationTestHarness(
        integrationsFactory = {
            // The shipping `HealthConnectIntegration` hard-wires
            // `RealHealthConnectRepository(context)` into its `McpServerProvider`,
            // bypassing the Koin override. Construct the integration directly
            // here with the harness fake so the health tool answers
            // deterministically instead of short-circuiting on
            // `HealthConnectUnavailableException`.
            val repo = com.rousecontext.app.integration.harness.HarnessFakeHealthConnectRepository()
            fakeRepository = repo
            listOf(
                object : McpIntegration {
                    override val id = HEALTH_ID
                    override val displayName = "Health Connect"
                    override val description = "Test Health integration"
                    override val path = "/health"
                    override val provider: McpServerProvider = HealthConnectMcpServer(repo)
                    override val onboardingRoute = "setup"
                    override val settingsRoute = "settings"
                    override suspend fun isAvailable(): Boolean = true
                }
            )
        }
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `list_record_types on HealthConnect round-trips and audits`() = runBlocking {
        harness.provisionDevice()
        harness.enableIntegrations(listOf(HEALTH_ID))

        // Give the fake repo a non-trivial permission set so the tool's
        // output is deterministic without being empty. `fakeRepository`
        // is populated by [integrationsFactory] on harness start().
        val fake = fakeRepository
            as com.rousecontext.app.integration.harness.HarnessFakeHealthConnectRepository
        fake.grantedPermissions = mutableSetOf("Steps", "HeartRate")

        val mcpSession: McpSession = harness.koin.get()
        mcpSession.resolvePort()
        val tokenStore: TokenStore = harness.koin.get()
        val tokenPair = tokenStore.createTokenPair(
            integrationId = HEALTH_ID,
            clientId = "health-smoke",
            clientName = null
        )

        McpTestDriver(
            session = mcpSession,
            hostHeader = "synth-$HEALTH_ID.example.test",
            bearerToken = tokenPair.accessToken
        ).use { driver ->
            driver.initialize()
            val response = driver.callTool(
                name = "list_record_types",
                argumentsJson = buildJsonObject {
                    put("include_granted_only", JsonPrimitive(false))
                }.toString()
            )
            assertTrue(
                "list_record_types must name at least one known record type " +
                    "in the response JSON: $response",
                response.contains("Steps") || response.contains("HeartRate")
            )
        }

        val auditDao: AuditDao = harness.koin.get()
        assertEquals("health smoke call must audit exactly one row", 1, auditDao.count())
        val entry = auditDao.getById(auditDao.latestId()!!)!!
        assertEquals("list_record_types", entry.toolName)
    }

    private companion object {
        private const val HEALTH_ID = "health"
    }
}
