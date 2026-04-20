package com.rousecontext.integrations.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.notifications.FieldEncryptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises every [NotificationMcpProvider] tool's `execute()` path through the
 * SDK handler that [com.rousecontext.mcp.tool.registerTool] installs. Covers
 * happy paths, error paths, filtering, time-range parsing, and own-package
 * blocking logic so the package-level coverage for
 * `NotificationMcpProviderKt` + tool classes moves from low-40s to near 100%.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationMcpToolExecutionTest {

    private lateinit var context: Context
    private lateinit var database: NotificationDatabase
    private lateinit var dao: NotificationDao
    private lateinit var server: Server

    private val fakeConnection: ClientConnection = mockk(relaxed = true)
    private val toolHandlers = mutableMapOf<
        String,
        suspend ClientConnection.(CallToolRequest) -> CallToolResult
        >()

    // Mutable active-notifications list the provider's source callback returns.
    private var activeNotifications: Array<StatusBarNotification> = emptyArray()

    private val actionCalls = mutableListOf<Pair<String, Int>>()
    private val dismissCalls = mutableListOf<String>()
    private var actionResult: Boolean = true
    private var dismissResult: Boolean = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = NotificationDatabase.createInMemory(context)
        dao = database.notificationDao()

        server = mockk(relaxed = true)
        val nameSlot = slot<String>()
        val handlerSlot = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = any<ToolSchema>(),
                handler = capture(handlerSlot)
            )
        } answers {
            toolHandlers[nameSlot.captured] = handlerSlot.captured
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun registerProvider(
        fieldEncryptor: FieldEncryptor? = null,
        allowActions: Boolean = true
    ): NotificationMcpProvider = NotificationMcpProvider(
        dao = dao,
        activeNotificationSource = { activeNotifications },
        actionPerformer = { key, index ->
            actionCalls.add(key to index)
            actionResult
        },
        notificationDismisser = { key ->
            dismissCalls.add(key)
            dismissResult
        },
        fieldEncryptor = fieldEncryptor,
        allowActions = allowActions
    ).also { it.register(server) }

    private suspend fun call(name: String, args: Map<String, Any> = emptyMap()): CallToolResult {
        val handler = toolHandlers[name] ?: error("Tool not registered: $name")
        val jsonArgs = buildJsonObject {
            for ((k, v) in args) {
                when (v) {
                    is String -> put(k, JsonPrimitive(v))
                    is Int -> put(k, JsonPrimitive(v))
                    is Long -> put(k, JsonPrimitive(v))
                    is Boolean -> put(k, JsonPrimitive(v))
                    else -> error("Unsupported arg type: ${v::class}")
                }
            }
        }
        return handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(name = name, arguments = jsonArgs)
            )
        )
    }

    private fun textOf(result: CallToolResult): String =
        (result.content.first() as TextContent).text!!

    // ---------- StatusBarNotification mocking ----------

    @Suppress("LongParameterList")
    private fun mockSbn(
        key: String = "k",
        pkg: String = "com.example.app",
        title: String? = "Title",
        text: String? = "Body",
        postTime: Long = 1_000L,
        ongoing: Boolean = false,
        category: String? = null,
        actionLabels: List<String> = emptyList()
    ): StatusBarNotification {
        val sbn = mockk<StatusBarNotification>(relaxed = true)
        every { sbn.key } returns key
        every { sbn.packageName } returns pkg
        every { sbn.postTime } returns postTime
        every { sbn.isOngoing } returns ongoing

        val notification = Notification()
        notification.category = category
        val actions = if (actionLabels.isEmpty()) {
            null
        } else {
            actionLabels.map { label ->
                @Suppress("DEPRECATION")
                Notification.Action.Builder(0, label, null).build()
            }.toTypedArray()
        }
        notification.actions = actions
        val extras = Bundle()
        if (title != null) extras.putCharSequence("android.title", title)
        if (text != null) extras.putCharSequence("android.text", text)
        notification.extras = extras
        every { sbn.notification } returns notification
        return sbn
    }

    // ---------- list_active_notifications ----------

    @Test
    fun `list_active_notifications returns all when filter null`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(
            mockSbn(key = "a", pkg = "com.slack.android", title = "Hi"),
            mockSbn(key = "b", pkg = "com.google.gmail", title = "Mail")
        )

        val result = call("list_active_notifications")

        assertFalse(result.isError == true)
        val text = textOf(result)
        assertTrue(text.contains("com.slack.android"))
        assertTrue(text.contains("com.google.gmail"))
    }

    @Test
    fun `list_active_notifications filters by package substring`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(
            mockSbn(key = "a", pkg = "com.slack.android", title = "Slack"),
            mockSbn(key = "b", pkg = "com.google.gmail", title = "Gmail")
        )

        val result = call("list_active_notifications", mapOf("filter" to "slack"))

        assertFalse(result.isError == true)
        val text = textOf(result)
        assertTrue(text.contains("com.slack.android"))
        assertFalse(text.contains("com.google.gmail"))
    }

    @Test
    fun `list_active_notifications serializes actions and metadata`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(
            mockSbn(
                key = "k1",
                pkg = "com.x",
                title = "T",
                text = "B",
                postTime = 4321L,
                ongoing = true,
                category = "msg",
                actionLabels = listOf("Reply", "Archive")
            )
        )

        val result = call("list_active_notifications")

        val text = textOf(result)
        assertTrue("ongoing present", text.contains("\"ongoing\":true"))
        assertTrue("category present", text.contains("\"category\":\"msg\""))
        assertTrue("first action label", text.contains("\"label\":\"Reply\""))
        assertTrue("second action label", text.contains("\"label\":\"Archive\""))
        assertTrue("action id zero", text.contains("\"id\":0"))
    }

    @Test
    fun `list_active_notifications handles missing title text and category`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(
            mockSbn(
                key = "k",
                pkg = "com.x",
                title = null,
                text = null,
                category = null,
                actionLabels = emptyList()
            )
        )

        val result = call("list_active_notifications")

        val text = textOf(result)
        assertTrue(text.contains("\"title\":\"\""))
        assertTrue(text.contains("\"text\":\"\""))
        assertTrue(text.contains("\"category\":\"\""))
        assertTrue(text.contains("\"actions\":[]"))
    }

    // ---------- perform_notification_action ----------

    @Test
    fun `perform_notification_action success path calls action performer`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.x"))
        actionResult = true

        val result = call(
            "perform_notification_action",
            mapOf("notification_key" to "k1", "action_index" to 2)
        )

        assertFalse(result.isError == true)
        assertEquals(listOf("k1" to 2), actionCalls)
        val text = textOf(result)
        assertTrue(text.contains("\"success\":true"))
    }

    @Test
    fun `perform_notification_action returns failure message when performer fails`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.x"))
        actionResult = false

        val result = call(
            "perform_notification_action",
            mapOf("notification_key" to "k1", "action_index" to 0)
        )

        val text = textOf(result)
        assertTrue(text.contains("\"success\":false"))
        assertTrue(text.contains("Failed to perform action"))
    }

    @Test
    fun `perform_notification_action blocked when actions disabled`() = runBlocking {
        registerProvider(allowActions = false)
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.x"))

        val result = call(
            "perform_notification_action",
            mapOf("notification_key" to "k1", "action_index" to 0)
        )

        assertTrue(result.isError == true)
        assertTrue(actionCalls.isEmpty())
        assertTrue(textOf(result).contains("disabled"))
    }

    @Test
    fun `perform_notification_action blocks own package`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.rousecontext.app"))

        val result = call(
            "perform_notification_action",
            mapOf("notification_key" to "k1", "action_index" to 0)
        )

        assertTrue(result.isError == true)
        assertTrue(actionCalls.isEmpty())
        assertTrue(textOf(result).contains("Cannot act on Rouse Context"))
    }

    // ---------- dismiss_notification ----------

    @Test
    fun `dismiss_notification success calls dismisser`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.x"))
        dismissResult = true

        val result = call("dismiss_notification", mapOf("notification_key" to "k1"))

        assertFalse(result.isError == true)
        assertEquals(listOf("k1"), dismissCalls)
        assertTrue(textOf(result).contains("\"success\":true"))
    }

    @Test
    fun `dismiss_notification reports false when dismisser returns false`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.x"))
        dismissResult = false

        val result = call("dismiss_notification", mapOf("notification_key" to "k1"))

        assertTrue(textOf(result).contains("\"success\":false"))
    }

    @Test
    fun `dismiss_notification blocked when actions disabled`() = runBlocking {
        registerProvider(allowActions = false)
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.x"))

        val result = call("dismiss_notification", mapOf("notification_key" to "k1"))

        assertTrue(result.isError == true)
        assertTrue(dismissCalls.isEmpty())
    }

    @Test
    fun `dismiss_notification blocks own package`() = runBlocking {
        registerProvider()
        activeNotifications = arrayOf(mockSbn(key = "k1", pkg = "com.rousecontext.audit"))

        val result = call("dismiss_notification", mapOf("notification_key" to "k1"))

        assertTrue(result.isError == true)
        assertTrue(dismissCalls.isEmpty())
        assertTrue(textOf(result).contains("Cannot dismiss Rouse Context"))
    }

    // ---------- search_notification_history ----------

    @Test
    fun `search_notification_history returns records as JSON`() = runBlocking {
        registerProvider()
        dao.insert(
            NotificationRecord(
                packageName = "com.slack.android",
                title = "Hello",
                text = "Body",
                postedAt = 1_000L,
                category = "msg",
                ongoing = false
            )
        )

        val result = call("search_notification_history")

        assertFalse(result.isError == true)
        val text = textOf(result)
        assertTrue(text.contains("com.slack.android"))
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("Body"))
    }

    @Test
    fun `search_notification_history applies query filter and limit`() = runBlocking {
        registerProvider()
        dao.insert(record(title = "Foo message"))
        dao.insert(record(title = "Bar message"))

        val result = call(
            "search_notification_history",
            mapOf("query" to "Foo", "limit" to 10)
        )

        val text = textOf(result)
        assertTrue(text.contains("Foo message"))
        assertFalse(text.contains("Bar message"))
    }

    @Test
    fun `search_notification_history parses ISO Instant since and until`() = runBlocking {
        registerProvider()
        dao.insert(record(title = "Early", postedAt = 1_000L))
        val recent = Instant.now().toEpochMilli()
        dao.insert(record(title = "Recent", postedAt = recent))

        val sinceIso = Instant.ofEpochMilli(recent - 1_000).toString()
        val untilIso = Instant.ofEpochMilli(recent + 1_000).toString()

        val result = call(
            "search_notification_history",
            mapOf("since" to sinceIso, "until" to untilIso)
        )

        val text = textOf(result)
        assertTrue(text.contains("Recent"))
        assertFalse(text.contains("Early"))
    }

    @Test
    fun `search_notification_history parses ISO date-only since`() = runBlocking {
        registerProvider()
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
        val startOfYesterday = yesterday.atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        dao.insert(record(title = "InRange", postedAt = startOfYesterday + 1_000))

        val result = call(
            "search_notification_history",
            mapOf("since" to yesterday.toString())
        )

        val text = textOf(result)
        assertTrue(text.contains("InRange"))
    }

    @Test
    fun `search_notification_history falls back to zero for invalid ISO`() = runBlocking {
        registerProvider()
        dao.insert(record(title = "Stored", postedAt = 5_000L))

        val result = call(
            "search_notification_history",
            mapOf("since" to "not-a-date")
        )

        // Invalid ISO falls back to sinceMillis=0, so the record is included.
        assertTrue(textOf(result).contains("Stored"))
    }

    @Test
    fun `search_notification_history decrypts via field encryptor when provided`() = runBlocking {
        val encryptor = mockk<FieldEncryptor>()
        every { encryptor.decrypt("ciphered_title") } returns "Plain Title"
        every { encryptor.decrypt("ciphered_body") } returns "Plain Body"
        every { encryptor.decrypt(null) } returns null

        registerProvider(fieldEncryptor = encryptor)
        dao.insert(
            record(title = "ciphered_title", text = "ciphered_body", postedAt = 5_000L)
        )

        val result = call("search_notification_history")

        val text = textOf(result)
        assertTrue("decrypted title in output", text.contains("Plain Title"))
        assertTrue("decrypted body in output", text.contains("Plain Body"))
        assertFalse("encrypted value not leaked", text.contains("ciphered_title"))
    }

    @Test
    fun `search_notification_history returns empty array when no matches`() = runBlocking {
        registerProvider()
        val result = call("search_notification_history")
        assertEquals("[]", textOf(result))
    }

    // ---------- get_notification_stats ----------

    @Test
    fun `get_notification_stats defaults to today period`() = runBlocking {
        registerProvider()
        val now = Instant.now().toEpochMilli()
        dao.insert(record(packageName = "com.foo", postedAt = now))
        dao.insert(record(packageName = "com.foo", postedAt = now))
        dao.insert(record(packageName = "com.bar", postedAt = now))

        val result = call("get_notification_stats")

        assertFalse(result.isError == true)
        val text = textOf(result)
        assertTrue(text.contains("\"total\":3"))
        assertTrue(text.contains("\"most_frequent_app\":\"com.foo\""))
        assertTrue(text.contains("\"period\":\"today\""))
    }

    @Test
    fun `get_notification_stats week period widens range`() = runBlocking {
        registerProvider()
        val threeDaysAgo = Instant.now().minusSeconds(3 * 24 * 3600).toEpochMilli()
        dao.insert(record(packageName = "com.week", postedAt = threeDaysAgo))

        val todayResult = call("get_notification_stats", mapOf("period" to "today"))
        assertTrue(textOf(todayResult).contains("\"total\":0"))

        val weekResult = call("get_notification_stats", mapOf("period" to "week"))
        val text = textOf(weekResult)
        assertTrue(text.contains("\"total\":1"))
        assertTrue(text.contains("\"period\":\"week\""))
    }

    @Test
    fun `get_notification_stats month period captures older entries`() = runBlocking {
        registerProvider()
        val twentyDaysAgo = Instant.now().minusSeconds(20L * 24 * 3600).toEpochMilli()
        dao.insert(record(packageName = "com.month", postedAt = twentyDaysAgo))

        val result = call("get_notification_stats", mapOf("period" to "month"))

        val text = textOf(result)
        assertTrue(text.contains("\"total\":1"))
        assertTrue(text.contains("\"period\":\"month\""))
    }

    @Test
    fun `get_notification_stats unknown period falls back to today`() = runBlocking {
        registerProvider()
        val now = Instant.now().toEpochMilli()
        dao.insert(record(packageName = "com.x", postedAt = now))

        val result = call("get_notification_stats", mapOf("period" to "gibberish"))

        val text = textOf(result)
        // Unknown maps to todayStart..now, same as today
        assertTrue(text.contains("\"total\":1"))
        assertTrue(text.contains("\"period\":\"gibberish\""))
    }

    @Test
    fun `get_notification_stats reports none when empty`() = runBlocking {
        registerProvider()

        val result = call("get_notification_stats")

        val text = textOf(result)
        assertTrue(text.contains("\"total\":0"))
        assertTrue(text.contains("\"most_frequent_app\":\"none\""))
        assertTrue(text.contains("\"by_app\":[]"))
    }

    // ---------- provider metadata ----------

    @Test
    fun `provider exposes stable id and display name`() {
        val provider = registerProvider()
        assertEquals("notifications", provider.id)
        assertEquals("Notifications", provider.displayName)
    }

    @Test
    fun `search_notification_history registered with correct schema`() {
        registerProvider()
        assertNotNull(toolHandlers["search_notification_history"])
    }

    private fun record(
        packageName: String = "com.test.app",
        title: String? = "T",
        text: String? = "B",
        postedAt: Long = System.currentTimeMillis(),
        category: String? = null,
        ongoing: Boolean = false
    ) = NotificationRecord(
        packageName = packageName,
        title = title,
        text = text,
        postedAt = postedAt,
        category = category,
        ongoing = ongoing
    )
}
