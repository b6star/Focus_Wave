package com.yourssu.focuswave

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourssu.focuswave.ui.orbit.OrbitSection
import com.yourssu.focuswave.ui.orbit.OrbitUtil
import com.yourssu.focuswave.server.FileServerManager
import com.yourssu.focuswave.ui.chat.ChatOverlay
import com.yourssu.focuswave.ui.components.SoundMixerPanel
import com.yourssu.focuswave.ui.fileshare.FileShareOverlay
import com.yourssu.focuswave.ui.sound.SoundPlaybackEffect
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.TimerPhase
import com.yourssu.focuswave.ui.state.TimerUiState
import com.yourssu.focuswave.ui.theme.FocusWaveTheme
import com.yourssu.focuswave.ui.timer.TimerViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FocusWaveTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen(
    timerViewModel: TimerViewModel = viewModel(),
    fileServerManager: FileServerManager = viewModel()
) {
    val timerUiState by timerViewModel.uiState.collectAsState()
    val fileShareUiState by fileServerManager.uiState.collectAsState()
    var showFileShare by rememberSaveable { mutableStateOf(false) }
    var showChat by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity


    DisposableEffect(timerUiState.isRunning, fileShareUiState.isRunning) {
        if (timerUiState.isRunning || fileShareUiState.isRunning) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MainScreenContent(
            timerUiState = timerUiState,
            onStartClick = timerViewModel::startTimer,
            onPauseClick = timerViewModel::pauseTimer,
            onResetClick = timerViewModel::resetTimer,
            onNewPathClick = timerViewModel::increasePathSeed,
            onFileShareClick = { showFileShare = true },
            onChatClick = { showChat = true },
            onSettingsClick = { },
            timerViewModel = timerViewModel,
            onSoundEnabledChange = timerViewModel::setSoundEnabled,
            onSoundVolumeChange = timerViewModel::setSoundVolume
        )

        if (showFileShare) {
            FileShareOverlay(
                uiState = fileShareUiState,
                onStartClick = fileServerManager::startServer,
                onStopClick = fileServerManager::stopServer,
                onDismiss = { showFileShare = false }
            )
        }

        if (showChat) {
            ChatOverlay(
                uiState = fileShareUiState,
                onStartClick = fileServerManager::startServer,
                onStopClick = fileServerManager::stopServer,
                onDismiss = { showChat = false }
            )
        }
    }
}

@Composable
private fun MainScreenContent(
    timerUiState: TimerUiState,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResetClick: () -> Unit,
    onNewPathClick: () -> Unit,
    onFileShareClick: () -> Unit,
    onChatClick: () -> Unit,
    onSettingsClick: () -> Unit,
    timerViewModel: TimerViewModel,
    onSoundEnabledChange: (SoundTrackId, Boolean) -> Unit,
    onSoundVolumeChange: (SoundTrackId, Float) -> Unit,
) {
    val playbackSoundTracks = if (timerUiState.phase == TimerPhase.PAUSED) {
        timerUiState.soundTracks.map { it.copy(isEnabled = false) }
    } else {
        timerUiState.soundTracks
    }

    SoundPlaybackEffect(soundTracks = playbackSoundTracks)

    FocusScreen(
        uiState = timerUiState,
        timerOverlay = {
            TimerControlsPanel(
                uiState = timerUiState,
                onStartClick = onStartClick,
                onPauseClick = onPauseClick,
                onResetClick = onResetClick,
                onNewPathClick = onNewPathClick,
                onDecreaseFocus = timerViewModel::decreaseFocusMinutes,
                onIncreaseFocus = timerViewModel::increaseFocusMinutes,
                onDecreaseBreak = timerViewModel::decreaseBreakMinutes,
                onIncreaseBreak = timerViewModel::increaseBreakMinutes
            )
        },
        countdownOverlay = {
            BreakCountdownOverlay(
                isVisible = timerUiState.showBreakCountdown,
                count = timerUiState.breakCountdownNumber
            )
        },
        soundMixerPanel = {
            SoundMixerPanel(
                soundTracks = timerUiState.soundTracks,
                onEnabledChange = onSoundEnabledChange,
                onVolumeChange = onSoundVolumeChange
            )
        },
        bottomNavigation = {
            FocusBottomNavigation(
                onFileShareClick = onFileShareClick,
                onChatClick = onChatClick,
                onSettingsClick = onSettingsClick
            )
        }
    )
}

@Composable
private fun FocusScreen(
    uiState: TimerUiState,
    timerOverlay: @Composable () -> Unit,
    countdownOverlay: @Composable () -> Unit,
    soundMixerPanel: @Composable () -> Unit,
    bottomNavigation: @Composable () -> Unit = {},
    fileShareOverlay: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        FocusScene()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = bottomNavigation
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val compactHeight = maxHeight < 720.dp
                val horizontalPadding = if (compactHeight) 12.dp else 16.dp
                val verticalPadding = if (compactHeight) 8.dp else 12.dp
                val sectionGap = if (compactHeight) 8.dp else 12.dp
                val orbitMinHeight = if (compactHeight) 170.dp else 220.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(sectionGap)
                ) {
                    timerOverlay()


                    OrbitSection(
                        progress = uiState.progress,
                        pathSeed = uiState.pathSeed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .heightIn(min = orbitMinHeight)
                    )

                    soundMixerPanel()
                }
            }
        }

        countdownOverlay()
        fileShareOverlay()
    }
}

