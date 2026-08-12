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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourssu.focuswave.ui.orbit.OrbitSection
import com.yourssu.focuswave.ui.orbit.OrbitUtil
import com.yourssu.focuswave.server.FileServerManager
import com.yourssu.focuswave.ui.chat.ChatOverlay
import com.yourssu.focuswave.ui.components.SoundMixerPanel
import com.yourssu.focuswave.ui.fileshare.FileShareOverlay
import com.yourssu.focuswave.ui.sound.SoundPlaybackEffect
import com.yourssu.focuswave.ui.state.SoundCategoryId
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.TimerPhase
import com.yourssu.focuswave.ui.state.TimerUiState
import com.yourssu.focuswave.ui.theme.FocusWaveTheme
import com.yourssu.focuswave.ui.theme.WhiteText85
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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import com.yourssu.focuswave.ui.theme.WhiteText100
import kotlinx.coroutines.withTimeoutOrNull

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
            onSoundVolumeChange = timerViewModel::setSoundVolume,
            onSoundSelectionModeToggle = timerViewModel::toggleSoundSelectionMode,
            onSoundTrackSelected = timerViewModel::setSoundTrack
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
    onSoundEnabledChange: (SoundCategoryId, Boolean) -> Unit,
    onSoundVolumeChange: (SoundCategoryId, Float) -> Unit,
    onSoundSelectionModeToggle: () -> Unit,
    onSoundTrackSelected: (SoundCategoryId, SoundTrackId) -> Unit,
) {
    val playbackSoundCategories = if (timerUiState.phase == TimerPhase.PAUSED) {
        timerUiState.soundMixer.categories.map { it.copy(isEnabled = false) }
    } else {
        timerUiState.soundMixer.categories
    }

    SoundPlaybackEffect(soundCategories = playbackSoundCategories)

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
                onIncreaseFocus = timerViewModel::increaseFocusMinutes
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
                soundCategories = timerUiState.soundMixer.categories,
                isSelectionMode = timerUiState.soundMixer.isSelectionMode,
                onSelectionModeToggle = onSoundSelectionModeToggle,
                onEnabledChange = onSoundEnabledChange,
                onVolumeChange = onSoundVolumeChange,
                onTrackSelected = onSoundTrackSelected
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
                val horizontalPadding = if (compactHeight) 18.dp else 26.dp
                val verticalPadding = if (compactHeight) 12.dp else 24.dp
                val sectionGap = if (compactHeight) 10.dp else 14.dp
                val orbitMinHeight = if (compactHeight) 260.dp else 420.dp

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
                            .weight(1.35f)
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
            painter = painterResource(id = R.drawable.grok_space_03),
            contentDescription = "Space background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x90000006),
                            Color(0x78000410),
                            Color(0xA0000006)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), RoundedCornerShape(26.dp))
        )
    }
}

@Composable
private fun TimerControlsPanel(
    uiState: TimerUiState,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResetClick: () -> Unit,
    onNewPathClick: () -> Unit,
    onDecreaseFocus: () -> Unit,
    onIncreaseFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val journeyText = OrbitUtil.getStateByProgress(
        progress = uiState.progress,
        phase = uiState.phase,
        isRunning = uiState.isRunning
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.GpsFixed,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "FOCUS",
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = onResetClick
                )
                CompactIconButton(
                    icon = Icons.Default.Settings,
                    onClick = onNewPathClick
                )
            }
        }

        // 시간 텍스트
        Text(
            text = uiState.formattedTime,
            color = WhiteText100.copy(alpha = 0.85f),
            style = MaterialTheme.typography.displayLarge,
            fontSize = 92.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (uiState.phase == TimerPhase.READY) "Ready for launch" else journeyText,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Row(
            modifier = Modifier.fillMaxWidth(0.78f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(54.dp)
        ) {
            DurationPill(
                minutes = uiState.focusMinutes,
                enabled = uiState.canEditDurations,
                onDecrease = onDecreaseFocus,
                onIncrease = onIncreaseFocus,
                modifier = Modifier.weight(1f)
            )
            LaunchButton(
                isRunning = uiState.isRunning,
                onClick = {
                    if (uiState.isRunning) onPauseClick() else onStartClick()
                },
                modifier = Modifier.weight(1.06f)
            )
        }
    }
}

@Composable
private fun DurationPill(
    minutes: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)

    Row(
        modifier = modifier
            .height(60.dp)
            .clip(shape)
            .background(Color(0xCC090D1D))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperIconButton(
            icon = Icons.Default.Remove,
            enabled = enabled,
            onClick = onDecrease
        )
        Text(
            text = "$minutes min",
            color = WhiteText85,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        StepperIconButton(
            icon = Icons.Default.Add,
            enabled = enabled,
            onClick = onIncrease
        )
    }
}

@Composable
private fun StepperIconButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 0.88f else 0.28f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LaunchButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedButton(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(32.dp),
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp
        ),
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = if (isRunning) Color.White.copy(alpha = 0.16f) else Color(0xCC071839),
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 18.dp)
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.RocketLaunch,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isRunning) "PAUSE" else "LAUNCH",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun CompactIconButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(14.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(19.dp)
        )
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
                .background(Color(0xCC061425))
                .border(
                    BorderStroke(1.dp, Color(0xFF66C7FF).copy(alpha = 0.18f)),
                    RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavButton(
                label = "Files",
                icon = Icons.Default.UploadFile,
                onClick = onFileShareClick
            )
            BottomNavButton(
                label = "Chat",
                icon = Icons.Default.ChatBubble,
                onClick = onChatClick
            )
            BottomNavButton(
                label = "Settings",
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
            containerColor = Color.White.copy(alpha = 0.10f),
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
                color = WhiteText85,
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
                color = WhiteText85,
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
                    onIncreaseFocus = {},
                    onDecreaseFocus = {}
                )
            },
            countdownOverlay = {},
            soundMixerPanel = {},
            bottomNavigation = {},
            fileShareOverlay = {},
        )
    }
}
