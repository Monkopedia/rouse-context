package com.rousecontext.app.receivers

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.mcp.core.AuthorizationCodeManager
import com.rousecontext.mcp.core.McpSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthApprovalReceiverTest {

    private val authManager = mockk<AuthorizationCodeManager>(relaxed = true)
    private val mcpSession = mockk<McpSession> {
        every { authorizationCodeManager } returns authManager
    }
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        startKoin {
            modules(
                module {
                    single { mcpSession }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `approve action delegates to authorizationCodeManager approve`() {
        val receiver = AuthApprovalReceiver()
        val intent = Intent(context, AuthApprovalReceiver::class.java).apply {
            action = AuthApprovalReceiver.ACTION_APPROVE
            putExtra(AuthApprovalReceiver.EXTRA_DISPLAY_CODE, "ABC-123")
            putExtra(AuthApprovalReceiver.EXTRA_NOTIFICATION_ID, 5000)
        }

        receiver.onReceive(context, intent)

        verify { authManager.approve("ABC-123") }
    }

    @Test
    fun `deny action delegates to authorizationCodeManager deny`() {
        val receiver = AuthApprovalReceiver()
        val intent = Intent(context, AuthApprovalReceiver::class.java).apply {
            action = AuthApprovalReceiver.ACTION_DENY
            putExtra(AuthApprovalReceiver.EXTRA_DISPLAY_CODE, "XYZ-789")
            putExtra(AuthApprovalReceiver.EXTRA_NOTIFICATION_ID, 5001)
        }

        receiver.onReceive(context, intent)

        verify { authManager.deny("XYZ-789") }
    }

    @Test
    fun `receiver dismisses notification after handling`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = Shadows.shadowOf(manager)

        // Post a dummy notification first
        val notificationId = 5002
        val notification = android.app.Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_delete)
            .build()
        manager.notify(notificationId, notification)

        val receiver = AuthApprovalReceiver()
        val intent = Intent(context, AuthApprovalReceiver::class.java).apply {
            action = AuthApprovalReceiver.ACTION_APPROVE
            putExtra(AuthApprovalReceiver.EXTRA_DISPLAY_CODE, "TEST")
            putExtra(AuthApprovalReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }

        receiver.onReceive(context, intent)

        // Verify approve was called and notification dismissed
        verify { authManager.approve("TEST") }
        assertEquals(null, shadow.getNotification(notificationId))
    }

    @Test
    fun `missing display code causes early return without crash`() {
        val receiver = AuthApprovalReceiver()
        val intent = Intent(context, AuthApprovalReceiver::class.java).apply {
            action = AuthApprovalReceiver.ACTION_APPROVE
            // No EXTRA_DISPLAY_CODE
        }

        // Should not throw
        receiver.onReceive(context, intent)

        verify(exactly = 0) { authManager.approve(any()) }
        verify(exactly = 0) { authManager.deny(any()) }
    }
}
