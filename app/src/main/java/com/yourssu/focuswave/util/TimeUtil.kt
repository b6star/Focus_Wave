package com.yourssu.focuswave.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtil {
    fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60

        return when {
            h > 0 -> "${h}시간 ${m}분 ${s}초"
            m > 0 -> "${m}분 ${s}초"
            else -> "${s}초"
        }
    }

}

@Composable
fun rememberCurrentTimeText(): String {
    val formatter = remember {
        DateTimeFormatter.ofPattern("h:mm-a", Locale.US)
    }

    var currentTimeText by remember {

        mutableStateOf(LocalTime.now().format(formatter))
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeText =
                LocalTime.now().format(formatter)
            delay(1_000L)
        }
    }
    return currentTimeText
}

@Composable
fun rememberCurrentDateText(): String {
    val formatter = remember {
        DateTimeFormatter.ofPattern("MMM dd · yyyy", Locale.US)
    }

    var currentDateText by remember {
        mutableStateOf(LocalDate.now().format(formatter).uppercase(Locale.US))
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentDateText = LocalDate.now().format(formatter).uppercase(Locale.US)
            delay(60_000L)
        }
    }

    return currentDateText
}

@Composable
fun rememberCurrentTimeStatusText(): String {
    var statusText by remember {
        mutableStateOf(currentTimeStatusText())
    }

    LaunchedEffect(Unit) {
        while (true) {
            statusText = currentTimeStatusText()
            delay(60_000L)
        }
    }

    return statusText
}

private fun currentTimeStatusText() = when (LocalTime.now().hour) {
    in 0..4 -> listOf(
        "Everything's on mute", "Stop texting your ex", "Even the moon is tired",
        "Why are you awake?", "Go to sleep", "Overthinking hour",
        "Error 404: Sleep not found", "Ghosting the world", "Midnight vibes", "Too late for coffee"
    )
    in 5..8 -> listOf(
        "Fresh day loading", "Morning again", "Still sleepy",
        "Need more bed", "Snooze button won", "Who invented mornings?",
        "Sun is too bright", "Starting engine...", "Barely awake", "Where's the caffeine?"
    )
    in 9..11 -> listOf(
        "Brain online", "Productivity is good", "Coffee's working",
        "Let's get it", "On the grind", "Actually focusing",
        "Do not disturb", "Making things happen", "Fully charged", "Keyboard on fire"
    )
    in 12..13 -> listOf(
        "Lunch is calling", "Need food", "Stomach in chat",
        "Hangry mode off", "Time to eat", "Refueling...",
        "Menu scrolling", "Bite-sized break", "Brain needs carbs", "Out to lunch"
    )
    in 14..17 -> listOf(
        "Still productive", "Afternoon cruising", "Decent energy",
        "Sugar rush fading", "Surviving the afternoon", "Keep the pace",
        "Almost golden hour", "Powering through", "Focus check", "Zone state active"
    )
    in 18..20 -> listOf(
        "Day clocking out", "Evening in chat", "Almost done",
        "Wrapping things up", "Sunset state of mind", "Time to chill",
        "Task list cleared", "Closing tabs...", "Dinner loading", "Out of office"
    )
    else -> listOf(
        "Getting quiet", "Night mode", "Tomorrow can wait",
        "Do nothing club", "Zero stress found", "Battery at 5%",
        "Brain logging off", "Star gazing", "Cozy vibes only", "Peace and quiet"
    )
}.random()