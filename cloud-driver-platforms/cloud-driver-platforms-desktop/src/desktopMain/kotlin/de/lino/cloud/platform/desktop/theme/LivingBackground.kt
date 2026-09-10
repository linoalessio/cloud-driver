package de.lino.cloud.platform.desktop.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Shared "tech / living / 3D" visual primitives that carry the homepage's identity
 * (`homepage/style.css`/`homepage/script.js`) into this Compose Desktop client - a pointer-driven
 * 3D card tilt, a drifting dot-constellation background, a breathing radial glow, a mutating hex
 * ciphertext strip, and an upload "pipeline" stage visualization. Each is a direct port of the
 * matching homepage effect rather than a new one invented for this client, so the two surfaces
 * read as one product. No extra dependency is pulled in for any of this - `compose.foundation`/
 * `compose.ui` already provide `Canvas`, `graphicsLayer`, and the animation APIs used below.
 */

/**
 * Ports the homepage cipher card's pointer-driven 3D tilt (`script.js`'s `pointermove` handler:
 * `rotateY(px*7deg) rotateX(-py*6deg)`) to a [graphicsLayer] rotation driven by cursor position
 * within the modified composable's own bounds. Tracks quickly while the pointer moves (mirroring
 * the homepage's `transition: transform 0.08s linear`) and eases back to level on
 * [PointerEventType.Exit] (mirroring its `0.45s ease` leave transition).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tiltOnHover(maxDegrees: Float = 7f): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var hovering by remember { mutableStateOf(false) }
    var targetRotationX by remember { mutableStateOf(0f) }
    var targetRotationY by remember { mutableStateOf(0f) }

    val settleMillis = if (hovering) 80 else 450
    val animatedRotationX by animateFloatAsState(targetRotationX, tween(settleMillis, easing = LinearOutSlowInEasing))
    val animatedRotationY by animateFloatAsState(targetRotationY, tween(settleMillis, easing = LinearOutSlowInEasing))

    this
        .onSizeChanged { newSize -> size = newSize }
        .onPointerEvent(PointerEventType.Move) { event ->
            hovering = true
            if (size.width > 0 && size.height > 0) {
                val position = event.changes.first().position
                val px = position.x / size.width - 0.5f
                val py = position.y / size.height - 0.5f
                targetRotationY = (px * maxDegrees).coerceIn(-maxDegrees, maxDegrees)
                targetRotationX = (-py * maxDegrees * 0.85f).coerceIn(-maxDegrees, maxDegrees)
            }
        }
        .onPointerEvent(PointerEventType.Exit) {
            hovering = false
            targetRotationX = 0f
            targetRotationY = 0f
        }
        .graphicsLayer {
            rotationX = animatedRotationX
            rotationY = animatedRotationY
            cameraDistance = 20f * this.density
        }
}

/** One drifting point in [ConstellationBackground]'s field - plain mutable state, not Compose [androidx.compose.runtime.State], since every field is only ever touched from the single animation coroutine that owns it. */
private class Particle(var x: Float, var y: Float, val vx: Float, val vy: Float, val radius: Float) {
    /** Advances this particle by one frame, wrapping at [bounds]' edges (mirrors `script.js`'s own wrap-around drift). */
    fun drift(bounds: IntSize) {
        this.x = wrap(this.x + this.vx, bounds.width.toFloat())
        this.y = wrap(this.y + this.vy, bounds.height.toFloat())
    }

    private fun wrap(value: Float, max: Float): Float = when {
        value < 0f -> value + max
        value > max -> value - max
        else -> value
    }

    companion object {
        fun random(bounds: IntSize): Particle = Particle(
            x = Random.nextFloat() * bounds.width,
            y = Random.nextFloat() * bounds.height,
            vx = (Random.nextFloat() - 0.5f) * 0.3f,
            vy = (Random.nextFloat() - 0.5f) * 0.3f,
            radius = Random.nextFloat() * 1.3f + 0.6f,
        )
    }
}

/** Distance under which two [ConstellationBackground] particles draw a connecting line - matches `script.js`'s `LINK_DIST`. */
private const val LINK_DISTANCE = 130f

/**
 * Ports `script.js`'s drifting dot-constellation background (points wandering and wrapping at the
 * edges, with a faint connecting line drawn between any pair closer than [LINK_DISTANCE]) to a
 * [Canvas]. Dot count scales with area exactly as the homepage does (`area/16000`, capped at 90).
 * The drift loop pauses whenever [LocalWindowInfo]'s window isn't focused - Compose Desktop has no
 * direct equivalent of the web's `document.hidden`/`prefers-reduced-motion`, so this is the
 * practical stand-in for costing nothing while the app is backgrounded; kept deliberately used
 * only on the auth screens (this app's one true "hero" moment), never behind everyday file work.
 */
