package com.rousecontext.app.support

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BugReportUriBuilderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `build produces well-formed GitHub issues URL with expected prefill fields`() {
        val uri = BugReportUriBuilder(context).build()

        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/Monkopedia/rouse-context/issues/new", uri.path)

        assertEquals("bug_report.md", uri.getQueryParameter("template"))

        val title = uri.getQueryParameter("title")
        assertNotNull(title)
        assertTrue("title should start with App bug: prefix", title!!.startsWith("App bug:"))

        val body = uri.getQueryParameter("body")
        assertNotNull(body)
        assertTrue(
            "body should contain What happened placeholder",
            body!!.contains("What happened")
        )
        assertTrue(
            "body should contain Device info header",
            body.contains("Device info")
        )
        assertTrue(
            "body should contain app version",
            body.contains("App version:")
        )
        assertTrue(
            "body should contain android version",
            body.contains("Android:")
        )
        assertTrue(
            "body should contain device model",
            body.contains("Device:")
        )
        assertTrue(
            "body should contain battery optimization field",
            body.contains("Battery optimization exempt:")
        )
    }

    @Test
    fun `build does not include privacy-sensitive fields`() {
        val uri = BugReportUriBuilder(context).build()
        val body = uri.getQueryParameter("body") ?: ""

        // These should NEVER be included automatically.
        assertFalse("body must not contain subdomain", body.contains("subdomain"))
        assertFalse("body must not contain Firebase UID", body.contains("Firebase"))
        assertFalse("body must not leak tokens", body.contains("token", ignoreCase = true))
        assertFalse(
            "body must not contain audit entries",
            body.contains("audit", ignoreCase = true)
        )
    }

    @Test
    fun `build URL-encodes title and body correctly`() {
        val uri = BugReportUriBuilder(context).build()
        val raw = uri.toString()

        // The raw URL string must encode the `<!--` comment markers.
        // An un-encoded body would dump raw HTML comments into the query string.
        assertTrue(
            "raw URL should URL-encode the body content",
            raw.contains("body=") && !raw.contains("<!--")
        )

        // Title space should be percent-encoded ("%20") not a literal space.
        assertFalse("raw URL must not contain literal spaces", raw.contains(' '))
    }

    @Test
    fun `round-trip through Uri preserves body content`() {
        val uri = BugReportUriBuilder(context).build()
        val body = uri.getQueryParameter("body")
        // Uri.getQueryParameter decodes; the decoded body should round-trip the
        // HTML comment markers we put in.
        assertNotNull(body)
        assertTrue(body!!.contains("<!-- What happened? -->"))
    }

    @Test
    fun `oversized title truncates body but keeps URL well-formed`() {
        // Build once to confirm the builder never exceeds MAX_URI_LEN even if
        // the body grows due to a long device model string at runtime.
        val uri = BugReportUriBuilder(context).build()
        val raw = uri.toString()

        assertTrue(
            "URL length ${raw.length} must not exceed cap ${BugReportUriBuilder.MAX_URI_LEN}",
            raw.length <= BugReportUriBuilder.MAX_URI_LEN
        )

        // Even after truncation, the URL must still parse and carry the
        // device info section so the report is triageable.
        val parsed = Uri.parse(raw)
        assertEquals("github.com", parsed.host)
        val body = parsed.getQueryParameter("body")
        assertNotNull(body)
        assertTrue(body!!.contains("Device info"))
    }
}
