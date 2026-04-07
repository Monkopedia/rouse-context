package com.rousecontext.mcp.core

import java.io.File
import org.junit.Test

/**
 * Generates static HTML files for each state of the OAuth authorization page,
 * plus a gallery index that iframes them all side-by-side.
 *
 * These are the actual HTML pages served to users in the browser during OAuth,
 * not Compose approximations. Each state variant replaces the polling script with
 * an inline script that immediately renders the final state.
 */
class AuthPageGalleryTest {

    private val outputDir = File(
        System.getProperty("user.dir")
    ).resolve("../../docs/auth-pages").canonicalFile

    private val displayCode = "AB3X-9K2F"

    private fun baseHtml(): String = buildAuthorizePage(
        displayCode = displayCode,
        requestId = "demo-request-id",
        redirectUri = "https://example.com/callback",
        hostname = "main-board.rousecontext.com",
        integration = "health"
    )

    /**
     * Replaces the polling `<script>` block with [replacement] so the page
     * renders in a fixed state without making any network requests.
     */
    private fun replaceScript(html: String, replacement: String): String {
        val scriptStart = html.indexOf("<script>")
        val scriptEnd = html.indexOf("</script>") + "</script>".length
        require(scriptStart >= 0 && scriptEnd > scriptStart) {
            "Could not find <script> block in authorize page HTML"
        }
        return html.substring(0, scriptStart) + replacement + html.substring(scriptEnd)
    }

    private fun waitingHtml(): String {
        // Replace polling script with a no-op so the page stays in its initial
        // "Waiting for approval..." state without firing network requests.
        return replaceScript(baseHtml(), "<script>/* waiting - no polling */</script>")
    }

    private fun approvedHtml(): String {
        val script = """
            <script>
                document.getElementById('spinner').style.display = 'none';
                document.getElementById('status').className = 'success';
                document.getElementById('status').textContent = 'Approved! Redirecting...';
            </script>
        """.trimIndent()
        return replaceScript(baseHtml(), script)
    }

    private fun deniedHtml(): String {
        val script = """
            <script>
                document.getElementById('spinner').style.display = 'none';
                document.getElementById('status').className = 'error';
                document.getElementById('status').textContent = 'Request denied';
            </script>
        """.trimIndent()
        return replaceScript(baseHtml(), script)
    }

    private fun expiredHtml(): String {
        val script = """
            <script>
                document.getElementById('spinner').style.display = 'none';
                document.getElementById('status').className = 'error';
                document.getElementById('status').textContent = 'Request expired';
            </script>
        """.trimIndent()
        return replaceScript(baseHtml(), script)
    }

    @Test
    fun `generate all auth page states`() {
        outputDir.mkdirs()

        val states = mapOf(
            "waiting" to waitingHtml(),
            "approved" to approvedHtml(),
            "denied" to deniedHtml(),
            "expired" to expiredHtml()
        )

        for ((name, html) in states) {
            val file = outputDir.resolve("$name.html")
            file.writeText(html)
            println("Wrote ${file.absolutePath}")
        }

        outputDir.resolve("index.html").writeText(buildGalleryIndex(states.keys.toList()))
        println("Wrote gallery index: ${outputDir.resolve("index.html").absolutePath}")
    }

    @Test
    fun `waiting state contains display code and spinner`() {
        val html = waitingHtml()
        assert(displayCode in html) { "Display code not found in waiting HTML" }
        assert("spinner" in html) { "Spinner element not found in waiting HTML" }
        assert("Waiting for approval" in html) {
            "Waiting text not found in waiting HTML"
        }
    }

    @Test
    fun `approved state shows success message`() {
        val html = approvedHtml()
        assert("Approved! Redirecting..." in html) {
            "Approved message not found"
        }
        assert("success" in html) { "Success class not found" }
    }

    @Test
    fun `denied state shows error message`() {
        val html = deniedHtml()
        assert("Request denied" in html) { "Denied message not found" }
        assert("error" in html) { "Error class not found" }
    }

