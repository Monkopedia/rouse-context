package com.rousecontext.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that every `<service>` declared in the merged app manifest resolves
 * to a real class on the classpath.
 *
 * Regression guard for #141: a stale service declaration in
 * `notifications/AndroidManifest.xml` pointed at a class that did not exist.
 * Android silently lists unresolvable services in "Notification Access" and
 * can fail to bind them, producing empty notification history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class ManifestServicesTest {

    @Test
    fun `all declared services resolve to real classes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
        // Not `?: return` — a null here means the merged manifest was not
        // visible, and returning would report PASS having asserted nothing.
        // The failure this test guards is itself silent (Android lists
        // unresolvable services and fails to bind them, producing empty
        // notification history), so a vacuous pass removes the only signal.
        val services = requireNotNull(info.services) {
            "no <service> entries visible — merged manifest missing or " +
                "GET_SERVICES returned nothing; this test cannot verify anything"
        }

        val unresolved = services.mapNotNull { svc ->
            val className = svc.name
            try {
                Class.forName(className)
                null
            } catch (_: ClassNotFoundException) {
                className
            }
        }

        if (unresolved.isNotEmpty()) {
            fail(
                "Merged manifest declares <service> entries with no matching class:\n" +
                    unresolved.joinToString("\n") { "  - $it" }
            )
        }
    }

    @Test
    fun `notification capture service is present and resolvable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(
            context,
            "com.rousecontext.integrations.notifications.NotificationCaptureService"
        )
        val info = context.packageManager.getServiceInfo(component, 0)
        assertNotNull(info)
    }
}
