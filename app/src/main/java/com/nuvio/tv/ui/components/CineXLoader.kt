package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CineX brand loader / splash animation — a native Compose recreation of
 * `assets/branding/cinex/animations/cinex_animated_logo.svg`: nine columns of dots forming the
 * CineX "lens" mark, each column pulsing vertically (an equaliser) with a staggered phase.
 *
 * Pure Canvas, no Lottie/SVG dependency. Used as the in-app launch splash and as the primary
 * full-screen loading indicator (via [LoadingIndicator]). The geometry (column x, per-column dot y,
 * radii, palette) is copied 1:1 from the SVG viewBox (512x512, centred at 256) and scaled to [size].
 *
 * Mirrors the NuvioMobile CineXLoader exactly so the two apps animate identically.
 */

// Column centre x (SVG units, viewBox 0..512)
private val COL_X = floatArrayOf(
    65.324f, 112.405f, 159.485f, 206.566f, 253.646f, 300.726f, 347.807f, 394.887f, 441.968f,
)

// Per-column dot radius (SVG units)
private val COL_R = floatArrayOf(
    14.124f, 15.301f, 16.478f, 17.655f, 18.832f, 17.655f, 16.478f, 15.301f, 18.832f,
)

// Per-column dot centre y (SVG units), symmetric about 256
private val COL_Y = arrayOf(
    floatArrayOf(204.211f, 256f, 307.789f),
    floatArrayOf(171.255f, 213.628f, 256f, 298.372f, 340.745f),
    floatArrayOf(150.069f, 192.441f, 234.814f, 277.186f, 319.559f, 361.931f),
    floatArrayOf(128.883f, 171.255f, 213.628f, 256f, 298.372f, 340.745f, 383.117f),
    floatArrayOf(107.697f, 150.069f, 192.441f, 234.814f, 277.186f, 319.559f, 361.931f, 404.303f),
    floatArrayOf(128.883f, 171.255f, 213.628f, 256f, 298.372f, 340.745f, 383.117f),
    floatArrayOf(171.255f, 213.628f, 256f, 298.372f, 340.745f),
    floatArrayOf(204.211f, 256f, 307.789f),
    floatArrayOf(256f),
)

private val COL_COLOR = longArrayOf(
    0xFF1E1B4B, 0xFF3730A3, 0xFF4F46E5, 0xFF6366F1, 0xFF818CF8,
    0xFF93C5FD, 0xFFBFDBFE, 0xFFA78BFA, 0xFFC4B5FD,
)

private const val VIEWBOX = 512f
private const val CENTER = 256f
private const val CYCLE_MS = 1400 // matches SVG eq animation duration
private const val STAGGER = 0.1f  // 0.14s / 1.4s per column