@Composable
private fun FocusScene(
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.grok_space_02),
            contentDescription = "Space background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerControlsPanel(
    uiState: TimerUiState,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResetClick: () -> Unit,
    onNewPathClick: () -> Unit,
    onDecreaseFocus: () -> Unit,
    onIncreaseFocus: () -> Unit,
    onDecreaseBreak: () -> Unit,
    onIncreaseBreak: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    val journeyText = OrbitUtil.getStateByProgress(
        progress = uiState.progress,
        phase = uiState.phase,
        isRunning = uiState.isRunning
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.09f)
                    )
                )
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)), shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            )
            {
                Text(
                    text = uiState.formattedTime,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "TOTAL ${uiState.totalFormattedTime} / ${uiState.statusText}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = journeyText,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.activePhase.name,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        LinearProgressIndicator(
            progress = { uiState.progress },
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.24f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimerDurationSettings(
                focusMinutes = uiState.focusMinutes,
                breakMinutes = uiState.breakMinutes,
                enabled = uiState.canEditDurations,
                onDecreaseFocus = onDecreaseFocus,
                onIncreaseFocus = onIncreaseFocus,
                onDecreaseBreak = onDecreaseBreak,
                onIncreaseBreak = onIncreaseBreak
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                )
            ) {
                TimerActionButton(
                    icon = if (uiState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    onClick = {
                        if (uiState.isRunning) {
                            onPauseClick()
                        } else {
                            onStartClick()
                        }
                    }
                )
                TimerActionButton(
                    icon = Icons.Default.Refresh,
                    onClick = onResetClick
                )
                TimerActionButton(
                    icon = Icons.Default.Route,
                    onClick = onNewPathClick
                )
            }

        }
    }
}

@Composable
private fun FocusBottomNavigation(
    onFileShareClick: () -> Unit,
    onChatClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.42f))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavButton(
                label = "파일",
                icon = Icons.Default.UploadFile,
                onClick = onFileShareClick
            )
            BottomNavButton(
                label = "채팅",
                icon = Icons.Default.ChatBubble,
                onClick = onChatClick
            )
            BottomNavButton(
                label = "설정",
                icon = Icons.Default.Settings,
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun BottomNavButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(22.dp),
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp
        ),
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.08f),
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerDurationSettings(
    focusMinutes: Int,
    breakMinutes: Int,
    enabled: Boolean,
    onDecreaseFocus: () -> Unit,
    onIncreaseFocus: () -> Unit,
    onDecreaseBreak: () -> Unit,
    onIncreaseBreak: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DurationStepper(
            label = "FOCUS",
            minutes = focusMinutes,
            enabled = enabled,
            onDecrease = onDecreaseFocus,
            onIncrease = onIncreaseFocus
        )

        /*
        DurationStepper(
            label = "BREAK",
            minutes = breakMinutes,
            enabled = enabled,
            onDecrease = onDecreaseBreak,
            onIncrease = onIncreaseBreak
        )
        */
    }
}

@Composable
private fun DurationStepper(
    label: String,
    minutes: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .width(108.dp)
            .background(Color.White.copy(alpha = 0.1f), shape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(
            text = "-",
            enabled = enabled,
            onClick = onDecrease
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "${minutes}m",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        StepperButton(
            text = "+",
            enabled = enabled,
            onClick = onIncrease
        )
    }
}

@Composable
private fun StepperButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val contentAlpha = if (enabled) 1f else 0.38f

    Box(
        modifier = Modifier
            .size(30.dp)
            .background(Color.White.copy(alpha = if (enabled) 0.20f else 0.08f), shape)
            .border(
                BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = if (enabled) 0.34f else 0.12f)
                ),
                shape
            )
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    down.consume()
                    val releasedBeforeLongPress = withTimeoutOrNull(450L) {
                        waitForUpOrCancellation()
                    }
                    if (releasedBeforeLongPress != null) {
                        releasedBeforeLongPress.consume()
                        onClick()
                        return@awaitEachGesture
                    }
                    onClick()
                    while (true) {
                        val up = withTimeoutOrNull(100L) {
                            waitForUpOrCancellation()
                        }
                        if (up != null) {
                            up.consume()
                            break
                        }
                        onClick()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = contentAlpha),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TimerActionButton(
    icon: ImageVector   ,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = CircleShape

    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape),
        shape = shape,
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 5.dp,
            pressedElevation = 1.dp,
            hoveredElevation = 7.dp,
            focusedElevation = 7.dp
        ),
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = Color.White.copy(alpha = 0.2f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.1f),
            disabledContentColor = Color.White.copy(alpha = 0.42f)
        ),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun BreakCountdownOverlay(
    isVisible: Boolean,
    count: Int,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .size(168.dp)
                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(84.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    RoundedCornerShape(84.dp)
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Break ends in",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    backgroundColor = 0xFF000000
)
@Composable
fun MainScreenPreview() {
    val previewState = TimerUiState()

    FocusWaveTheme {
        FocusScreen(
            uiState = previewState,
            timerOverlay = {
                TimerControlsPanel(
                    uiState = previewState,
                    onStartClick = {},
                    onPauseClick = {},
                    onResetClick = {},
                    onNewPathClick = {},
                    onIncreaseBreak = {},
                    onIncreaseFocus = {},
                    onDecreaseFocus = {},
                    onDecreaseBreak = {}
                )
            },
            countdownOverlay = {},
            soundMixerPanel = {},
            bottomNavigation = {},
            fileShareOverlay = {},
        )
    }
}
