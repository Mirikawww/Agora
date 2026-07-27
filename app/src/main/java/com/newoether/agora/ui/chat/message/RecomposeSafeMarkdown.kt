package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex

/**
 * Double-buffered crossfade Markdown composable.
 * Prevents visual "flash" from AST re-parsing during streaming by maintaining
 * two content buffers and crossfading between them.
 *
 * The fade length is the streaming frame rate. A swap can only start once the previous one has
 * finished (see the `!fading` guard below), so each fade plus its two priming frames is the floor
 * on how often text can visibly change — a single 180ms setting capped it near 4.7 updates/s
 * regardless of how fast tokens arrived, which reads as stuttering rather than streaming. Mid-
 * stream swaps therefore use a short fade, and only the final settle after the stream ends keeps
 * the longer, smoother one. No content is dropped either way: the state effect below re-runs when
 * `fading` clears and picks up whatever the latest value is by then.
 */
private const val STREAMING_FADE_NS = 50_000_000L  // ~50ms → roughly 12 visible updates/s
private const val SETTLED_FADE_NS = 180_000_000L   // final swap only; smoothness over rate
@Composable
internal fun RecomposeSafeMarkdown(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    render: @Composable (text: String) -> Unit
) {
    var buf0 by remember { mutableStateOf("") }
    var buf1 by remember { mutableStateOf("") }
    var front by remember { mutableStateOf(0) }
    var fading by remember { mutableStateOf(false) }
    var fadeAlpha by remember { mutableFloatStateOf(0f) }
    var fadeKey by remember { mutableIntStateOf(0) }
    var wasStreaming by remember { mutableStateOf(false) }
    var waitingForFade by remember { mutableStateOf(false) }
    // State machine — driven from an effect so composition stays read-only.
    // Keyed on every input that can trigger a buffer swap / fade transition:
    // a new content value, a streaming↔idle flip, or a fade completing (fading → false).
    LaunchedEffect(content, isStreaming, fading) {
        if (isStreaming) {
            waitingForFade = false
            val cur = if (front == 0) buf0 else buf1
            if (content != cur && !fading) {
                if (front == 0) buf1 = content else buf0 = content
                fadeKey++
                fading = true
                fadeAlpha = 0f
            }
        } else {
            if (wasStreaming) {
                waitingForFade = true
            }
            if (waitingForFade) {
                if (!fading) {
                    if (front == 0) buf1 = content else buf0 = content
                    waitingForFade = false
                    fadeKey++
                    fading = true
                    fadeAlpha = 0f
                }
            }
            if (!waitingForFade && !fading) {
                if (front == 0) {
                    if (buf0 != content) buf0 = content
                    buf1 = ""
                } else {
                    if (buf1 != content) buf1 = content
                    buf0 = ""
                }
            }
        }
        wasStreaming = isStreaming
    }

    // Fade animation — keyed by fadeKey so every fade gets a fresh LaunchedEffect
    LaunchedEffect(fadeKey) {
        if (!fading) return@LaunchedEffect
        withFrameNanos { }
        val startNs = withFrameNanos { it }
        val durationNs = if (isStreaming) STREAMING_FADE_NS else SETTLED_FADE_NS
        while (true) {
            val nowNs = withFrameNanos { it }
            val p = ((nowNs - startNs).toFloat() / durationNs).coerceAtMost(1f)
            fadeAlpha = p
            if (p >= 1f) break
        }
        front = 1 - front
        fading = false
        fadeAlpha = 0f
    }

    // Visibility / z-order: symmetric for both buffers
    val incoming = 1 - front
    val z0 = when { fading && incoming == 0 -> 2f; fading && front == 0 -> 0f; front == 0 -> 2f; else -> 0f }
    val a0 = when { fading && incoming == 0 -> fadeAlpha; fading && front == 0 -> 1f; front == 0 -> 1f; else -> 0f }
    val z1 = when { fading && incoming == 1 -> 2f; fading && front == 1 -> 0f; front == 1 -> 2f; else -> 0f }
    val a1 = when { fading && incoming == 1 -> fadeAlpha; fading && front == 1 -> 1f; front == 1 -> 1f; else -> 0f }

    Box(modifier = modifier) {
        if (buf0.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().zIndex(z0).alpha(a0)) { render(buf0) }
        }
        if (buf1.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().zIndex(z1).alpha(a1)) { render(buf1) }
        }
    }
}