@Composable
fun CineXLoader(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val transition = rememberInfiniteTransition(label = "cinexLoader")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cinexPhase",
    )

    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / VIEWBOX
        val cy = CENTER * s
        for (col in COL_X.indices) {
            // eq keyframe: scaleY 1 -> 0.35 -> 1 == 0.675 + 0.325*cos(2*pi*phase)
            var phase = t + STAGGER * col
            phase -= phase.toInt().toFloat()
            val scaleY = 0.675f + 0.325f * cos(2f * PI.toFloat() * phase)
            val cx = COL_X[col] * s
            val r = COL_R[col] * s
            val ry = r * scaleY
            val color = Color(COL_COLOR[col])
            for (dotY in COL_Y[col]) {
                val y = cy + (dotY - CENTER) * scaleY * s
                drawOval(
                    color = color,
                    topLeft = Offset(cx - r, y - ry),
                    size = Size(r * 2f, ry * 2f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CineX launch splash — the lens mark (dots fly in from the edges, then settle
// into an equaliser wave) with the wordmark "CINEX" drawn as LIVE TEXT in the
// Bebas Neue font directly below it (C/I/N/E #E0E7FF, X #A78BFA). There is no
// logo/icon image rendered below the lens — the wordmark is pure text so the two
// apps render identically. Mirrors NuvioMobile CineXSplash exactly.
// ---------------------------------------------------------------------------

private const val SPLASH_BG = 0xFF0A0A12L
private const val SPLASH_DROP_MS = 2000f
private const val SPLASH_LETTER_START_MS = 1300f
private const val SPLASH_LETTER_DUR_MS = 600f

private fun easeOutExpo(t: Float): Float = if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

private fun splashEdgeStart(tx: Float, ty: Float, cx: Float, cy: Float, w: Float, h: Float): Offset {
    val dx = tx - cx
    val dy = ty - cy
    if (abs(dx) < 2f && abs(dy) < 2f) return Offset(cx, -50f)
    val ax = if (dx < 0f) (-80f - tx) / dx else (w + 80f - tx) / dx
    val ay = if (dy < 0f) (-80f - ty) / dy else (h + 80f - ty) / dy
    val sParam = minOf(abs(ax), abs(ay))
    return Offset(tx + dx * sParam, ty + dy * sParam)
}

@Composable
fun CineXSplash(modifier: Modifier = Modifier) {
    var elapsedMs by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var startNs = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNs == 0L) startNs = now
                elapsedMs = (now - startNs) / 1_000_000f
            }
        }
    }
    val bebas = FontFamily(Font(R.font.bebas_neue))
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier.background(Color(SPLASH_BG)), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val minDim = minOf(wPx, hPx)
        val s = minDim / 1132f
        val cx = wPx / 2f
        val cy = hPx / 2f - minDim * 0.04f
        val el = elapsedMs
        val fontSizeSp = with(density) { (minDim * 50f / 480f).toSp() }
        val wordmark = remember(fontSizeSp, bebas) {
            buildAnnotatedString {
                pushStyle(SpanStyle(color = Color(0xFFE0E7FF))); append("CINE"); pop()
                pushStyle(SpanStyle(color = Color(0xFFA78BFA))); append("X"); pop()
            }
        }
        val wordmarkLayout = remember(fontSizeSp, bebas, wordmark) {
            textMeasurer.measure(text = wordmark, style = TextStyle(fontFamily = bebas, fontSize = fontSizeSp))
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val flyT = (el / SPLASH_DROP_MS).coerceIn(0f, 1f)
            val waving = el > SPLASH_DROP_MS
            val wt = ((el - SPLASH_DROP_MS) / 1000f).coerceAtLeast(0f)
            val we = (wt / 0.5f).coerceIn(0f, 1f)
            for (col in COL_X.indices) {
                val sY = if (waving) 1f + sin(wt * 3.5f + col * 0.5f) * 0.38f * we else 1f
                val colColor = Color(COL_COLOR[col])
                val ys = COL_Y[col]
                for (di in ys.indices) {
                    val targetX = cx + (COL_X[col] - CENTER) * s
                    val targetY = cy + (ys[di] - CENTER) * s
                    val radius = COL_R[col] * s
                    val delay = col * 0.05f + di * 0.02f
                    val t = ((flyT - delay * 0.7f) / (1f - delay * 0.4f)).coerceIn(0f, 1f)
                    val p = easeOutExpo(t)
                    val start = splashEdgeStart(targetX, targetY, cx, cy, wPx, hPx)
                    val baseX = start.x + (targetX - start.x) * p
                    val baseYraw = start.y + (targetY - start.y) * p
                    val alpha = (t * 3f).coerceIn(0f, 1f)
                    if (alpha <= 0f) continue
                    val drawY = cy + (baseYraw - cy) * sY
                    val ry = radius * sY
                    if (t > 0.01f && t < 0.98f) {
                        val tl = (1f - p) * s * 80f
                        val sdx = start.x - targetX
                        val sdy = start.y - targetY
                        val len = sqrt(sdx * sdx + sdy * sdy).coerceAtLeast(1f)
                        val nx = sdx / len
                        val ny = sdy / len
                        drawLine(
                            color = colColor.copy(alpha = alpha * 0.35f),
                            start = Offset(baseX + nx * tl, drawY + ny * tl), end = Offset(baseX, drawY),
                            strokeWidth = radius * 1.2f, cap = StrokeCap.Round,
                        )
                    }
                    drawOval(
                        color = colColor.copy(alpha = alpha),
                        topLeft = Offset(baseX - radius, drawY - ry), size = Size(radius * 2f, ry * 2f),
                    )
                }
            }
            val lt = ((el - SPLASH_LETTER_START_MS) / SPLASH_LETTER_DUR_MS).coerceIn(0f, 1f)
            if (lt > 0f) {
                val lp = easeOutCubic(lt)
                val wmAlpha = (lt * 2f).coerceIn(0f, 1f)
                val slidePx = (1f - lp) * (minDim * 90f / 480f)
                val baselineY = cy + minDim * 140f / 480f
                val topLeftX = cx - wordmarkLayout.size.width / 2f
                val topLeftY = baselineY - wordmarkLayout.firstBaseline + slidePx
                drawText(textLayoutResult = wordmarkLayout, topLeft = Offset(topLeftX, topLeftY), alpha = wmAlpha)
            }
        }
    }
}
