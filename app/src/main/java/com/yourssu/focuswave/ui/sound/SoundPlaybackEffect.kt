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
import com.yourssu.focuswave.R
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.SoundTrackUiState

@Composable
fun SoundPlaybackEffect(
    soundTracks: List<SoundTrackUiState>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSoundTracks = rememberUpdatedState(soundTracks)
    val players = remember(context) { createPlayers(context) }

    LaunchedEffect(soundTracks, players) {
        applySoundTracks(players = players, soundTracks = soundTracks)
    }

    DisposableEffect(lifecycleOwner, players) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> pauseAll(players)
                Lifecycle.Event.ON_START -> applySoundTracks(
                    players = players,
                    soundTracks = latestSoundTracks.value
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

private fun createPlayers(context: Context): Map<SoundTrackId, MediaPlayer> {
    val appContext = context.applicationContext
    return SoundTrackId.entries.mapNotNull { id ->
        MediaPlayer.create(appContext, id.rawResourceId)?.apply {
            isLooping = true
            setVolume(0.5f, 0.5f)
        }?.let { player -> id to player }
    }.toMap()
}

private fun applySoundTracks(
    players: Map<SoundTrackId, MediaPlayer>,
    soundTracks: List<SoundTrackUiState>
) {
    soundTracks.forEach { track ->
        val player = players[track.id] ?: return@forEach
        val volume = track.volume.coerceIn(0f, 1f)

        runCatching { player.setVolume(volume, volume) }

        if (track.isEnabled) {
            runCatching {
                if (!player.isPlaying) player.start()
            }
        } else {
            runCatching {
                if (player.isPlaying) player.pause()
                //player.seekTo(0)
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

private val SoundTrackId.rawResourceId: Int
    get() = when (this) {
        SoundTrackId.Rain -> R.raw.rain_soft
        SoundTrackId.Ocean -> R.raw.hastings_beach
        SoundTrackId.Cafe -> R.raw.cafe_ambience
        SoundTrackId.Space -> R.raw.space_ambient
    }
