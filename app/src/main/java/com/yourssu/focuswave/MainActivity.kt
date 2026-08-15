package com.yourssu.focuswave

import android.app.Activity
import android.content.Context
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourssu.focuswave.ui.orbit.OrbitSection
import com.yourssu.focuswave.util.OrbitUtil
import com.yourssu.focuswave.server.FileServerManager
import com.yourssu.focuswave.ui.chat.ChatOverlay
import com.yourssu.focuswave.ui.sound.SoundMixerPanel
import com.yourssu.focuswave.ui.fileshare.FileShareOverlay
import com.yourssu.focuswave.ui.sound.SoundPlaybackEffect
import com.yourssu.focuswave.ui.state.SoundCategoryId
import com.yourssu.focuswave.ui.state.SoundTrackId
import com.yourssu.focuswave.ui.state.TimerPhase
import com.yourssu.focuswave.ui.state.TimerUiState
import com.yourssu.focuswave.ui.theme.FocusWaveTheme
import com.yourssu.focuswave.ui.theme.WhiteText85
import com.yourssu.focuswave.viewmodel.FocusViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.core.content.edit
import com.yourssu.focuswave.ui.theme.WhiteText100
import com.yourssu.focuswave.ui.theme.WhiteText75
import com.yourssu.focuswave.util.LayoutSpacing
import com.yourssu.focuswave.util.layoutSpacing
import com.yourssu.focuswave.util.rememberCurrentDateText
import com.yourssu.focuswave.util.rememberCurrentTimeStatusText
import com.yourssu.focuswave.util.rememberCurrentTimeText
import kotlinx.coroutines.delay

private const val AOD_BACKGROUND_CHANGE_INTERVAL_MILLIS = 60 * 60 * 1000L

private val TIMER_BACKGROUNDS = listOf(
    R.drawable.bg_timer_milkyway,
    R.drawable.bg_aod_milkyway2,
    R.drawable.bg_aod_milkyway,
)

private val AOD_BACKGROUNDS = listOf(
    R.drawable.bg_aod_aurora,
    R.drawable.bg_aod_aurora2,
    R.drawable.bg_aod_aurora3,
    R.drawable.bg_aod_aurora4,
    R.drawable.bg_aod_aurora5,
    R.drawable.bg_aod_aurora6,
    R.drawable.bg_aod_aurora7

)

private fun glassPillColors(active: Boolean): List<Color> =
    if (active) {
        listOf(
            Color.White.copy(alpha = 0.18f),
            Color(0xFF14213A).copy(alpha = 0.56f),
            Color(0xFF07111F).copy(alpha = 0.70f)
        )
    } else {
        listOf(
            Color.White.copy(alpha = 0.14f),
            Color(0xFF12315A).copy(alpha = 0.68f),
            Color(0xFF071839).copy(alpha = 0.82f)
        )
    }

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
    companion object {
        val PREFERENCES_NAME = "com.yourssu.focuswave"
        val FOCUS_MODE = "focus-mode"
        val AOD_MODE = "focus-aod"
        val TIMER_MODE = "focus-timer"
    }
}

