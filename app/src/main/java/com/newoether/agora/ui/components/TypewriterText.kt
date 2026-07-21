package com.newoether.agora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    typeSpeedMs: Int = 100,
    deleteSpeedMs: Int = 50,
    pauseAfterTypeMs: Int = 2000,
    pauseAfterDeleteMs: Int = 600
) {
    TypewriterTexts(
        texts = listOf(text),
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = color,
        typeSpeedMs = typeSpeedMs,
        deleteSpeedMs = deleteSpeedMs,
        pauseAfterTypeMs = pauseAfterTypeMs,
        pauseAfterDeleteMs = pauseAfterDeleteMs,
        mode = "sequential",
    )
}

/**
 * Cycles through [texts] with typewriter animation.
 *
 * - sequential: 0..n-1, then reshuffle only when mode is random
 * - random: shuffle once per full cycle; after finishing the cycle, reshuffle again
 */
@Composable
fun TypewriterTexts(
    texts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    typeSpeedMs: Int = 100,
    deleteSpeedMs: Int = 50,
    pauseAfterTypeMs: Int = 2000,
    pauseAfterDeleteMs: Int = 600,
    mode: String = "random",
) {
    val source = remember(texts) {
        texts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
    }
    var visibleCount by remember { mutableIntStateOf(0) }
    var activeText by remember(source) { mutableIntStateOf(0) }
    // hold current cycle order as indices into source
    val cycle = remember(source, mode) {
        mutableListOf<Int>().apply {
            addAll(source.indices)
            if (mode == "random" && size > 1) shuffle()
        }
    }

    LaunchedEffect(source, mode) {
        var pointer = 0
        while (true) {
            if (cycle.isEmpty()) {
                delay(pauseAfterDeleteMs.toLong())
                continue
            }
            if (pointer >= cycle.size) {
                // full cycle finished
                if (mode == "random" && cycle.size > 1) {
                    // reshuffle for the next cycle
                    var reshuffled: List<Int>
                    do {
                        reshuffled = cycle.shuffled(Random)
                    } while (reshuffled == cycle && cycle.size > 1)
                    cycle.clear()
                    cycle.addAll(reshuffled)
                }
                pointer = 0
            }
            val idx = cycle[pointer]
            activeText = idx
            val text = source[idx]
            for (i in 1..text.length) {
                visibleCount = i
                delay(typeSpeedMs.toLong())
            }
            delay(pauseAfterTypeMs.toLong())
            for (i in text.length - 1 downTo 0) {
                visibleCount = i
                delay(deleteSpeedMs.toLong())
            }
            delay(pauseAfterDeleteMs.toLong())
            pointer++
        }
    }

    val current = source.getOrElse(activeText) { source.first() }
    val cursorAlpha by rememberInfiniteTransition(label = "typewriterCursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(530, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "typewriterCursorAlpha"
    )

    Row(modifier = modifier) {
        Text(
            text = current.take(visibleCount),
            style = style,
            fontWeight = fontWeight,
            color = color
        )
        Text(
            text = "|",
            style = style,
            fontWeight = fontWeight,
            color = color.copy(alpha = cursorAlpha),
            modifier = Modifier.alpha(cursorAlpha)
        )
    }
}
