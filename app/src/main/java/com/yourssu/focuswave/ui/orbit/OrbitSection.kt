package com.yourssu.focuswave.ui.orbit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourssu.focuswave.R
import com.yourssu.focuswave.ui.theme.WhiteText85
import com.yourssu.focuswave.util.OrbitUtil
import kotlin.math.PI
import kotlin.math.atan2

@Composable
fun OrbitSection(
    progress: Float,
    pathSeed: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val compact = maxWidth < 420.dp || maxHeight < 300.dp
        val veryCompact = maxHeight < 230.dp
        val clampedProgress = progress.coerceIn(0f, 1f)

        val earthSize = when {
            veryCompact -> 92.dp
            compact -> 122.dp
            else -> 160.dp
        }
        val moonSize = when {
            veryCompact -> 72.dp
            compact -> 72.dp
            else -> 112.dp
        }
        val rocketSize = when {
            veryCompact -> 52.dp
            compact -> 66.dp
            else -> 82.dp
        }
        val markerBoxSize = when {
            veryCompact -> 132.dp
            compact -> 158.dp
            else -> 210.dp
        }

        val markerBoxPx = with(density) { markerBoxSize.toPx() }
        val edgePaddingPx = with(density) { if (compact) 0.dp.toPx() else 8.dp.toPx() }
        val markerCenterInset = markerBoxPx * 0.5f + edgePaddingPx
        val earthCenterX = markerCenterInset.coerceAtMost(width * 0.20f)
        val moonCenterX = (width - markerCenterInset).coerceAtLeast(width * 0.82f)
        val earthCenterY = (height - markerCenterInset).coerceAtLeast(height * 0.80f)
        val moonCenterY = markerCenterInset.coerceAtMost(height * 0.20f)

        val path = remember(width, height, pathSeed, earthCenterX, earthCenterY, moonCenterX, moonCenterY) {
            OrbitUtil.generateJourneyPath(
                pathSeed = pathSeed,
                width = width,
                height = height,
                startX = earthCenterX,
                startY = earthCenterY,
                endX = moonCenterX,
                endY = moonCenterY
            )
        }

        val pathMeasure = remember(path) {
            PathMeasure().apply { setPath(path, false) }
        }

        val currentDistance = pathMeasure.length * clampedProgress
        val position = pathMeasure.getPosition(currentDistance)
        val tangent = pathMeasure.getTangent(currentDistance)
        val angleInDegrees = if (tangent.x != 0f || tangent.y != 0f) {
            atan2(tangent.y, tangent.x) * (180f / PI.toFloat())
        } else {
            -35f
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val traveledPath = Path()
            val remainingPath = Path()

            pathMeasure.getSegment(0f, currentDistance, traveledPath, true)
            pathMeasure.getSegment(currentDistance, pathMeasure.length, remainingPath, true)

            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.30f),
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
            drawPath(
                path = remainingPath,
                color = Color.White.copy(alpha = 0.2f),
                style = Stroke(
                    width = 4.2f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 14f), 0f)
                )
            )
            drawPath(
                path = traveledPath,
                color = Color(0xFF61C7FF).copy(alpha = 0.94f),
                style = Stroke(width = 5.5f, cap = StrokeCap.Round)
            )
            drawPath(
                path = traveledPath,
                color = Color.White.copy(alpha = 0.56f),
                style = Stroke(width = 2.4f, cap = StrokeCap.Round)
            )
        }

        JourneyMarker(
            centerX = earthCenterX,
            centerY = earthCenterY,
            markerSize = markerBoxSize,
            imageSize = earthSize,
            imageResId = R.drawable.earth_low,
            contentDescription = "Earth start point",
            label = "START",
            labelAlignment = Alignment.TopCenter,
            imageAlpha = 0.82f,
            shadowAlpha = 0.80f,
            shadowOffsetXRatio = 0.24f,
            shadowOffsetYRatio = 0.18f
        )

        JourneyMarker(
            centerX = moonCenterX,
            centerY = moonCenterY,
            markerSize = markerBoxSize,
            imageSize = moonSize,
            imageResId = R.drawable.moon_low,
            contentDescription = "Moon goal point",
            label = "GOAL",
            labelAlignment = Alignment.BottomCenter,
            imageAlpha = 0.72f,
            shadowAlpha = 0.76f,
            shadowOffsetXRatio = -0.22f,
            shadowOffsetYRatio = 0.16f
        )

        Image(
            painter = painterResource(id = R.drawable.rocket1),
            contentDescription = "Rocket",
            modifier = Modifier
                .size(rocketSize)
                .graphicsLayer {
                    translationX = position.x - (size.width / 2f)
                    translationY = position.y - (size.height / 2f)
                    rotationZ = if (progress < 0.9f)
                        angleInDegrees + 90f else
                        angleInDegrees + 270f
                }
        )
    }
}

@Composable
private fun JourneyMarker(
    centerX: Float,
    centerY: Float,
    markerSize: Dp,
    imageSize: Dp,
    imageResId: Int,
    contentDescription: String,
    label: String,
    labelAlignment: Alignment,
    imageAlpha: Float,
    shadowAlpha: Float,
    shadowOffsetXRatio: Float,
    shadowOffsetYRatio: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(markerSize)
            .graphicsLayer {
                translationX = centerX - size.width / 2f
                translationY = centerY - size.height / 2f
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = imageResId),
            contentDescription = contentDescription,
            alpha = imageAlpha,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(imageSize)
        )
        Canvas(
            modifier = Modifier
                .size(imageSize)
                .clip(CircleShape)
        ) {
            val shadowCenter = Offset(
                x = size.width * (0.5f + shadowOffsetXRatio),
                y = size.height * (0.5f + shadowOffsetYRatio)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF020617).copy(alpha = shadowAlpha * 0.96f),
                        Color(0xFF11103A).copy(alpha = shadowAlpha * 0.78f),
                        Color(0xFF2563EB).copy(alpha = shadowAlpha * 0.36f),
                        Color(0xFF7DD3FC).copy(alpha = shadowAlpha * 0.12f),
                        Color.Transparent
                    ),
                    center = shadowCenter,
                    radius = size.minDimension * 0.495f
                ),
                radius = size.minDimension * 0.495f,
                center = shadowCenter
            )

        }
        Text(
            text = label,
            modifier = Modifier
                .align(labelAlignment)
                .background(Color(0xCC061425), RoundedCornerShape(999.dp))
                .border(BorderStroke(1.dp, Color(0xFF66C7FF).copy(alpha = 0.26f)), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = WhiteText85,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
