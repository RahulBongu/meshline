package com.meshline.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The live `⏱ M:SS` label of a disappearing message.
 *
 * Each bubble owns a one-second ticker, so the countdown stays accurate
 * without making the whole message list recompose every second. The ticker
 * stops once the deadline passes; the service sweep removes the row within
 * one maintenance tick.
 */
@Composable
fun DisappearingCountdown(
    expiresAtMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
) {
    var nowMs by remember(expiresAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (System.currentTimeMillis() < expiresAtMs) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
        nowMs = System.currentTimeMillis()
    }

    val remaining = (expiresAtMs - nowMs) / 1_000L
    if (remaining > 0) {
        Text(
            text = "⏱ " + String.format(Locale.US, "%d:%02d", remaining / 60, remaining % 60),
            color = color,
            fontSize = fontSize,
            modifier = modifier,
        )
    }
}