@Composable
fun MainScreen(
    focusViewModel: FocusViewModel = viewModel(),
    fileServerManager: FileServerManager = viewModel()
) {
    val timerUiState by focusViewModel.uiState.collectAsState()
    val serverUiState by fileServerManager.serverUiState.collectAsState()
    val fileShareUiState by fileServerManager.fileShareUiState.collectAsState()
    var showFileShare by rememberSaveable { mutableStateOf(false) }
    var showChat by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity

    val prefs = remember(context) {
        context.getSharedPreferences(
            MainActivity.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    var focusMode by rememberSaveable {
        mutableStateOf(
            prefs.getString(MainActivity.FOCUS_MODE,
                MainActivity.TIMER_MODE)
                ?: MainActivity.TIMER_MODE
        )
    }

    LaunchedEffect(timerUiState.isRunning, focusMode) {
        if (timerUiState.isRunning || focusMode == MainActivity.AOD_MODE) {
            focusViewModel.turnSoundOn()
        } else {
            focusViewModel.turnSoundOff()
        }

    }



    DisposableEffect(timerUiState.isRunning, serverUiState.isRunning) {
        if (timerUiState.isRunning || serverUiState.isRunning || focusMode == MainActivity.AOD_MODE) {
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
            focusMode = focusMode,
            timerUiState = timerUiState,
            onStartClick = focusViewModel::startTimer,
            onPauseClick = focusViewModel::pauseTimer,
            onResetClick = focusViewModel::resetTimer,
            onNewPathClick = focusViewModel::increasePathSeed,
            onDecreaseFocus = focusViewModel::decreaseFocusMinutes,
            onIncreaseFocus = focusViewModel::increaseFocusMinutes,
            onChangeModeClick = {
                val nextMode = if (focusMode ==
                    MainActivity.TIMER_MODE) {
                    MainActivity.AOD_MODE
                } else {
                    MainActivity.TIMER_MODE
                }
                focusMode = nextMode
                prefs.edit {
                    putString(MainActivity.FOCUS_MODE, nextMode)
                }
            },
            onFileShareClick = { showFileShare = true },
            onChatClick = { showChat = true },
            onSettingsClick = { },
            onSoundEnabledChange = focusViewModel::setSoundEnabled,
            onSoundVolumeChange = focusViewModel::setSoundVolume,
            onSoundSelectionModeToggle = focusViewModel::toggleSoundSelectionMode,
            onSoundTrackSelected = focusViewModel::setSoundTrack
        )

        if (showFileShare) {
            FileShareOverlay(
                serverUiState = serverUiState,
                fileShareUiState = fileShareUiState,
                onStartClick = fileServerManager::startServer,
                onStopClick = fileServerManager::stopServer,
                onConnectionInfoToggle = fileServerManager::toggleConnectionInfoExpanded,
                onDismiss = { showFileShare = false }
            )
        }

        if (showChat) {
            ChatOverlay(
                serverUiState = serverUiState,
                onStartClick = fileServerManager::startServer,
                onStopClick = fileServerManager::stopServer,
                onConnectionInfoToggle = fileServerManager::toggleConnectionInfoExpanded,
                onDismiss = { showChat = false }
            )
        }
    }
}

@Composable
private fun MainScreenContent(
    focusMode: String,
    timerUiState: TimerUiState,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResetClick: () -> Unit,
    onChangeModeClick: () -> Unit,
    onNewPathClick: () -> Unit,
    onDecreaseFocus: () -> Unit,
    onIncreaseFocus: () -> Unit,
    onFileShareClick: () -> Unit,
    onChatClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSoundEnabledChange: (SoundCategoryId, Boolean) -> Unit,
    onSoundVolumeChange: (SoundCategoryId, Float) -> Unit,
    onSoundSelectionModeToggle: () -> Unit,
    onSoundTrackSelected: (SoundCategoryId, SoundTrackId) -> Unit,
) {
    SoundPlaybackEffect(
        soundCategories = timerUiState.soundMixer.categories,
        isPlaybackEnabled = timerUiState.soundMixer.isPlaybackEnabled
    )

    FocusScreen(
        focusMode = focusMode,
        uiState = timerUiState,
        modeHeader = { onRefreshAodBackground, onRefreshTimerBackground, isCompact, statusRefreshTrigger ->
            if (focusMode == MainActivity.TIMER_MODE) {
                TimerControlsPanel(
                    uiState = timerUiState,
                    onStartClick = onStartClick,
                    onPauseClick = onPauseClick,
                    onResetClick = { onResetClick()
                        onRefreshTimerBackground() },
                    onChangeModeClick = onChangeModeClick,
                    onNewPathClick = onNewPathClick,
                    onDecreaseFocus = onDecreaseFocus,
                    onIncreaseFocus = onIncreaseFocus,
                    isCompact = isCompact
                )
            } else {
                AodPanel(
                    onChangeModeClick = onChangeModeClick,
                    onRefreshBackgroundClick = onRefreshAodBackground,
                    isCompact = isCompact,
                    statusRefreshTrigger = statusRefreshTrigger
                )
            }
        },
        countdownOverlay = {
            BreakCountdownOverlay(
                isVisible = timerUiState.showBreakCountdown,
                count = timerUiState.breakCountdownNumber
            )
        },
        soundMixerPanel = { isCompact ->
            SoundMixerPanel(
                soundCategories = timerUiState.soundMixer.categories,
                isSelectionMode = timerUiState.soundMixer.isSelectionMode,
                onSelectionModeToggle = onSoundSelectionModeToggle,
                onEnabledChange = onSoundEnabledChange,
                onVolumeChange = onSoundVolumeChange,
                onTrackSelected = onSoundTrackSelected,
                isCompact = isCompact
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
    focusMode: String,
    uiState: TimerUiState,
    modeHeader: @Composable (
        onRefreshAodBackground: () -> Unit,
        onRefreshTimerBackground: () -> Unit,
        isCompact: Boolean,
        statusRefreshTrigger: Int
    ) -> Unit,
    countdownOverlay: @Composable () -> Unit,
    soundMixerPanel: @Composable (isCompact: Boolean) -> Unit,
    bottomNavigation: @Composable () -> Unit = {},
    fileShareOverlay: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val backgroundState = rememberFocusBackground(focusMode)
    var statusRefreshTrigger by rememberSaveable {
        mutableIntStateOf(0)
    }
    val refreshAodBackground = {
        backgroundState.refreshAodBackground()
        statusRefreshTrigger += 1
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompact = maxHeight < LayoutSpacing.COMPACT_HEIGHT_DP.dp || maxWidth < LayoutSpacing.COMPACT_WIDTH_DP.dp
        FocusScene(
            isCompact = isCompact,
            bgImagePath = backgroundState.selectedBackground,
            overlayDarkness = 0.5f
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = bottomNavigation
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val horizontalPadding = if (isCompact) 18.dp else 26.dp
                val verticalPadding = if (isCompact) 2.dp else 4.dp
                val sectionGap = if (isCompact) 10.dp else 14.dp
                val orbitMinHeight = if (isCompact) 260.dp else 420.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(sectionGap)
                ) {
                    modeHeader(
                        refreshAodBackground,
                        backgroundState.refreshTimerBackground,
                        isCompact,
                        statusRefreshTrigger
                    )
                    if (focusMode == MainActivity.TIMER_MODE) {
                        OrbitSection(
                            progress = uiState.progress,
                            pathSeed = uiState.pathSeed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1.35f)
                                .heightIn(min = orbitMinHeight)
                        )
                    }
                    else {
                        // Keeps the sound mixer anchored near the bottom in AOD mode.
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1.35f)
                                .heightIn(min = orbitMinHeight)
                        )
                    }

                    soundMixerPanel(isCompact)
                }
            }
        }

        countdownOverlay()
        fileShareOverlay()
    }
}

