package com.yourssu.focuswave.ui.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourssu.focuswave.ui.state.SECONDS_PER_MINUTE
import com.yourssu.focuswave.ui.state.SoundCategoryId
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.TimerPhase
import com.yourssu.focuswave.ui.state.TimerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TimerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var pausedSoundSnapshot: Map<SoundCategoryId, Float> = emptyMap()

    fun updateFocusMinutes(minutes: Int) {
        val nextMinutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        _uiState.update { currentState ->
            if (currentState.isRunning) return@update currentState

            currentState.copy(
                focusMinutes = nextMinutes,
                totalSeconds = nextMinutes * SECONDS_PER_MINUTE,
                remainingSeconds = nextMinutes * SECONDS_PER_MINUTE,
                phase = TimerPhase.READY,
                activePhase = TimerPhase.FOCUS
            )
        }
    }

    fun decreaseFocusMinutes() {
        _uiState.update { state ->
            state.copy(focusMinutes = (state.focusMinutes - 1).coerceAtLeast(1))
        }
    }

    fun increaseFocusMinutes() {
        _uiState.update { state ->
            state.copy(focusMinutes = (state.focusMinutes + 1).coerceAtMost(180))
        }
    }
    fun startTimer() {
        val currentState = _uiState.value
        if (timerJob?.isActive == true || currentState.isRunning) return

        when (currentState.phase) {
            TimerPhase.READY, TimerPhase.FINISHED -> startFocusPhase()
            TimerPhase.PAUSED -> resumeTimer()
            TimerPhase.FOCUS, TimerPhase.BREAK -> resumeTimer()
        }
    }

    fun pauseTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { currentState ->
            if (!currentState.isRunning) return@update currentState

            pausedSoundSnapshot = currentState.soundMixer.categories
                .filter { it.isEnabled }
                .associate { it.id to it.volume }

            currentState.copy(
                phase = TimerPhase.PAUSED,
                isRunning = false
            )
        }
    }

    fun resetTimer() {
        timerJob?.cancel()
        timerJob = null
        pausedSoundSnapshot = emptyMap()
        _uiState.update { currentState ->
            currentState.copy(
                totalSeconds = currentState.focusMinutes * SECONDS_PER_MINUTE,
                remainingSeconds = currentState.focusMinutes * SECONDS_PER_MINUTE,
                phase = TimerPhase.READY,
                activePhase = TimerPhase.FOCUS,
                isRunning = false,
                pathSeed = currentState.pathSeed + 1
            ).withAllSoundsStopped()
        }
    }

    fun startFocusPhase() {
        timerJob?.cancel()
        timerJob = null
        pausedSoundSnapshot = emptyMap()
        _uiState.update { currentState ->
            currentState.copy(
                totalSeconds = currentState.focusMinutes * SECONDS_PER_MINUTE,
                remainingSeconds = currentState.focusMinutes * SECONDS_PER_MINUTE,
                phase = TimerPhase.FOCUS,
                activePhase = TimerPhase.FOCUS,
                isRunning = true
            )
        }
        launchTimer()
    }

    fun startBreakPhase() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { currentState ->
            currentState.copy(
                totalSeconds = currentState.breakMinutes * SECONDS_PER_MINUTE,
                remainingSeconds = currentState.breakMinutes * SECONDS_PER_MINUTE,
                phase = TimerPhase.BREAK,
                activePhase = TimerPhase.BREAK,
                isRunning = true
            )
        }
        launchTimer()
    }

    fun setSoundEnabled(id: SoundCategoryId, isEnabled: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                soundMixer = currentState.soundMixer.copy(
                    categories = currentState.soundMixer.categories.map { category ->
                        if (category.id == id) category.copy(isEnabled = isEnabled) else category
                    }
                )
            )
        }
    }

    fun setSoundVolume(id: SoundCategoryId, volume: Float) {
        _uiState.update { currentState ->
            currentState.copy(
                soundMixer = currentState.soundMixer.copy(
                    categories = currentState.soundMixer.categories.map { category ->
                        if (category.id == id) category.copy(volume = volume.coerceIn(0f, 1f)) else category
                    }
                )
            )
        }
    }

    fun setSoundTrack(categoryId: SoundCategoryId, trackId: SoundTrackId) {
        _uiState.update { currentState ->
            currentState.copy(
                soundMixer = currentState.soundMixer.copy(
                    categories = currentState.soundMixer.categories.map { category ->
                        if (category.id == categoryId) {
                            category.copy(selectedTrackId = trackId)
                        } else {
                            category
                        }
                    }
                )
            )
        }
    }

    fun toggleSoundSelectionMode() {
        _uiState.update { currentState ->
            currentState.copy(
                soundMixer = currentState.soundMixer.copy(
                    isSelectionMode = !currentState.soundMixer.isSelectionMode
                )
            )
        }
    }

    fun increasePathSeed() {
        _uiState.update { currentState ->
            currentState.copy(pathSeed = currentState.pathSeed + 1)
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    private fun resumeTimer() {
        _uiState.update { currentState ->
            val resumePhase = when (currentState.phase) {
                TimerPhase.PAUSED -> currentState.activePhase
                TimerPhase.FOCUS, TimerPhase.BREAK -> currentState.phase
                TimerPhase.READY, TimerPhase.FINISHED -> TimerPhase.FOCUS
            }
            val restoredSoundCategories = restorePausedSoundCategories(currentState)
            pausedSoundSnapshot = emptyMap()

            currentState.copy(
                phase = resumePhase,
                activePhase = resumePhase,
                isRunning = true,
                soundMixer = currentState.soundMixer.copy(categories = restoredSoundCategories)
            )
        }
        launchTimer()
    }

    private fun restorePausedSoundCategories(currentState: TimerUiState) =
        if (currentState.phase == TimerPhase.PAUSED) {
            currentState.soundMixer.categories.map { category ->
                val pausedVolume = pausedSoundSnapshot[category.id]
                when {
                    pausedVolume == null -> category.copy(isEnabled = false)
                    category.isEnabled -> category.copy(isEnabled = true, volume = pausedVolume)
                    else -> category.copy(isEnabled = false)
                }
            }
        } else {
            currentState.soundMixer.categories
        }

    private fun launchTimer() {
        if (timerJob?.isActive == true) return

        timerJob = viewModelScope.launch {
            while (_uiState.value.isRunning) {
                delay(1_000)
                val shouldContinue = tickTimer()
                if (!shouldContinue) break
            }
        }
    }

    private fun tickTimer(): Boolean {
        var shouldContinue = true

        _uiState.update { currentState ->
            if (!currentState.isRunning) {
                shouldContinue = false
                return@update currentState
            }

            val nextRemainingSeconds = (currentState.remainingSeconds - 1).coerceAtLeast(0)
            if (nextRemainingSeconds > 0) {
                return@update currentState.copy(remainingSeconds = nextRemainingSeconds)
            }

            when (currentState.phase) {
                TimerPhase.FOCUS -> {
                    shouldContinue = false
                    timerJob = null
                    pausedSoundSnapshot = emptyMap()

                    currentState.copy(
                        remainingSeconds = 0,
                        phase = TimerPhase.FINISHED,
                        activePhase = TimerPhase.FINISHED,
                        isRunning = false
                    ).withAllSoundsStopped()
                }

                TimerPhase.BREAK -> {
                    shouldContinue = false
                    timerJob = null
                    pausedSoundSnapshot = emptyMap()
                    currentState.copy(
                        remainingSeconds = 0,
                        phase = TimerPhase.FINISHED,
                        activePhase = TimerPhase.FINISHED,
                        isRunning = false
                    ).withAllSoundsStopped()
                }

                TimerPhase.READY,
                TimerPhase.PAUSED,
                TimerPhase.FINISHED -> {
                    shouldContinue = false
                    timerJob = null
                    currentState.copy(isRunning = false)
                }
            }
        }

        return shouldContinue
    }
}

private fun TimerUiState.withAllSoundsStopped(): TimerUiState = copy(
    soundMixer = soundMixer.copy(
        categories = soundMixer.categories.map { it.copy(isEnabled = false) }
    )
)

private const val MIN_MINUTES = 1
private const val MAX_MINUTES = 180
