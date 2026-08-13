package com.yourssu.focuswave.util

import androidx.compose.ui.graphics.Path
import com.yourssu.focuswave.ui.state.TimerPhase
import kotlin.random.Random

object OrbitUtil {
    fun generateJourneyPath(
        pathSeed: Int,
        width: Float,
        height: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Path {
        return Path().apply {
            val random = Random(pathSeed)
            moveTo(startX, startY)

            val c1x = random.nextFloat() * width * 0.5f
            val c1y = random.nextFloat() * height
            val c2x = width * 0.5f + random.nextFloat() * width * 0.5f
            val c2y = random.nextFloat() * height

            cubicTo(c1x, c1y, c2x, c2y, endX, endY)
        }
    }

    fun getStateByProgress(
        progress: Float,
        phase: TimerPhase,
        isRunning: Boolean
    ): String {
        return when (phase) {
            TimerPhase.READY -> "Spaceship standing by"
            TimerPhase.PAUSED -> "Mission paused"
            TimerPhase.FINISHED -> "Goal arrival complete"
            TimerPhase.FOCUS -> when {
                !isRunning -> "Mission paused"
                progress < 0.2f -> "Leaving Earth orbit"
                progress < 0.8f -> "Flying toward the Moon"
                progress < 0.9f -> "preparing for landing"
                progress < 1f -> "Final descent"
                else -> "ERROR"
            }
        }
    }
}