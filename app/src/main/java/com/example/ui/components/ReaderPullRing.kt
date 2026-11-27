package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The pull-weight ring: a bare circular indicator that fills as the reader pulls
 * toward the next or previous chapter, and spins once the pull commits.
 *
 * Ported from the Arcane Codex vertical-mode pull (PullWeightOverlay): no card, no
 * border, no text — the ring itself is the whole affordance.
 */
@Composable
fun ReaderPullRing(
    direction: Int,          // -1 previous, +1 next, 0 none
    progress: Float,         // 0..1.3
    committing: Boolean,
    hasTarget: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    if (direction == 0 || progress <= 0.03f) return

    val clamped = progress.coerceIn(0f, 1f)
    val ready = progress >= 1f && hasTarget
    val isPrev = direction < 0

    // Same timing as the source: 200 ms on commit, otherwise follow the finger.
    val scale by animateFloatAsState(
        targetValue = if (committing) 1.05f else if (ready) 1.08f else 0.8f + clamped * 0.25f,
        animationSpec = tween(if (committing) 200 else 80),
        label = "pullScale"
    )
    val spin by animateFloatAsState(
        targetValue = if (committing) 300f else 0f,
        animationSpec = tween(200),
        label = "pullSpin"
    )

    // The ring lives in its own band at the edge of the screen. It used to slide in
    // over the chapter title, which is the first thing on the page.
    val bandDp = if (isPrev) 96f else 88f
    val drift = (clamped * 10f).coerceAtMost(12f)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = if (isPrev) Alignment.TopCenter else Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .offset(y = if (isPrev) (bandDp - drift).dp else (drift - bandDp).dp)
                .size(40.dp)
                .testTag("reader_pull_ring")
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                val radius = arcSize.width / 2f
                val full = 2f * Math.PI.toFloat() * radius
                // Track
                drawCircle(
                    color = accent.copy(alpha = 0.22f),
                    radius = radius,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = stroke)
                )
                // Filled arc: grows with the pull, opens up on commit.
                val sweepFraction = if (committing) 0.72f else (clamped * 0.88f)
                rotate(degrees = -90f + (if (committing) spin else clamped * 160f)) {
                    drawArc(
                        color = accent.copy(alpha = (clamped * 1.4f).coerceAtMost(1f)),
                        startAngle = 0f,
                        sweepAngle = 360f * sweepFraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                if (full <= 0f) Unit
            }
        }
    }
}
