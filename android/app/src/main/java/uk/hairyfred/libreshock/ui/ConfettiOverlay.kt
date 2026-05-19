package uk.hairyfred.libreshock.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

/**
 *  Full-screen confetti burst overlay. Renders nothing when [parties] is
 *  empty, so callers swap a non-empty list in for the duration of a
 *  burst then clear it back to empty.
 */
@Composable
fun ConfettiOverlay(parties: List<Party>) {
    if (parties.isEmpty()) return
    KonfettiView(
        modifier = Modifier.fillMaxSize(),
        parties = parties,
    )
}

/**
 *  Single radial-upward burst centered on a screen-relative origin
 *  (0..1, 0..1) — (0.5, 0.5) is the middle of the screen. Used after
 *  a successful alarm dismissal, with the origin set to roughly where
 *  the user's action came from (Stop button vs middle for QR scan).
 */
fun celebrationParties(relX: Float, relY: Float): List<Party> {
    val palette = listOf(
        Color(0xFFFF5722).toArgb(),
        Color(0xFFFFC107).toArgb(),
        Color(0xFF4CAF50).toArgb(),
        Color(0xFF03A9F4).toArgb(),
        Color(0xFFE91E63).toArgb(),
    )
    return listOf(
        Party(
            angle = 270,           // upward
            spread = 110,          // 110-degree fan
            speed = 30f,
            maxSpeed = 55f,
            damping = 0.9f,
            colors = palette,
            // Short, single popper-style burst of ~60 pieces.
            emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(60),
            position = Position.Relative(relX.toDouble(), relY.toDouble()),
        ),
    )
}

private fun Color.toArgb(): Int =
    ((alpha * 255).toInt() shl 24) or
        ((red * 255).toInt() shl 16) or
        ((green * 255).toInt() shl 8) or
        (blue * 255).toInt()
