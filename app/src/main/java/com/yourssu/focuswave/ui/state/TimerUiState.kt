package com.yourssu.focuswave.ui.state

import com.yourssu.focuswave.MainActivity
import kotlin.random.Random

data class TimerUiState(
    val focusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val breakMinutes: Int = DEFAULT_BREAK_MINUTES,
    val totalSeconds: Int = DEFAULT_FOCUS_MINUTES * SECONDS_PER_MINUTE,
    val remainingSeconds: Int = DEFAULT_FOCUS_MINUTES * SECONDS_PER_MINUTE,
    val phase: TimerPhase = TimerPhase.READY,
    val isRunning: Boolean = false,
    val activePhase: TimerPhase = TimerPhase.FOCUS,
    val soundMixer: SoundMixerUiState = SoundMixerUiState(),
    val pathSeed: Int = Random.nextInt()
) {
    val formattedTime: String
        get() {
            val minutes = remainingSeconds / SECONDS_PER_MINUTE
            val seconds = remainingSeconds % SECONDS_PER_MINUTE
            return "%02d:%02d".format(minutes, seconds)
        }

    val progress: Float
        get() {
            if (totalSeconds <= 0) return 0f
            val elapsedSeconds = totalSeconds - remainingSeconds
            return (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
        }

    val totalFormattedTime: String
        get() {
            val minutes = totalSeconds / SECONDS_PER_MINUTE
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            return "%02d:%02d".format(minutes, seconds)
        }

    val statusText: String
        get() = when (phase) {
            TimerPhase.READY -> "READY"
            TimerPhase.FOCUS -> "FOCUS"
            TimerPhase.PAUSED -> "PAUSED"
            TimerPhase.FINISHED -> "FINISHED"
        }

    val canEditDurations: Boolean
        get() = !isRunning

    val showBreakCountdown: Boolean
        get() = remainingSeconds in 1..5

    val breakCountdownNumber: Int
        get() = if (showBreakCountdown) remainingSeconds else 0
}

enum class TimerPhase {
    READY,
    FOCUS,
    PAUSED,
    FINISHED
}

const val DEFAULT_FOCUS_MINUTES = 25
const val DEFAULT_BREAK_MINUTES = 5
const val SECONDS_PER_MINUTE = 60