private data class FocusBackgroundState(
    val selectedBackground: Int,
    val refreshAodBackground: () -> Unit,
    val refreshTimerBackground: () -> Unit
)
@Composable
private fun rememberFocusBackground(focusMode: String): FocusBackgroundState {
    var selectedTimerBackground by rememberSaveable {
        mutableIntStateOf(TIMER_BACKGROUNDS.random())
    }
    var selectedAodBackground by rememberSaveable {
        mutableIntStateOf(AOD_BACKGROUNDS.random())
    }
    var lastAodBackgroundChangedAt by rememberSaveable {
        mutableStateOf(System.currentTimeMillis())
    }

    fun refreshTimerBackground() {
        selectedTimerBackground = TIMER_BACKGROUNDS
            .filter { it != selectedTimerBackground }
            .ifEmpty { TIMER_BACKGROUNDS }
            .random()
    }

    fun refreshAodBackground() {
        selectedAodBackground = AOD_BACKGROUNDS
            .filter { it != selectedAodBackground }
            .ifEmpty { AOD_BACKGROUNDS }
            .random()
        lastAodBackgroundChangedAt = System.currentTimeMillis()
    }

    LaunchedEffect(focusMode) {
        if (focusMode == MainActivity.TIMER_MODE) {
            if (selectedTimerBackground !in TIMER_BACKGROUNDS) {
                selectedTimerBackground = TIMER_BACKGROUNDS.first()
            }
            return@LaunchedEffect
        }

        if (selectedAodBackground !in AOD_BACKGROUNDS) {
            selectedAodBackground = AOD_BACKGROUNDS.first()
            lastAodBackgroundChangedAt = System.currentTimeMillis()
        }

        while (true) {
            val elapsed = System.currentTimeMillis() - lastAodBackgroundChangedAt
            val remaining = (AOD_BACKGROUND_CHANGE_INTERVAL_MILLIS - elapsed)
                .coerceAtLeast(0L)

            delay(remaining)
            refreshAodBackground()
        }
    }

    val resultBg = if (focusMode == MainActivity.TIMER_MODE) {
        selectedTimerBackground
    } else {
        selectedAodBackground
    }

    return FocusBackgroundState(
        selectedBackground = resultBg,
        refreshAodBackground = ::refreshAodBackground,
        refreshTimerBackground = ::refreshTimerBackground
    )
}

