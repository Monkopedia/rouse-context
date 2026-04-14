package com.rousecontext.app.support

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.rousecontext.app.BuildConfig

/**
 * Builds a GitHub Issues "new issue" URI pre-populated with enough device and
 * app-state context to make a bug report useful, while deliberately omitting
 * anything privacy-sensitive (audit history, device subdomain, Firebase UID,
 * integration-specific data, tokens).
 *
 * Opening the resulting [Uri] with `Intent(ACTION_VIEW, uri)` launches the
 * browser; if the user is logged into GitHub they see a pre-populated "Submit"
 * form for the `bug_report.md` template.
 *
 * The GitHub prefill API encodes the body into the query string, so we have to
 * respect Android's intent URI length cap (~2 KB). If the assembled URL would
 * exceed [MAX_URI_LEN] the builder falls back to a minimal body that only
 * contains the device-info header.
 */
class BugReportUriBuilder(private val context: Context) {

    fun build(): Uri {
        val body = buildString {
            append("<!-- What happened? -->\n\n\n")
            append("<!-- What did you expect? -->\n\n\n")
            append("<!-- Steps to reproduce -->\n\n\n")
            append(deviceInfoSection())
        }
        val url = assemble(TITLE_PREFIX, body)
        return if (url.length <= MAX_URI_LEN) {
            Uri.parse(url)
        } else {
            Uri.parse(assemble(TITLE_PREFIX, deviceInfoSection()))
        }
    }

    private fun deviceInfoSection(): String = buildString {
        append("---\n")
        append("**Device info** (do not remove — helps triage):\n")
        append("- App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("- Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("- Battery optimization exempt: ${isIgnoringBatteryOptimizations()}\n")
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun assemble(title: String, body: String): String = ISSUE_URL_BASE +
        "?template=bug_report.md" +
        "&title=" + Uri.encode(title) +
        "&body=" + Uri.encode(body)

    companion object {
        const val TITLE_PREFIX: String = "App bug: "
        const val ISSUE_URL_BASE: String =
            "https://github.com/Monkopedia/rouse-context/issues/new"

        /**
         * Soft cap on the total URI length. Android's intent URI limit is
         * approximately 2 KB and browsers also apply caps; 2048 is a
         * conservative value that leaves headroom for URL-encoded expansion.
         */
        const val MAX_URI_LEN: Int = 2048
    }
}
