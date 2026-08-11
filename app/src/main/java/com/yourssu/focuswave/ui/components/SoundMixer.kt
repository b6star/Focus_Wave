package com.yourssu.focuswave.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.SoundTrackUiState

@Composable
fun SoundMixerPanel(
    soundTracks: List<SoundTrackUiState>,
    onEnabledChange: (SoundTrackId, Boolean) -> Unit,
    onVolumeChange: (SoundTrackId, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.14f), shape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "SOUND MIXER",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val itemSpacing = 10.dp
            val visibleItemCount = minOf(soundTracks.size, 3).coerceAtLeast(1)
            val itemWidth = (maxWidth - itemSpacing * (visibleItemCount - 1)) / visibleItemCount

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = soundTracks,
                    key = { it.id }
                ) { track ->
                    SoundMixerRow(
                        track = track,
                        onEnabledChange = onEnabledChange,
                        onVolumeChange = onVolumeChange,
                        modifier = Modifier.width(itemWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundMixerRow(
    track: SoundTrackUiState,
    onEnabledChange: (SoundTrackId, Boolean) -> Unit,
    onVolumeChange: (SoundTrackId, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = RoundedCornerShape(10.dp)
    val tileBackground = if (track.isEnabled) {
        Color.White.copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val tileBorder = if (track.isEnabled) {
        Color.White.copy(alpha = 0.34f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }
    val titleColor = if (track.isEnabled) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.58f)
    }
    val secondaryColor = if (track.isEnabled) {
        Color.White.copy(alpha = 0.78f)
    } else {
        Color.White.copy(alpha = 0.44f)
    }

    Column(
        modifier = modifier
            .background(tileBackground, tileShape)
            .border(BorderStroke(1.dp, tileBorder), tileShape)
            .clickable { onEnabledChange(track.id, !track.isEnabled) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = track.title,
                    color = titleColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = "${track.volumePercent}%",
                    color = secondaryColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Slider(
            value = track.volume,
            onValueChange = { onVolumeChange(track.id, it) },
            enabled = track.isEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 28.dp)
        )
    }
}
