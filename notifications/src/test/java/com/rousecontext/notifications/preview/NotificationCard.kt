package com.rousecontext.notifications.preview

import android.app.Notification
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ShadeColors(
    val background: Color,
    val card: Color,
    val primary: Color,
    val secondary: Color,
    val divider: Color,
    val action: Color,
    val accent: Color,
    val isDark: Boolean
)

private fun shadeColors(isDark: Boolean, notifColor: Color?): ShadeColors {
    val fallback = if (isDark) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
    return ShadeColors(
        background = if (isDark) Color(0xFF1B1B1F) else Color(0xFFF2F2F7),
        card = if (isDark) Color(0xFF2C2C30) else Color.White,
        primary = if (isDark) Color(0xFFE2E2E6) else Color(0xFF1A1C1E),
        secondary = if (isDark) Color(0xFF8E9099) else Color(0xFF6F7580),
        divider = if (isDark) Color(0xFF3C3C40) else Color(0xFFE0E0E4),
        action = fallback,
        accent = notifColor ?: fallback,
        isDark = isDark
    )
}

/**
 * Test-only composable that renders an Android [Notification] object in a style
 * resembling the system notification shade. Used for Roborazzi screenshot tests.
 */
@Composable
fun NotificationCard(
    notification: Notification,
    appName: String = "Rouse Context",
    isDark: Boolean = false
) {
    val extras = notification.extras
    val title = extras.getString(Notification.EXTRA_TITLE)
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val subText = extras.getString(Notification.EXTRA_SUB_TEXT)
    val actions = notification.actions?.toList() ?: emptyList()
    val notifColor = if (notification.color != 0) {
        Color(notification.color.toLong() or 0xFF000000L)
    } else {
        null
    }
    val colors = shadeColors(isDark, notifColor)

    Box(
        modifier = Modifier
            .width(380.dp)
            .background(colors.background)
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (colors.isDark) 0.dp else 1.dp
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                NotificationHeader(appName, subText, colors)
                Spacer(Modifier.height(8.dp))
                NotificationContent(title, text, colors)
                NotificationActions(actions, colors)
            }
        }
    }
}

@Composable
private fun NotificationHeader(appName: String, subText: String?, colors: ShadeColors) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = appName,
            fontSize = 12.sp,
            color = colors.secondary,
            fontWeight = FontWeight.Medium
        )
        if (subText != null) {
            Text(
                text = " \u00B7 $subText",
                fontSize = 12.sp,
                color = colors.secondary
            )
        }
        Spacer(Modifier.weight(1f))
        Text(text = "now", fontSize = 12.sp, color = colors.secondary)
    }
}

@Composable
private fun NotificationContent(title: String?, text: String?, colors: ShadeColors) {
    if (title != null) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary
        )
        Spacer(Modifier.height(2.dp))
    }
    if (text != null) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = colors.secondary,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun NotificationActions(actions: List<Notification.Action>, colors: ShadeColors) {
    if (actions.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    Spacer(Modifier.height(4.dp))
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        actions.forEach { action ->
            TextButton(onClick = {}) {
                Text(
                    text = action.title.toString().uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.action,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
