package com.rousecontext.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.TealPrimary

@Immutable
data class DeviceCodeApprovalState(
    val integrationName: String = "Health Connect",
    val codeLength: Int = 8,
    val enteredCode: String = "",
    val isApproving: Boolean = false
)

@Composable
fun DeviceCodeApprovalScreen(
    state: DeviceCodeApprovalState = DeviceCodeApprovalState(),
    onCodeChanged: (String) -> Unit = {},
    onApprove: () -> Unit = {},
    onDeny: () -> Unit = {}
) {
    var code by remember(state.enteredCode) { mutableStateOf(state.enteredCode) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Approve Connection",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "An AI client wants to access ${state.integrationName}.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Enter the code shown by the client:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Code input boxes
            CodeInputRow(
                code = code,
                length = state.codeLength,
                onCodeChanged = { newCode ->
                    if (newCode.length <= state.codeLength) {
                        code = newCode
                        onCodeChanged(newCode)
                    }
                }
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onApprove,
                enabled = code.length == state.codeLength && !state.isApproving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
            ) {
                Text("Approve", modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Deny", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun CodeInputRow(code: String, length: Int, onCodeChanged: (String) -> Unit) {
    BasicTextField(
        value = code,
        onValueChange = { value ->
            val filtered = value.filter { it.isLetterOrDigit() }.uppercase()
            onCodeChanged(filtered)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        decorationBox = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // First row (4 boxes)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until length / 2) {
                        CodeBox(char = code.getOrNull(i))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Second row (4 boxes)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in length / 2 until length) {
                        CodeBox(char = code.getOrNull(i))
                    }
                }
            }
        }
    )
}

@Composable
private fun CodeBox(char: Char?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .border(
                width = 2.dp,
                color = if (char != null) {
                    TealPrimary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = char?.toString() ?: "",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DeviceCodeApprovalEmptyPreview() {
    RouseContextTheme(darkTheme = true) {
        DeviceCodeApprovalScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DeviceCodeApprovalFilledPreview() {
    RouseContextTheme(darkTheme = true) {
        DeviceCodeApprovalScreen(
            state = DeviceCodeApprovalState(enteredCode = "ABCD1234")
        )
    }
}
