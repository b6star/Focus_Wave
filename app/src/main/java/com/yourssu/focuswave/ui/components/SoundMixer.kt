package com.yourssu.focuswave.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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

        soundTracks.forEach { track ->
            SoundMixerRow(
                track = track,
                onEnabledChange = onEnabledChange,
                onVolumeChange = onVolumeChange
            )
        }
    }
}

@Composable
private fun SoundMixerRow(
    track: SoundTrackUiState,
    onEnabledChange: (SoundTrackId, Boolean) -> Unit,
    onVolumeChange: (SoundTrackId, Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${track.volumePercent}%",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
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

        Switch(
            checked = track.isEnabled,
            onCheckedChange = { onEnabledChange(track.id, it) }
        )
    }
}
