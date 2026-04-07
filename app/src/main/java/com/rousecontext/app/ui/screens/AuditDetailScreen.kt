package com.rousecontext.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Immutable
data class AuditDetailState(
    val toolName: String = "",
    val provider: String = "",
    val timestampMillis: Long = 0L,
    val durationMs: Long = 0L,
    val argumentsJson: String? = null,
    val resultJson: String? = null,
    val isLoading: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditDetailScreen(state: AuditDetailState = AuditDetailState(), onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Detail") },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Tool name & provider
                DetailSection(label = "Tool") {
                    Text(
                        text = state.toolName,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                DetailSection(label = "Provider") {
                    Text(
                        text = state.provider,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Timestamp
                DetailSection(label = "Timestamp") {
                    Text(
                        text = formatTimestamp(state.timestampMillis),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Duration
                DetailSection(label = "Duration") {
                    Text(
                        text = "${state.durationMs}ms",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Arguments
                DetailSection(label = "Arguments") {
                    CodeBlock(
                        text = formatJsonOrRaw(state.argumentsJson),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Result
                DetailSection(label = "Result") {
                    CodeBlock(
                        text = formatJsonOrRaw(state.resultJson),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, content: @Composable () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    content()
}

@Composable
private fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        )
    }
}

private val DETAIL_TIMESTAMP_FORMAT =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

private fun formatTimestamp(millis: Long): String {
    if (millis == 0L) return "Unknown"
    return DETAIL_TIMESTAMP_FORMAT.format(Date(millis))
}

private fun formatJsonOrRaw(json: String?): String {
    if (json.isNullOrBlank()) return "(none)"
    // Simple pretty-print: indent by inserting newlines after { and , and before }
    return try {
        prettyPrintJson(json)
    } catch (_: Exception) {
        json
    }
}

/**
 * Minimal JSON pretty-printer that avoids pulling in a full JSON library
 * just for display purposes. Handles nested braces and arrays.
 */
private fun prettyPrintJson(json: String): String {
    val sb = StringBuilder()
    var indent = 0
    var inString = false
    var escaped = false
    for (ch in json) {
        when {
            escaped -> {
                sb.append(ch)
                escaped = false
            }
            ch == '\\' && inString -> {
                sb.append(ch)
                escaped = true
            }
            ch == '"' -> {
                inString = !inString
                sb.append(ch)
            }
            inString -> sb.append(ch)
            else -> appendStructural(sb, ch, indent).also { indent = it }
        }
    }
    return sb.toString()
}

private fun appendStructural(sb: StringBuilder, ch: Char, indent: Int): Int {
    var level = indent
    when (ch) {
        '{', '[' -> {
            sb.append(ch)
            level++
            sb.append('\n')
            repeat(level) { sb.append("  ") }
        }
        '}', ']' -> {
            level--
            sb.append('\n')
            repeat(level) { sb.append("  ") }
            sb.append(ch)
        }
        ',' -> {
            sb.append(ch)
            sb.append('\n')
            repeat(level) { sb.append("  ") }
        }
        ':' -> sb.append(": ")
        ' ', '\n', '\r', '\t' -> {} // skip whitespace
        else -> sb.append(ch)
    }
    return level
}

// --- Previews ---

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AuditDetailPreview() {
    RouseContextTheme(darkTheme = true) {
        AuditDetailScreen(
            state = AuditDetailState(
                toolName = "health/get_steps",
                provider = "health",
                timestampMillis = System.currentTimeMillis(),
                durationMs = 142,
                argumentsJson = """{"days":7,"metric":"steps"}""",
                resultJson = """{"total":52340,"average":7477}""",
                isLoading = false
            )
        )
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun AuditDetailLightPreview() {
    RouseContextTheme(darkTheme = false) {
        AuditDetailScreen(
            state = AuditDetailState(
                toolName = "health/get_heart_rate",
                provider = "health",
                timestampMillis = System.currentTimeMillis(),
                durationMs = 201,
                argumentsJson = """{"days":7}""",
                resultJson = null,
                isLoading = false
            )
        )
    }
}