@Composable
private fun FocusScene(
    isCompact: Boolean,
    modifier: Modifier = Modifier,
    overlayDarkness: Float = 0.5f,
    bgImagePath: Int
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = bgImagePath),
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
                            Color(0xFF000006).copy(alpha = (overlayDarkness * 1.35f).coerceIn(0f, 1f)),
                            Color(0xFF000410).copy(alpha = (overlayDarkness * 0.75f).coerceIn(0f, 1f)),
                            Color(0xFF000006).copy(alpha = (overlayDarkness * 1.35f).coerceIn(0f, 1f))
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(layoutSpacing(isCompact).framePadding)
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    RoundedCornerShape(26.dp)
                )
        )
    }
}


@Composable
private fun AodPanel(
    onChangeModeClick: () -> Unit,
    onRefreshBackgroundClick: () -> Unit,
    isCompact: Boolean,
    statusRefreshTrigger: Int
) {
    val spacing = layoutSpacing(isCompact)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 헤더
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
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = WhiteText75,
                    modifier = Modifier.size(spacing.iconSize)
                )
                Text(
                    text = "Always On Display",
                    color = WhiteText75,
                    style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.iconSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = onRefreshBackgroundClick,
                    iconSize = spacing.iconSize
                )
                CompactIconButton(
                    icon = Icons.Default.SwapHoriz,
                    onClick = onChangeModeClick,
                    iconSize = spacing.iconSize
                )
            }
        }


        Text(
            text = rememberCurrentDateText(),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        val timeText = rememberCurrentTimeText()
        val (time, meridiem) = timeText.split("-").let { parts ->
            parts[0] to parts.getOrElse(1) { "" }
        }


        // 시간 텍스트
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = 10.dp,
                alignment = Alignment.CenterHorizontally
            )
        ) {

            Text(
                text = time,
                color = WhiteText100.copy(alpha = 0.85f),
                style = MaterialTheme.typography.displayLarge,
                fontSize = if (isCompact) 66.sp else 92.sp,
                fontWeight = FontWeight.Thin,
                textAlign = TextAlign.Center,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                text = meridiem,
                color = WhiteText100.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = if (isCompact) 46.sp else 66.sp,
                fontWeight = FontWeight.Thin,
                modifier = Modifier.alignByBaseline()
            )
        }



        Text(  // 정해진 시간마다 시간별 상태 텍스트를 가져온다, refreshTrigger 는 리셋버튼을 눌렀을 때 즉시 텍스트를 다른 값으로 바꾸기 위함이다.
            text = rememberCurrentTimeStatusText(
                refreshIntervalMillis = AOD_BACKGROUND_CHANGE_INTERVAL_MILLIS,
                refreshTrigger = statusRefreshTrigger
            ),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.headlineSmall,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Thin,
            textAlign = TextAlign.Center
        )


    }
}


