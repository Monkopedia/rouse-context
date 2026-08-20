package com.rousecontext.integrations.outreach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.integrations.testing.McpToolTestHarness
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the channel-id normalization that `create_notification_channel` and
 * `delete_notification_channel` apply to their `id` argument (GH #540).
 *
 * Both tools used to hand-roll the "prefix unless already prefixed" rule that
 * [resolveChannelId] already implements. Collapsing them onto the helper is
 * only safe if the two agree on every reachable input, so the edge cases —
 * unprefixed, already-prefixed, empty, case-shifted, unknown — are asserted
 * here rather than left to inspection.
 */
@RunWith(RobolectricTestRunner::class)
class OutreachChannelIdResolutionTest {

    private lateinit var context: Context
    private lateinit var harness: McpToolTestHarness
    private lateinit var nm: NotificationManager

    private val fakeConnection: ClientConnection = mockk(relaxed = true)

    private val prefix = OutreachMcpProvider.AI_CHANNEL_PREFIX

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        harness = McpToolTestHarness()
        OutreachMcpProvider(context, dndEnabled = false).register(harness.createMockServer())
    }

    private fun createChannel(id: String): CallToolResult = runBlocking {
        harness.callTool(
            name = "create_notification_channel",
            arguments = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("name", JsonPrimitive("Channel $id"))
            },
            connection = fakeConnection
        )
    }

    private fun deleteChannel(id: String): CallToolResult = runBlocking {
        harness.callTool(
            name = "delete_notification_channel",
            arguments = buildJsonObject { put("id", JsonPrimitive(id)) },
            connection = fakeConnection
        )
    }

    private fun bodyOf(result: CallToolResult): String =
        (result.content.first() as TextContent).text!!

    /**
     * Register a channel under a literal id, bypassing the create tool, so the
     * delete assertions cannot pass vacuously when create and delete resolve an
     * id the same wrong way.
     */
    private fun seedChannel(literalId: String) {
        nm.createNotificationChannel(
            NotificationChannel(
                literalId,
                "Seeded $literalId",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    // ---- create_notification_channel ----

    @Test
    fun `create prefixes an unprefixed id`() {
        val result = createChannel("alerts")
        assertFalse(result.isError == true)
        assertNotNull(
            "Channel should exist under the prefixed id",
            nm.getNotificationChannel("${prefix}alerts")
        )
        assertNull("Raw id must not be used as-is", nm.getNotificationChannel("alerts"))
        assertTrue(bodyOf(result).contains("\"id\":\"${prefix}alerts\""))
    }

    @Test
    fun `create leaves an already-prefixed id alone`() {
        val result = createChannel("${prefix}alerts")
        assertFalse(result.isError == true)
        assertNotNull(nm.getNotificationChannel("${prefix}alerts"))
        assertNull(
            "Prefix must not be applied twice",
            nm.getNotificationChannel("$prefix${prefix}alerts")
        )
        assertTrue(bodyOf(result).contains("\"id\":\"${prefix}alerts\""))
    }

    @Test
    fun `create with an empty id yields the bare prefix`() {
        val result = createChannel("")
        assertFalse(result.isError == true)
        assertNotNull(nm.getNotificationChannel(prefix))
        assertTrue(bodyOf(result).contains("\"id\":\"$prefix\""))
    }

    @Test
    fun `create prefix match is case-sensitive`() {
        val shouted = prefix.uppercase() + "alerts"
        val result = createChannel(shouted)
        assertFalse(result.isError == true)
        assertNotNull(
            "An upper-case prefix does not count as prefixed",
            nm.getNotificationChannel("$prefix$shouted")
        )
        assertNull(nm.getNotificationChannel(shouted))
    }

    // ---- delete_notification_channel ----

    @Test
    fun `delete prefixes an unprefixed id`() {
        seedChannel("${prefix}alerts")
        val result = deleteChannel("alerts")
        assertFalse(bodyOf(result), result.isError == true)
        assertNull(nm.getNotificationChannel("${prefix}alerts"))
    }

    @Test
    fun `delete accepts an already-prefixed id`() {
        seedChannel("${prefix}alerts")
        val result = deleteChannel("${prefix}alerts")
        assertFalse(bodyOf(result), result.isError == true)
        assertNull(
            "Prefix must not be applied twice on the delete path",
            nm.getNotificationChannel("${prefix}alerts")
        )
    }

    @Test
    fun `delete of an unknown channel reports the resolved id`() {
        val result = deleteChannel("ghost")
        assertTrue(result.isError == true)
        assertTrue(
            "Error should name the resolved id, not the raw one",
            bodyOf(result).contains("Channel not found: ${prefix}ghost")
        )
    }

    @Test
    fun `delete with an empty id resolves to the bare prefix`() {
        val result = deleteChannel("")
        assertTrue(result.isError == true)
        assertTrue(bodyOf(result).contains("Channel not found: $prefix"))
    }

    @Test
    fun `delete prefix match is case-sensitive`() {
        val shouted = prefix.uppercase() + "alerts"
        // Seeded where a case-SENSITIVE resolver looks; a case-insensitive one
        // would leave the id untouched and report "not found".
        seedChannel("$prefix$shouted")
        val result = deleteChannel(shouted)
        assertFalse(bodyOf(result), result.isError == true)
        assertNull(nm.getNotificationChannel("$prefix$shouted"))
    }

    @Test
    fun `delete does not touch the provider's own outreach channel`() {
        val result = deleteChannel(OutreachMcpProvider.CHANNEL_ID)
        assertTrue(result.isError == true)
        assertTrue(
            bodyOf(result).contains("Channel not found: $prefix${OutreachMcpProvider.CHANNEL_ID}")
        )
        assertNotNull(
            "The provider's own channel must survive",
            nm.getNotificationChannel(OutreachMcpProvider.CHANNEL_ID)
        )
    }

    // ---- resolveChannelId itself ----

    @Test
    fun `resolveChannelId agrees with the tools on every non-null input`() {
        assertEquals("${prefix}alerts", resolveChannelId("alerts"))
        assertEquals("${prefix}alerts", resolveChannelId("${prefix}alerts"))
        assertEquals(prefix, resolveChannelId(""))
        assertEquals(
            "$prefix${prefix.uppercase()}alerts",
            resolveChannelId(prefix.uppercase() + "alerts")
        )
    }

    @Test
    fun `resolveChannelId maps null to the provider channel`() {
        // The one input on which the helper differs from the inline copies it
        // replaces — and one the required `id` param cannot produce.
        assertEquals(OutreachMcpProvider.CHANNEL_ID, resolveChannelId(null))
    }
}
