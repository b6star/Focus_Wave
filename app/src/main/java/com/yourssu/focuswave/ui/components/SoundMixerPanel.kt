package com.yourssu.focuswave.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yourssu.focuswave.R
import com.yourssu.focuswave.ui.state.SoundCategoryId
import com.yourssu.focuswave.ui.state.SoundCategoryUiState
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.theme.WhiteText85

@Composable
fun SoundMixerPanel(
    soundCategories: List<SoundCategoryUiState>,
    isSelectionMode: Boolean,
    onSelectionModeToggle: () -> Unit,
    onEnabledChange: (SoundCategoryId, Boolean) -> Unit,
    onVolumeChange: (SoundCategoryId, Float) -> Unit,
    onTrackSelected: (SoundCategoryId, SoundTrackId) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC061425), shape)
            .border(BorderStroke(1.dp, Color(0xFF66C7FF).copy(alpha = 0.18f)), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SOUND MIXER",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            IconButton(
                onClick = onSelectionModeToggle,
                modifier = Modifier
                    .size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Sound selection",
                    tint = if (isSelectionMode) Color.White else Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp || maxHeight < 300.dp
            val veryCompact = maxHeight < 230.dp
            val itemSpacing = 12.dp
            val visibleItemCount = minOf(soundCategories.size, if (compact || veryCompact) 2 else 3).coerceAtLeast(1)
            val itemWidth = (maxWidth - itemSpacing * (visibleItemCount - 1)) / visibleItemCount

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = soundCategories,
                    key = { it.id }
                ) { category ->
                    SoundMixerCard(
                        category = category,
                        isSelectionMode = isSelectionMode,
                        onEnabledChange = onEnabledChange,
                        onVolumeChange = onVolumeChange,
                        onTrackSelected = onTrackSelected,
                        modifier = Modifier.width(itemWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundMixerCard(
    category: SoundCategoryUiState,
    isSelectionMode: Boolean,
    onEnabledChange: (SoundCategoryId, Boolean) -> Unit,
    onVolumeChange: (SoundCategoryId, Float) -> Unit,
    onTrackSelected: (SoundCategoryId, SoundTrackId) -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = RoundedCornerShape(18.dp)
    val tileBackground = if (category.isEnabled) {
        Color(0xFF0A2A45).copy(alpha = 0.78f)
    } else {
        Color.White.copy(alpha = 0.075f)
    }
    val tileBorder = if (category.isEnabled) {
        Color(0xFF69CFFF).copy(alpha = 0.36f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }
    val titleColor = if (category.isEnabled) WhiteText85 else Color.White.copy(alpha = 0.58f)
    val secondaryColor = if (category.isEnabled) {
        Color.White.copy(alpha = 0.78f)
    } else {
        Color.White.copy(alpha = 0.44f)
    }

    val cardHeight = if (isSelectionMode) 205.dp else 115.dp

    val backGroundAlpha = if (category.isEnabled) 0.25f else 0.65f

    Box(
        modifier = modifier
            .height(cardHeight)
            .clip(tileShape)
            .background(tileBackground)
            .border(BorderStroke(1.dp, tileBorder),
                tileShape)
            .clickable {
                onEnabledChange(category.id, !category.isEnabled)
            }
    ) {
        Image(
            painter = painterResource(id = categoryBackgroundRes(category.id)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = backGroundAlpha))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIcon(category = category)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = category.title,
                        color = titleColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = category.selectedTrack?.title ?: "No track",
                        color = secondaryColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Slider(
                value = category.volume,
                onValueChange = { onVolumeChange(category.id, it) },
                enabled = category.isEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 32.dp)
                    .scale(scaleX = 1f, scaleY = 0.5f)
            )

            Text(
                text = "${category.volumePercent}%",
                color = Color.White.copy(alpha = if (category.isEnabled) 0.82f else 0.46f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (isSelectionMode) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    category.tracks.forEach { track ->
                        val isSelected = track.id == category.selectedTrackId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTrackSelected(category.id, track.id) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = track.title,
                                color = if (isSelected) WhiteText85 else
                                    Color.White.copy(alpha = 0.52f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF69CFFF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

private fun categoryBackgroundRes(categoryId: SoundCategoryId): Int {
    return when (categoryId) {
        SoundCategoryId.Rain -> R.drawable.category_rain
        SoundCategoryId.Ocean -> R.drawable.category_ocean
        SoundCategoryId.Cafe -> R.drawable.category_cafe
        SoundCategoryId.City -> R.drawable.category_city
        SoundCategoryId.Space -> R.drawable.category_space
    }
}
@Composable
private fun CategoryIcon(
    category: SoundCategoryUiState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .background(
                color = Color(0xFF168BFF).copy(alpha = if (category.isEnabled) 0.30f else 0.14f),
                shape = CircleShape
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (category.id) {
                SoundCategoryId.Rain -> Icons.Default.Cloud
                SoundCategoryId.Ocean -> Icons.Default.Waves
                SoundCategoryId.Cafe -> Icons.Default.Coffee
                SoundCategoryId.City -> Icons.Default.DirectionsCar
                SoundCategoryId.Space -> Icons.Default.Public
            },
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(19.dp)
        )
    }
}
