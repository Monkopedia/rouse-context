package com.rousecontext.integrations.outreach

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Regression tests for [queryInstalledApps] and the `<queries>` manifest
 * declarations that make it work on Android 11+.
 *
 * See GitHub issue #196 and commit 50ef690d.
 */
@RunWith(RobolectricTestRunner::class)
class OutreachQueryInstalledAppsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ---- Test 1: queryInstalledApps functional behavior ----

    @Test
    fun `user app with launcher intent is returned`() {
        installApp(
            packageName = "com.example.myapp",
            label = "My App",
            isSystem = false,
            hasLauncher = true
        )

        val results = queryInstalledApps(context, filter = null)

        assertTrue(
            "User app with launcher should be returned",
            results.any { it.contains("com.example.myapp") }
        )
    }

    @Test
    fun `system app without launcher intent is filtered out`() {
        installApp(
            packageName = "com.android.systemui",
            label = "System UI",
            isSystem = true,
            hasLauncher = false
        )

        val results = queryInstalledApps(context, filter = null)

        assertTrue(
            "System app without launcher should be filtered out",
            results.none { it.contains("com.android.systemui") }
        )
    }

    @Test
    fun `system app with launcher intent is returned`() {
        installApp(
            packageName = "com.android.settings",
            label = "Settings",
            isSystem = true,
            hasLauncher = true
        )

        val results = queryInstalledApps(context, filter = null)

        assertTrue(
            "System app with launcher should be returned",
            results.any { it.contains("com.android.settings") }
        )
    }

    @Test
    fun `updated system app without launcher is returned`() {
        installApp(
            packageName = "com.android.webview",
            label = "WebView",
            isSystem = true,
            isUpdatedSystem = true,
            hasLauncher = false
        )

        val results = queryInstalledApps(context, filter = null)

        assertTrue(
            "Updated system app should be returned even without launcher",
            results.any { it.contains("com.android.webview") }
        )
    }

    @Test
    fun `filter narrows results by app name`() {
        installApp("com.example.alpha", "Alpha App", isSystem = false, hasLauncher = true)
        installApp("com.example.beta", "Beta App", isSystem = false, hasLauncher = true)
        installApp("com.example.gamma", "Gamma App", isSystem = false, hasLauncher = true)

        val results = queryInstalledApps(context, filter = "beta")

        assertEquals("Filter should return only matching app", 1, results.size)
        assertTrue(results[0].contains("com.example.beta"))
    }

    @Test
    fun `filter is case insensitive`() {
        installApp("com.example.app", "My Cool App", isSystem = false, hasLauncher = true)

        val results = queryInstalledApps(context, filter = "cool")

        assertTrue(
            "Case-insensitive filter should match",
            results.any { it.contains("com.example.app") }
        )
    }

    @Test
    fun `filter excludes non-matching apps`() {
        installApp("com.example.app", "My App", isSystem = false, hasLauncher = true)

        val results = queryInstalledApps(context, filter = "zzz-no-match")

        assertTrue("No apps should match the filter", results.isEmpty())
    }

    @Test
    fun `result JSON contains expected fields`() {
        installApp("com.example.json", "JSON App", isSystem = false, hasLauncher = true)

        val results = queryInstalledApps(context, filter = "json")

        assertEquals(1, results.size)
        val entry = results[0]
        assertTrue(
            "Should contain package field",
            entry.contains("\"package\":\"com.example.json\"")
        )
        assertTrue(
            "Should contain name field",
            entry.contains("\"name\":\"JSON App\"")
        )
        assertTrue(
            "Should contain system field",
            entry.contains("\"system\":false")
        )
    }

    @Test
    fun `system flag is true for non-updated system app with launcher`() {
        installApp("com.android.dialer", "Phone", isSystem = true, hasLauncher = true)

        val results = queryInstalledApps(context, filter = "phone")

        assertEquals(1, results.size)
        assertTrue("System flag should be true", results[0].contains("\"system\":true"))
    }

    // ---- Test 2: Manifest <queries> assertion ----

    @Test
    fun `manifest declares MAIN plus LAUNCHER query`() {
        val manifest = readModuleManifest()
        assertManifestQueryIntent(
            manifest,
            action = "android.intent.action.MAIN",
            category = "android.intent.category.LAUNCHER"
        )
    }

    @Test
    fun `manifest declares ACTION_VIEW https query`() {
        val manifest = readModuleManifest()
        assertManifestQueryIntent(
            manifest,
            action = "android.intent.action.VIEW",
            dataScheme = "https"
        )
    }

    @Test
    fun `manifest declares ACTION_VIEW http query`() {
        val manifest = readModuleManifest()
        assertManifestQueryIntent(
            manifest,
            action = "android.intent.action.VIEW",
            dataScheme = "http"
        )
    }

    // ---- Helpers ----

    @Suppress("Deprecation")
    private fun installApp(
        packageName: String,
        label: String,
        isSystem: Boolean,
        hasLauncher: Boolean,
        isUpdatedSystem: Boolean = false
    ) {
        val shadowPm = Shadows.shadowOf(context.packageManager)

        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.nonLocalizedLabel = label
            flags = 0
            if (isSystem) flags = flags or ApplicationInfo.FLAG_SYSTEM
            if (isUpdatedSystem) flags = flags or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }

        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = appInfo
        }
        shadowPm.installPackage(packageInfo)

        if (hasLauncher) {
            // Register a MAIN+LAUNCHER resolve so getLaunchIntentForPackage
            // returns a non-null intent for this package.
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            shadowPm.addResolveInfoForIntent(
                launchIntent,
                android.content.pm.ResolveInfo().apply {
                    activityInfo = android.content.pm.ActivityInfo().apply {
                        this.packageName = packageName
                        this.name = "$packageName.MainActivity"
                        this.applicationInfo = appInfo
                    }
                }
            )
        }
    }

    /**
     * Read the raw module manifest (not the merged one). This mirrors the
     * approach in [RecordTypeRegistryTest] which reads `src/main/AndroidManifest.xml`
     * directly. The `<queries>` declarations live in the integration module's
     * manifest so they merge into the app; asserting on the source guarantees
     * they won't be accidentally deleted.
     */
    private fun readModuleManifest(): String =
        java.io.File("src/main/AndroidManifest.xml").readText()

    /**
     * Assert that the manifest contains a `<queries><intent>` block matching
     * the given action and optional category or data scheme.
     *
     * Parses the XML properly rather than using brittle string matching.
     */
    private fun assertManifestQueryIntent(
        manifestXml: String,
        action: String,
        category: String? = null,
        dataScheme: String? = null
    ) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(manifestXml.reader())

        val androidNs = "http://schemas.android.com/apk/res/android"

        var inQueries = false
        var inIntent = false
        var foundAction = false
        var foundCategory = category == null
        var foundData = dataScheme == null
        var matched = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "queries" -> inQueries = true
                        "intent" -> if (inQueries) {
                            inIntent = true
                            foundAction = false
                            foundCategory = category == null
                            foundData = dataScheme == null
                        }
                        "action" -> if (inIntent) {
                            val name = parser.getAttributeValue(androidNs, "name")
                            if (name == action) foundAction = true
                        }
                        "category" -> if (inIntent) {
                            val name = parser.getAttributeValue(androidNs, "name")
                            if (name == category) foundCategory = true
                        }
                        "data" -> if (inIntent) {
                            val scheme = parser.getAttributeValue(androidNs, "scheme")
                            if (scheme == dataScheme) foundData = true
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "queries" -> inQueries = false
                        "intent" -> {
                            if (inIntent && foundAction && foundCategory && foundData) {
                                matched = true
                            }
                            inIntent = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val desc = buildString {
            append("action=$action")
            if (category != null) append(", category=$category")
            if (dataScheme != null) append(", data scheme=$dataScheme")
        }
        assertTrue(
            "Manifest <queries> should contain <intent> with $desc. " +
                "This is required for package visibility on Android 11+. " +
                "See commit 50ef690d.",
            matched
        )
    }
}
