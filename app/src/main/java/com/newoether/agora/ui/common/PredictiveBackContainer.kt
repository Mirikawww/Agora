package com.newoether.agora.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException

/**
 * Wraps full-screen overlay content with a predictive-back gesture animation.
 * As the user swipes back, the content scales down, slides toward the swipe
 * edge and fades; releasing past the threshold invokes [onBack].
 */
@Composable
fun PredictiveBackContainer(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event: BackEventCompat ->
                progress.snapTo(event.progress)
            }
            progress.snapTo(0f)
            onBack()
        } catch (_: CancellationException) {
            progress.animateTo(0f)
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val p = progress.value.coerceIn(0f, 1f)
                scaleX = 1f - 0.08f * p
                scaleY = 1f - 0.08f * p
                translationX = size.width * 0.18f * p
                alpha = 1f - 0.25f * p
            }
    ) {
        content()
    }
}
