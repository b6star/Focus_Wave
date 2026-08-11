package com.yourssu.focuswave.ui.orbit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourssu.focuswave.R
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
            veryCompact -> 42.dp
            compact -> 50.dp
            else -> 64.dp
        }
        val moonSize = when {
            veryCompact -> 36.dp
            compact -> 42.dp
            else -> 54.dp
        }
        val rocketSize = when {
            veryCompact -> 48.dp
            compact -> 58.dp
            else -> 74.dp
        }
        val markerBoxSize = when {
            veryCompact -> 96.dp
            compact -> 112.dp
            else -> 136.dp
        }

        val markerBoxPx = with(density) { markerBoxSize.toPx() }
        val edgePaddingPx = with(density) { if (compact) 10.dp.toPx() else 18.dp.toPx() }
        val markerCenterInset = markerBoxPx * 0.5f + edgePaddingPx
        val earthCenterX = markerCenterInset.coerceAtMost(width * 0.34f)
        val moonCenterX = (width - markerCenterInset).coerceAtLeast(width * 0.66f)
        val earthCenterY = (height - markerCenterInset).coerceAtLeast(height * 0.58f)
        val moonCenterY = markerCenterInset.coerceAtMost(height * 0.42f)

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

            drawRoundRect(
                color = Color.Black.copy(alpha = 0.20f),
                cornerRadius = CornerRadius(30f, 30f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = earthSize.toPx() * 2.5f,
                center = Offset(earthCenterX, earthCenterY)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.18f),
                radius = moonSize.toPx() * 2.5f,
                center = Offset(moonCenterX, moonCenterY)
            )
            drawCircle(
                color = Color(0xFF5BE7FF).copy(alpha = 0.22f),
                radius = markerBoxPx * 0.42f,
                center = Offset(earthCenterX, earthCenterY)
            )
            drawCircle(
                color = Color(0xFFFFF3B0).copy(alpha = 0.18f),
                radius = markerBoxPx * 0.36f,
                center = Offset(moonCenterX, moonCenterY)
            )

            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.42f),
                style = Stroke(width = 9f, cap = StrokeCap.Round)
            )
            drawPath(
                path = remainingPath,
                color = Color.White.copy(alpha = 0.34f),
                style = Stroke(
                    width = 5f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
                )
            )
            drawPath(
                path = traveledPath,
                color = Color(0xFF8FEFFF).copy(alpha = 0.92f),
                style = Stroke(width = 6f, cap = StrokeCap.Round)
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
            imageResId = R.drawable.earth,
            contentDescription = "Earth start point",
            label = "START",
            labelAlignment = Alignment.TopCenter
        )

        JourneyMarker(
            centerX = moonCenterX,
            centerY = moonCenterY,
            markerSize = markerBoxSize,
            imageSize = moonSize,
            imageResId = R.drawable.moon,
            contentDescription = "Moon goal point",
            label = "GOAL",
            labelAlignment = Alignment.BottomCenter
        )

        RouteStagePill(
            progress = clampedProgress,
            modifier = Modifier.align(Alignment.TopCenter)
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(Color.Black.copy(alpha = 0.34f), radius = size.minDimension * 0.42f)
            drawCircle(Color.White.copy(alpha = 0.12f), radius = size.minDimension * 0.30f)
        }
        Image(
            painter = painterResource(id = imageResId),
            contentDescription = contentDescription,
            modifier = Modifier.size(imageSize)
        )
        Text(
            text = label,
            modifier = Modifier
                .align(labelAlignment)
                .background(Color.Black.copy(alpha = 0.46f), RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RouteStagePill(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val stage = when {
        progress < 0.2f -> "LAUNCH"
        progress < 0.8f -> "FLIGHT"
        else -> "ARRIVAL"
    }

    Text(
        text = stage,
        modifier = modifier
            .padding(top = 6.dp)
            .background(Color.Black.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White.copy(alpha = 0.86f),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center
    )
}
