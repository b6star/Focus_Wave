package com.yourssu.focuswave.ui.sound

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yourssu.focuswave.ui.state.SoundCategoryUiState
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.defaultSoundCategories

@Composable
fun SoundPlaybackEffect(
    soundCategories: List<SoundCategoryUiState>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSoundCategories = rememberUpdatedState(soundCategories)

    val players = remember(context) {
        createPlayers(context)
    }

    LaunchedEffect(players, soundCategories) {
        applySoundCategories(players = players, soundCategories = soundCategories)
    }

    DisposableEffect(lifecycleOwner, players) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> pauseAll(players)
                Lifecycle.Event.ON_START -> applySoundCategories(
                    players = players,
                    soundCategories = latestSoundCategories.value
                )
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseAll(players)
        }
    }
}

private fun createPlayers(
    context: Context
): Map<SoundTrackId, MediaPlayer> {
    val appContext = context.applicationContext
    return defaultSoundCategories
        .flatMap { it.tracks }
        .distinctBy { it.id }
        .mapNotNull { track ->
            MediaPlayer.create(appContext, track.rawResId)?.apply {
            isLooping = true
            setVolume(0.5f, 0.5f)
        }?.let { player -> track.id to player }
        }.toMap()
}

private fun applySoundCategories(
    players: Map<SoundTrackId, MediaPlayer>,
    soundCategories: List<SoundCategoryUiState>
) {
    players.forEach { (trackId, player) ->
        val category = soundCategories.firstOrNull {
            it.isEnabled && it.selectedTrackId == trackId
        }

        if (category != null) {
            val volume = category.volume.coerceIn(0f, 1f)
            runCatching { player.setVolume(volume, volume) }

            runCatching {
                if (!player.isPlaying) player.start()
            }
        } else {
            runCatching {
                if (player.isPlaying ) player.pause()
            }
        }
    }
}

private fun pauseAll(players: Map<SoundTrackId, MediaPlayer>) {
    players.values.forEach { player ->
        runCatching {
            if (player.isPlaying) player.pause()
        }
    }
}

private fun releaseAll(players: Map<SoundTrackId, MediaPlayer>) {
    players.values.forEach { player ->
        runCatching {
            if (player.isPlaying) player.stop()
        }
        runCatching { player.release() }
    }
}