@Composable
fun ConstellationBackground(modifier: Modifier = Modifier, dotColor: Color = MaterialTheme.colorScheme.primary) {
    val focused = LocalWindowInfo.current.isWindowFocused
    var size by remember { mutableStateOf(IntSize.Zero) }
    val particles = remember { mutableListOf<Particle>() }
    var frame by remember { mutableStateOf(0L) }

    LaunchedEffect(size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val target = ((size.width.toLong() * size.height) / 16_000L).toInt().coerceIn(0, 90)
        particles.clear()
        repeat(target) { particles += Particle.random(size) }
    }

    LaunchedEffect(size, focused) {
        if (size.width <= 0 || size.height <= 0 || !focused) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                particles.forEach { particle -> particle.drift(size) }
                frame++
            }
        }
    }

    Canvas(modifier.onSizeChanged { newSize -> size = newSize }) {
        // Unused on purpose - reading it here makes this draw phase re-run every time the drift
        // loop above advances a frame, without the particle list itself being Compose State.
        @Suppress("UNUSED_EXPRESSION") frame

        for (i in particles.indices) {
            for (j in i + 1 until particles.size) {
                val a = particles[i]
                val b = particles[j]
                val distance = hypot(a.x - b.x, a.y - b.y)
                if (distance < LINK_DISTANCE) {
                    drawLine(
                        color = dotColor.copy(alpha = (1f - distance / LINK_DISTANCE) * 0.13f),
                        start = Offset(a.x, a.y),
                        end = Offset(b.x, b.y),
                        strokeWidth = 0.6f,
                    )
                }
            }
        }
        particles.forEach { particle ->
            drawCircle(color = dotColor.copy(alpha = 0.35f), radius = particle.radius, center = Offset(particle.x, particle.y))
        }
    }
}

/**
 * Ports the homepage hero's breathing radial glow (`.hero::before`'s 9s `breathe` keyframes) - a
 * soft [color]-tinted [Brush.radialGradient] that slowly scales and fades on an alternating loop.
 * Meant to sit behind a headline element via a [androidx.compose.foundation.layout.Box] stack, not
 * as a standalone visible shape.
 */
@Composable
fun BreathingGlow(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "breathing-glow")
    val scale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathing-glow-scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathing-glow-alpha",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent))),
    )
}

private const val HEX_CHARS = "0123456789abcdef"

/** How many hex characters live in [CipherHexStrip]'s mutating band - matches the homepage cipher exhibit's rough sample length. */
private const val CIPHER_SAMPLE_LENGTH = 96

/** How many characters [CipherHexStrip] mutates per tick - matches `script.js`'s `mutate()`. */
private const val CIPHER_MUTATIONS_PER_TICK = 5

/**
 * Ports the homepage's `.cipher-hex` band: a monospace strip of hex on [cipherBandColor], with a
 * handful of characters re-randomized every 150ms forever (mirrors `script.js`'s `mutate()`) - the
 * same "this is real ciphertext, quietly changing" flourish, standing in for "your files are
 * encrypted" without claiming to show any real file's actual bytes.
 */
@Composable
fun CipherHexStrip(modifier: Modifier = Modifier) {
    var hex by remember { mutableStateOf(buildString { repeat(CIPHER_SAMPLE_LENGTH) { append(HEX_CHARS.random()) } }) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            val chars = hex.toCharArray()
            repeat(CIPHER_MUTATIONS_PER_TICK) { chars[Random.nextInt(chars.size)] = HEX_CHARS.random() }
            hex = String(chars)
        }
    }

    Text(
        text = hex.chunked(24).joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = CloudDriverMono,
        color = cipherInkColor(),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cipherBandColor())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** The upload pipeline stages shown by [PipelineStages] - mirrors the homepage's `.pipeline` exhibit exactly, stage for stage. */
private val PIPELINE_STAGES = listOf("app", "TLS", "REST API (JWT)", "AES-256-GCM", "ciphertext at rest")

/** Index of the stage [PipelineStages] always highlights as "locked" - mirrors the homepage's `.stage.locked` (AES-256-GCM). */
private const val LOCKED_STAGE_INDEX = 3

/**
 * Ports the homepage's `.pipeline` stage-chip row (`app -> TLS -> REST API (JWT) -> AES-256-GCM ->
 * ciphertext at rest`) to a real progress visualization: unlike the homepage's decorative
 * CSS-`animation-delay` sweep, [activeFraction] (from [de.lino.cloud.platform.desktop.viewmodel.TransferProgress.fraction],
 * or `null` while idle) drives which stage is actually "lit" - a genuine description of what's
 * happening to an uploaded file, not a loop. The AES-256-GCM stage always reads as "locked"
 * (matches the homepage: every upload really is encrypted under a fresh data key).
 */
@Composable
fun PipelineStages(activeFraction: Float?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PIPELINE_STAGES.forEachIndexed { index, stage ->
            val stageStart = index.toFloat() / PIPELINE_STAGES.size
            val stageEnd = (index + 1).toFloat() / PIPELINE_STAGES.size
            val lit = activeFraction != null && activeFraction in stageStart..stageEnd
            val locked = index == LOCKED_STAGE_INDEX
            val accented = lit || locked

            Text(
                stage,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = CloudDriverMono,
                color = if (accented) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, if (accented) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(7.dp))
                    .background(if (lit) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (index != PIPELINE_STAGES.lastIndex) {
                Text(
                    "→",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