@Composable
private fun TimerControlsPanel(
    uiState: TimerUiState,
    isCompact: Boolean,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResetClick: () -> Unit,
    onChangeModeClick: () -> Unit,
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

    val spacing = layoutSpacing(isCompact = isCompact)

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
                    tint = WhiteText75,
                    modifier = Modifier.size(spacing.iconSize)
                )
                Text(
                    text = "Focus",
                    color = WhiteText75,
                    style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.iconSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = onResetClick,
                    iconSize = spacing.iconSize
                )
                CompactIconButton(
                    icon = Icons.Default.SwapHoriz,
                    onClick = onChangeModeClick,
                    iconSize = spacing.iconSize
                )
                /*
                CompactIconButton(
                    icon = Icons.Default.Route,
                    onClick = onNewPathClick
                )
                 */
            }
        }

        // 시간 텍스트
        Text(
            text = uiState.formattedTime,
            color = WhiteText100.copy(alpha = 0.85f),
            style = if (isCompact) { MaterialTheme.typography.titleLarge }
            else { MaterialTheme.typography.displayLarge },
            fontSize = if (isCompact) 66.sp else 92.sp,
            fontWeight = FontWeight.Thin,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (uiState.phase == TimerPhase.READY) "Ready for launch" else journeyText,
            color = Color.White.copy(alpha = 0.72f),
            fontWeight = FontWeight.Thin,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            fontStyle = FontStyle.Italic
        )

        Row(
            modifier = Modifier.fillMaxWidth(spacing.buttonWidthFraction),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.buttonSpacing)
        ) {
            DurationPill(
                minutes = uiState.focusMinutes,
                enabled = uiState.canEditDurations,
                isCompact = isCompact,
                onDecrease = onDecreaseFocus,
                onIncrease = onIncreaseFocus,
                modifier = Modifier.weight(1f)
            )
            LaunchButton(
                isRunning = uiState.isRunning,
                isCompact = isCompact,
                phase = uiState.phase,
                onClick = {
                    if (uiState.isRunning) onPauseClick() else onStartClick()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DurationPill(
    minutes: Int,
    enabled: Boolean,
    isCompact: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)
    val spacing = layoutSpacing(isCompact)
    Row(
        modifier = modifier
            .height(spacing.buttonHeight)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(glassPillColors(enabled)),
                shape = shape
            )
            .border(BorderStroke(Dp.Hairline, Color.White.copy(alpha = 0.18f)), shape)
            .padding(horizontal = spacing.buttonPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperIconButton(
            icon = Icons.Default.Remove,
            enabled = enabled,
            isCompact = isCompact,
            onClick = onDecrease
        )
        Text(
            text = "$minutes min",
            color = WhiteText85.copy(alpha = if (enabled) 1f else 0.53f),
            style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        StepperIconButton(
            icon = Icons.Default.Add,
            enabled = enabled,
            isCompact = isCompact,
            onClick = onIncrease
        )
    }
}

@Composable
private fun StepperIconButton(
    icon: ImageVector,
    isCompact: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val spacing = layoutSpacing(isCompact)
    Box(
        modifier = Modifier
            .size(34.dp)
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
            modifier = Modifier.size(spacing.iconSize)
        )
    }
}

@Composable
private fun LaunchButton(
    isRunning: Boolean,
    isCompact: Boolean,
    phase: TimerPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonShape = RoundedCornerShape(32.dp)
    val spacing = layoutSpacing(isCompact)

    ElevatedButton(
        onClick = onClick,
        modifier = modifier.height(spacing.buttonHeight),
        shape = buttonShape,
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp
        ),
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = glassPillColors(isRunning)
                    ),
                    shape = buttonShape
                )
                .border(BorderStroke(Dp.Hairline, Color.White.copy(alpha = 0.18f)), buttonShape)
                .padding(horizontal = spacing.buttonPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.RocketLaunch,
                contentDescription = null,
                tint = WhiteText85,
                modifier = Modifier.size(spacing.iconSize)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) { "PAUSE" }
                else if (phase == TimerPhase.READY) "LAUNCH"
                else { "RESUME" },
                color = WhiteText85,
                style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactIconButton(
    icon: ImageVector,
    iconSize: Dp,
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
            modifier = Modifier.size(iconSize)
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
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
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
            modeHeader = { onRefreshAodBg, onRefreshTimerBg, isCompact, statusRefreshTrigger ->
                TimerControlsPanel(
                    uiState = previewState,
                    onStartClick = {},
                    onPauseClick = {},
                    onResetClick = {},
                    onNewPathClick = {},
                    onIncreaseFocus = {},
                    onDecreaseFocus = {},
                    onChangeModeClick = {},
                    isCompact = true
                )
            },
            countdownOverlay = {},
            soundMixerPanel = {},
            bottomNavigation = {},
            fileShareOverlay = {},
            focusMode = MainActivity.TIMER_MODE
        )
    }
}
