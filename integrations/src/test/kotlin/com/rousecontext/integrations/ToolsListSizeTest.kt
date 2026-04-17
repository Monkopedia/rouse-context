package com.rousecontext.integrations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.integrations.health.FakeHealthConnectRepository
import com.rousecontext.integrations.health.HealthConnectMcpServer
import com.rousecontext.integrations.notifications.NotificationDao
import com.rousecontext.integrations.notifications.NotificationMcpProvider
import com.rousecontext.integrations.outreach.OutreachMcpProvider
import com.rousecontext.integrations.usage.UsageMcpProvider
import com.rousecontext.mcp.core.McpServerProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Prints `tools/list` byte sizes per integration. Not a correctness assertion —
 * used to measure the effect of description trimming (GH #214).
 */
@RunWith(RobolectricTestRunner::class)
class ToolsListSizeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class Captured(val name: String, val description: String?, val schema: ToolSchema)

    private fun capture(provider: McpServerProvider): List<Captured> {
        val captured = mutableListOf<Captured>()
        val server = mockk<Server>(relaxed = true)
        val n = slot<String>()
        val d = slot<String>()
        val s = slot<ToolSchema>()
        every {
            server.addTool(
                name = capture(n),
                description = capture(d),
                inputSchema = capture(s),
                handler = any()
            )
        } answers {
            captured += Captured(n.captured, d.captured, s.captured)
        }
        provider.register(server)
        return captured
    }

    private fun toJsonArray(tools: List<Captured>): JsonArray = buildJsonArray {
        for (t in tools) {
            add(
                buildJsonObject {
                    put("name", JsonPrimitive(t.name))
                    t.description?.let { put("description", JsonPrimitive(it)) }
                    put("inputSchema", schemaToJson(t.schema))
                }
            )
        }
    }

    private fun schemaToJson(schema: ToolSchema): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        schema.properties?.let { put("properties", it) }
        if (schema.required != null && schema.required!!.isNotEmpty()) {
            put(
                "required",
                buildJsonArray {
                    for (r in schema.required!!) add(JsonPrimitive(r))
                }
            )
        }
    }

    private fun report(label: String, provider: McpServerProvider) {
        val tools = capture(provider)
        val arr = toJsonArray(tools)
        val json = Json.encodeToString(JsonArray.serializer(), arr)
        println("TOOLS_LIST_SIZE integration=$label tools=${tools.size} bytes=${json.length}")
        if (System.getenv("TOOLS_LIST_DUMP") == "1") {
            println("TOOLS_LIST_DUMP integration=$label json=$json")
        }
    }

    @Test
    fun `report sizes`() {
        report("outreach", OutreachMcpProvider(context, dndEnabled = true))
        report("usage", UsageMcpProvider(context))
        report("health", HealthConnectMcpServer(FakeHealthConnectRepository()))
        report(
            "notifications",
            NotificationMcpProvider(
                dao = mockk<NotificationDao>(relaxed = true),
                activeNotificationSource = { emptyArray() },
                actionPerformer = { _, _ -> true },
                notificationDismisser = { true }
            )
        )
    }
}