    @Test
    fun `expired state shows expired message`() {
        val html = expiredHtml()
        assert("Request expired" in html) { "Expired message not found" }
        assert("error" in html) { "Error class not found" }
    }

    @Test
    fun `all states contain Rouse Context branding`() {
        val states = listOf(waitingHtml(), approvedHtml(), deniedHtml(), expiredHtml())
        for (html in states) {
            assert("Rouse Context" in html) { "Branding missing from HTML" }
        }
    }

    @Test
    fun `waiting state does not poll`() {
        val html = waitingHtml()
        assert("fetch(" !in html) {
            "Waiting state HTML should not contain fetch() calls"
        }
        assert("setInterval" !in html) {
            "Waiting state HTML should not contain setInterval"
        }
    }

    @Test
    fun `terminal states do not poll`() {
        for (html in listOf(approvedHtml(), deniedHtml(), expiredHtml())) {
            assert("fetch(" !in html) {
                "Terminal state HTML should not contain fetch() calls"
            }
            assert("setInterval" !in html) {
                "Terminal state HTML should not contain setInterval"
            }
        }
    }

    private fun buildGalleryCards(states: List<String>): String {
        val stateLabels = mapOf(
            "waiting" to "Waiting for Approval",
            "approved" to "Approved",
            "denied" to "Denied",
            "expired" to "Expired"
        )
        return states.joinToString("\n") { state ->
            val label = stateLabels[state] ?: state
            """
            |    <div class="card">
            |      <div class="card-header">$label</div>
            |      <div class="card-body">
            |        <iframe src="$state.html"></iframe>
            |      </div>
            |    </div>
            """.trimMargin()
        }
    }

    private fun buildGalleryIndex(states: List<String>): String {
        val cards = buildGalleryCards(states)
        return """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |<meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<title>OAuth Authorization Page - All States</title>
            |<style>
            |$GALLERY_CSS
            |</style>
            |</head>
            |<body>
            |
            |<h1>OAuth Authorization Page</h1>
            |<p class="subtitle">
            |  Browser-rendered HTML page shown during the OAuth authorization code flow.
            |  Each iframe shows the actual HTML served at GET /{integration}/authorize.
            |</p>
            |
            |<div class="grid">
            |$cards
            |</div>
            |
            |</body>
            |</html>
        """.trimMargin()
    }

    companion object {
        @Suppress("MaxLineLength")
        private val GALLERY_CSS = """
            |  :root {
            |    --bg: #1a1a2e;
            |    --surface: #16213e;
            |    --border: #0f3460;
            |    --text: #e0e0e0;
            |    --text-muted: #a0a0b0;
            |    --accent: #e94560;
            |  }
            |  * { box-sizing: border-box; margin: 0; padding: 0; }
            |  body {
            |    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            |    background: var(--bg);
            |    color: var(--text);
            |    padding: 2rem;
            |    max-width: 1400px;
            |    margin: 0 auto;
            |  }
            |  h1 { font-size: 2rem; margin-bottom: 0.5rem; color: #fff; }
            |  .subtitle { color: var(--text-muted); margin-bottom: 2rem; font-size: 0.95rem; }
            |  .grid {
            |    display: grid;
            |    grid-template-columns: repeat(auto-fill, minmax(460px, 1fr));
            |    gap: 1.5rem;
            |  }
            |  .card {
            |    background: var(--surface);
            |    border: 1px solid var(--border);
            |    border-radius: 12px;
            |    overflow: hidden;
            |  }
            |  .card-header {
            |    padding: 0.75rem 1rem;
            |    font-weight: 600;
            |    font-size: 0.95rem;
            |    border-bottom: 1px solid var(--border);
            |  }
            |  .card-body { padding: 0; }
            |  .card-body iframe { width: 100%; height: 480px; border: none; }
        """.trimMargin()
    }
}
