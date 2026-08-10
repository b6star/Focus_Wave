package com.yourssu.focuswave.ui.fileshare

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourssu.focuswave.server.FileServerManager
import com.yourssu.focuswave.server.FileShareUiState
import com.yourssu.focuswave.server.LocalFileServer
import com.yourssu.focuswave.server.SharedSourceIdentity
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class SharedFileUi(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val uriString: String?,
    val lastModified: Long? = null,
    val isSelected: Boolean = false
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileShareOverlay(
    uiState: FileShareUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fileServerManager: FileServerManager = viewModel()
    val trustedDeviceUiState by fileServerManager.trustedDeviceUiState.collectAsState()

    var receivedFiles by remember {
        mutableStateOf<List<SharedFileUi>>(emptyList())
    }

    var selectedFiles by remember {
        mutableStateOf<List<SharedFileUi>>(emptyList())
    }

    var fileMessage by remember {
        mutableStateOf<String?>(null)
    }

    var fileErrorMessage by remember {
        mutableStateOf<String?>(null)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        selectedFiles = uris.mapIndexed { index, uri ->
            uri.toSharedFileUi(
                context = context,
                id = index.toString()
            )
        }
    }

    var sendProgressMap by remember {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }

    val clipboardManager = LocalClipboardManager.current
    val pcAccessUrl = uiState.serverAddress.orEmpty()

    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val screenScrollState = rememberScrollState()

    fun refreshSharedFiles(showMessage: Boolean = true) {
        coroutineScope.launch {
            fileErrorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    context.loadSharedFiles()
                }
            }.onSuccess { files ->
                receivedFiles = files
                if (showMessage) {
                    fileMessage = "공유 파일 ${files.size}개를 불러왔습니다."
                }
            }.onFailure { error ->
                fileMessage = null
                fileErrorMessage = error.localizedMessage ?: "파일 목록을 불러오지 못했습니다."
            }
        }
    }

    fun saveReceivedFileToDownloads(file: SharedFileUi) {
        Log.d(FILE_SHARE_LOG_TAG, "download button clicked: name=${file.name}")
        coroutineScope.launch {
            fileErrorMessage = null
            val result = context.saveSharedFileToDownloads(file.name)
            if (result.isSuccess) {
                fileMessage = result.message
            } else {
                fileMessage = null
                fileErrorMessage = result.message
            }
        }
    }

    LaunchedEffect(uiState.filesRevision) {
        refreshSharedFiles(showMessage = false)
    }

    var previewFile by remember { mutableStateOf<SharedFileUi?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(screenScrollState)
                .padding(
                    start = 18.dp,
                    top = 18.dp,
                    end = 18.dp,
                    bottom = 48.dp
                )
                .background(Color(0xFF1E1E2E), RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderSection(onDismiss = onDismiss)

            FileShareServerCard(
                uiState = uiState,
                pcAccessUrl = pcAccessUrl,
                onStartClick = onStartClick,
                onStopClick = {
                    onStopClick()
                    selectedFiles = emptyList()
                },
                onRefreshClick = fileServerManager::regenerateAuthCode
            )


            //File Sharing panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FileShareTabButton(
                        text = "파일 다운로드",
                        selected = pagerState.currentPage == 0,
                        selectedColor = Color(0xFF8A86E6),
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    FileShareTabButton(
                        text = "파일 업로드",
                        selected = pagerState.currentPage == 1,
                        selectedColor = Color(0xFFE68A86),
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                fileMessage?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFF8BE9A8),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                fileErrorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 230.dp, max = 480.dp)
                ) { page ->
                    when (page) {
                        0 -> DownloadSection(
                            receivedFiles = receivedFiles,
                            onSaveClick = { file ->
                                saveReceivedFileToDownloads(file)
                            },
                            onPreviewClick = { file -> previewFile = file }
                        )

                        1 -> UploadSection(
                            selectedFiles = selectedFiles,
                            isServerRunning = uiState.isRunning,
                            sendProgressMap = sendProgressMap,
                            onPickFileClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onRemoveFileClick = { targetFile ->
                                selectedFiles = selectedFiles.filterNot { it.id == targetFile.id }
                                sendProgressMap = sendProgressMap - targetFile.id
                            },
                            onSendClick = {
                                coroutineScope.launch {
                                    fileErrorMessage = null
                                    selectedFiles.forEach { file ->
                                        sendProgressMap = sendProgressMap + (file.id to 0)
                                    }
                                    val filesToShare = selectedFiles
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            fileServerManager.shareFiles(
                                                filesToShare
                                            ) { fileId, percent ->
                                                coroutineScope.launch {
                                                    sendProgressMap =
                                                        sendProgressMap + (fileId to percent)
                                                }
                                            }
                                        }
                                    }.onSuccess {
                                        selectedFiles = emptyList()
                                        fileMessage =
                                            "${filesToShare.size}개 파일을 PC 다운로드 목록에 추가했습니다."
                                    }.onFailure { error ->
                                        fileErrorMessage =
                                            error.localizedMessage ?: "파일 공유 준비에 실패했습니다."
                                    }
                                }
                            }
                        )
                    }
                }
            }
            previewFile?.let { file ->
                FilePreviewDialog(
                    file = file,
                    onDismiss = { previewFile = null },
                    onSaveClick = { saveReceivedFileToDownloads(file) }
                )
            }

            trustedDeviceUiState.pendingDevices.firstOrNull()?.let { source ->
                TrustDevicePromptDialog(
                    source = source,
                    onConfirm = {
                        fileServerManager.startTrustedDeviceNaming(source)
                    },
                    onDismiss = {
                        fileServerManager.denyTrustedDevice(source)
                    }
                )
            }

            trustedDeviceUiState.namingDevice?.let { source ->
                NameTrustedDeviceDialog(
                    source = source,
                    onSkip = fileServerManager::skipTrustedDeviceName,
                    onConfirm = fileServerManager::confirmTrustedDeviceName
                )
            }

        }
    }
}

@Composable
private fun TrustDevicePromptDialog(
    source: SharedSourceIdentity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2F45), RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "신뢰 기기 추가",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = source.displayName,
                    color = Color(0xFFF2F1FF),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = source.ipAddress ?: "IP 정보를 확인할 수 없습니다.",
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "이 기기를 신뢰하면 다음 접속부터 인증 코드를 다시 입력하지 않아도 됩니다.",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("아니오")
                }
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8A86E6),
                        contentColor = Color.White
                    )
                ) {
                    Text("예")
                }
            }
        }
    }
}

@Composable
private fun NameTrustedDeviceDialog(
    source: SharedSourceIdentity,
    onSkip: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var deviceName by remember(source.sessionToken) {
        mutableStateOf(source.displayName)
    }

    Dialog(onDismissRequest = onSkip) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2F45), RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "기기 이름",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "이 기기에 이름을 부여하시겠습니까?",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                label = {
                    Text("기기 이름")
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF8A86E6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
                    focusedLabelColor = Color(0xFFB8B5FF),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.62f),
                    cursorColor = Color(0xFFB8B5FF)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onSkip) {
                    Text("건너뛰기")
                }
                Button(
                    onClick = { onConfirm(deviceName) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8A86E6),
                        contentColor = Color.White
                    )
                ) {
                    Text("확인")
                }
            }
        }
    }
}

@Composable
private fun FileShareTabButton(
    text: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                selectedColor
            } else {
                Color.Transparent
            },
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
@Composable
private fun HeaderSection(
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "🚀 Focus Wave File Sharing",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }

        TextButton(onClick = onDismiss) {
            Text("닫기")
        }
    }
}

@Composable
private fun FileShareServerCard(
    uiState: FileShareUiState,
    pcAccessUrl: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = uiState.statusText,
                    color = if (uiState.isRunning) Color(0xFF8BE9A8) else Color(0xFFFFD700),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = uiState.addressHint,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(
                onClick = {
                    if (uiState.isRunning) onStopClick() else onStartClick()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (uiState.isRunning) "서버 중지" else "서버 시작",
                    tint = if (uiState.isRunning) Color(0xFF8BE9A8).copy(alpha = 1f) else Color.White.copy(alpha = 0.5f)
                )
            }
        }

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PC 접속 주소",
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelMedium
            )

            IconButton(
                onClick = { isExpanded = !isExpanded }
            ) {
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        if (isExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionContainer(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (pcAccessUrl.isNullOrEmpty()) {
                            "서버를 시작하면 접속 주소가 표시됩니다."
                        } else {
                            pcAccessUrl
                        },
                        color = Color(0xFFB8B5FF),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 18.sp
                    )
                }

            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isRunning && uiState.authCode != null) {
                    Text(
                        text = uiState.authCode ,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFF2F1FF),
                        style = MaterialTheme.typography.headlineLarge,
                        letterSpacing = 4.sp
                    )

                    TextButton(
                        onClick = onRefreshClick,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("재발급")
                    }
                } else {
                    Text(
                        text = "서버 시작 후 표시됩니다.",
                        modifier = Modifier.weight(1f),
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text(
                text = "PC 브라우저에서 위 주소로 접속한 뒤 인증코드를 입력하세요.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DownloadSection(
    receivedFiles: List<SharedFileUi>,
    onSaveClick: (SharedFileUi) -> Unit,
    onPreviewClick: (SharedFileUi) -> Unit
) {
    SectionCard {

        Text(
            text = "💻 서버",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "서버에 있는 파일입니다.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )


        if (receivedFiles.isEmpty()) {
            EmptyText("아직 서버에 공유중인 파일이 없습니다.")
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(receivedFiles) { file ->
                    FileRow(
                        file = file,
                        showPreviewButton = file.isPreviewable(),
                        actionText = "저장",
                        onActionClick = { onSaveClick(file) },
                        onPreviewClick = { onPreviewClick(file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadSection(
    selectedFiles: List<SharedFileUi>,
    isServerRunning: Boolean,
    sendProgressMap: Map<String, Int>,
    onPickFileClick: () -> Unit,
    onRemoveFileClick: (SharedFileUi) -> Unit,
    onSendClick: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "📱 폰 → 💻 서버",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            if (!selectedFiles.isEmpty()) {
                Text(
                    text = "${selectedFiles.size} files",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

        }

        Text(
            text = "파일을 폰 서버에 공유하면 PC 웹에서 다운로드할 수 있습니다.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )


        Button(
            onClick = onPickFileClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE68A86),
                contentColor = Color.White
            )
        ) {
            Text("파일 선택하기")
        }

        if (selectedFiles.isEmpty()) {
            EmptyText("선택된 파일이 없습니다.")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedFiles) { file ->
                    FileRow(
                        file = file,
                        actionText = "삭제",
                        progress = sendProgressMap[file.id],
                        onActionClick = { onRemoveFileClick(file) }
                    )
                }
            }

            Button(
                onClick = onSendClick,
                enabled = isServerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8A86E6),
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "PC 다운로드 목록에 추가",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}


@Composable
private fun FileRow(
    file: SharedFileUi,
    showPreviewButton: Boolean = false,
    actionText: String? = null,
    progress: Int? = null,
    onActionClick: (() -> Unit)? = null,
    onPreviewClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.name,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true
                )

                Text(
                    text = buildString {
                        append(file.sizeBytes.toFileSizeText())
                        file.lastModified?.let { modifiedAt ->
                            append(" · ")
                            append(modifiedAt.toModifiedTimeText())
                        }
                    },
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showPreviewButton && onPreviewClick != null) {
                Text(
                    text = "열기",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable {
                            onPreviewClick()
                        }
                )
            }
            if (actionText != null && onActionClick != null) {
                Text(
                    text = actionText,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable {
                            onActionClick()
                        }
                )
            }
            progress?.let {
                Text(
                    text = if (it >= 100) "🟢" else "$it%",
                    color = Color(0xFFFFD700),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

    }
}

@Composable
private fun FilePreviewDialog(
    file: SharedFileUi,
    onDismiss: () -> Unit,
    onSaveClick: () -> Unit
) {
    val sourceFile = remember(file) {
        file.uriString?.let { uriString ->
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                uri.path?.let { path -> File(path) }
            } else {
                null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .background(Color(0xFF2A2F45), RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = file.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSaveClick) {
                    Text("저장")
                }
                TextButton(onClick = onDismiss) {
                    Text("닫기")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (sourceFile == null || !sourceFile.exists()) {
                    Text(
                        text = "파일을 찾을 수 없습니다.",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                } else {
                    FilePreviewContent(
                        file = sourceFile,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun FilePreviewContent(
    file: File,
    modifier: Modifier = Modifier
) {
    val extension = file.extension.lowercase()
    when (extension) {
        "jpg", "jpeg", "png", "webp" -> {
            val bitmap = remember(file) {
                BitmapFactory.decodeFile(file.absolutePath)
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("이미지를 불러올 수 없습니다.")
            }
        }
        "mp4", "mkv", "webm" -> {
            AndroidView(
                modifier = modifier,
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(Uri.fromFile(file))
                        setMediaController(MediaController(context))
                        start()
                    }
                }
            )
        }

        "mp3", "wav", "m4a", "aac" -> {
            AndroidView(
                modifier = modifier,
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(Uri.fromFile(file))
                        setMediaController(MediaController(context))
                        start()
                    }
                }
            )
        }

        "txt", "log", "csv", "json", "xml",
        "kt", "java", "kts",
        "c", "cpp", "cc", "h", "hpp",
        "py",
        "js", "ts",
        "html", "css",
        "md",
        "gradle", "properties",
        "yml", "yaml",
        "sql",
        "sh", "bat",
        "go", "rs", "swift",
        "php", "rb",
        "ini", "conf"
            -> {
            val text = remember(file) {
                runCatching {
                    file.readText()
                }.getOrElse {
                    "텍스트 파일을 읽을 수 없습니다."
                }
            }

            SelectionContainer {
                Text(
                    text = text,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = modifier.verticalScroll(rememberScrollState())
                )
            }
        }

        else -> {
            Text("이 파일 형식은 아직 앱 내부 미리보기를 지원하지 않습니다.")
        }
    }
}

@Composable
private fun EmptyText(
    text: String
) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

private fun Uri.toSharedFileUi(
    context: Context,
    id: String
): SharedFileUi {
    val contentResolver = context.contentResolver

    var fileName = "unknown"
    var sizeBytes = 0L
    val mimeType = contentResolver.getType(this)

    contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)

        if (cursor.moveToFirst()) {
            if (nameIndex >= 0) {
                fileName = cursor.getString(nameIndex) ?: "unknown"
            }

            if (sizeIndex >= 0) {
                sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }

    return SharedFileUi(
        id = id,
        name = fileName,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        uriString = this.toString()
    )
}

private fun Context.loadSharedFiles(): List<SharedFileUi> {
    val directory = LocalFileServer.sharedDirectory(applicationContext.filesDir)
    Log.d(FILE_SHARE_LOG_TAG, "app refresh directory: ${directory.absolutePath}")
    if (!directory.exists()) {
        Log.d(FILE_SHARE_LOG_TAG, "app refresh files found: count=0")
        return emptyList()
    }
    if (!directory.isDirectory) {
        throw IOException("공유 저장 경로가 폴더가 아닙니다.")
    }

    val files = directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        ?.map { file ->
            SharedFileUi(
                id = file.absolutePath,
                name = file.name,
                sizeBytes = file.length(),
                mimeType = null,
                uriString = file.toURI().toString(),
                lastModified = file.lastModified()
            )
        }
        ?.toList()
        ?: throw IOException("파일 목록을 읽지 못했습니다.")

    Log.d(FILE_SHARE_LOG_TAG, "app refresh files found: count=${files.size}")
    return files
}

private fun Context.stageFilesForPc(files: List<SharedFileUi>): Int {
    if (files.isEmpty()) {
        throw IOException("공유할 파일을 먼저 선택해주세요.")
    }

    val directory = LocalFileServer.sharedDirectory(applicationContext.filesDir)
    if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("공유 저장 폴더를 만들지 못했습니다.")
    }
    if (!directory.isDirectory) {
        throw IOException("공유 저장 경로가 폴더가 아닙니다.")
    }

    files.forEach { file ->
        val uri = file.uriString?.let(Uri::parse)
            ?: throw IOException("${file.name}: 파일 위치를 확인할 수 없습니다.")
        val safeName = sanitizeSharedFileName(file.name)
            ?: throw IOException("${file.name}: 파일명이 올바르지 않습니다.")
        val destination = resolveUniqueSharedFile(directory, safeName)

        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("${file.name}: 파일을 열 수 없습니다.")
            input.use { source ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > MAX_SHARED_FILE_SIZE_BYTES) {
                            throw IOException("${file.name}: 파일 크기 제한(50MB)을 초과했습니다.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!destination.isFile) {
                throw IOException("${file.name}: 파일 저장 실패")
            }
        } catch (error: IOException) {
            destination.delete()
            throw error
        }
    }

    return files.size
}

private fun sanitizeSharedFileName(rawName: String): String? {
    val leafName = rawName.replace('\\', '/').substringAfterLast('/')
    return Normalizer.normalize(leafName, Normalizer.Form.NFC)
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .replace(Regex("""[/:*?"<>|]"""), "_")
        .trim()
        .trim('.')
        .take(60)
        .ifBlank { null }
}

private fun resolveUniqueSharedFile(directory: File, fileName: String): File {
    val initial = File(directory, fileName)
    if (!initial.exists()) return initial

    val dotIndex = fileName.lastIndexOf('.')
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    var suffix = 1
    while (true) {
        val candidate = File(directory, "$baseName ($suffix)$extension")
        if (!candidate.exists()) return candidate
        suffix += 1
    }
}

private suspend fun Context.saveSharedFileToDownloads(
    fileName: String
): FileActionResult = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return@withContext FileActionResult(
            false,
            "Android 10 미만에서는 공용 Downloads 저장을 지원하지 않습니다."
        )
    }

    var insertedUri: Uri? = null
    try {
        val source = requireReadableSharedFile(fileName)
        val targetName = resolveUniqueDownloadName(source.name)
        Log.d(FILE_SHARE_LOG_TAG, "MediaStore save target: name=$targetName")
        val collection = MediaStore.Downloads.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
            put(MediaStore.MediaColumns.MIME_TYPE, source.name.toMimeType())
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        insertedUri = contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore 항목을 만들지 못했습니다.")

        val copiedBytes = contentResolver.openOutputStream(insertedUri, "w")?.use { output ->
            source.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: throw IOException("Downloads 출력 스트림을 열지 못했습니다.")
        if (copiedBytes != source.length()) {
            throw IOException("복사된 파일 크기가 원본과 다릅니다.")
        }

        val completed = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (contentResolver.update(insertedUri, completed, null, null) <= 0) {
            throw IOException("MediaStore 저장을 완료하지 못했습니다.")
        }
        Log.d(
            FILE_SHARE_LOG_TAG,
            "MediaStore save succeeded: uri=$insertedUri, " +
                    "name=$targetName, size=$copiedBytes"
        )
        FileActionResult(true, "Downloads 폴더에 $targetName 파일이 저장되었습니다.")
    } catch (error: SharedFileAccessException) {
        insertedUri?.let { contentResolver.delete(it, null, null) }
        Log.d(FILE_SHARE_LOG_TAG, "MediaStore save failed: ${error.message}")
        FileActionResult(false, error.message ?: "파일 저장 실패")
    } catch (error: SecurityException) {
        insertedUri?.let { contentResolver.delete(it, null, null) }
        Log.d(FILE_SHARE_LOG_TAG, "MediaStore permission failed: ${error.message}")
        FileActionResult(false, "권한 또는 저장소 오류로 파일을 저장하지 못했습니다.")
    } catch (error: Exception) {
        insertedUri?.let { contentResolver.delete(it, null, null) }
        Log.d(FILE_SHARE_LOG_TAG, "MediaStore save failed: ${error.message}")
        FileActionResult(false, "파일 저장 실패: ${error.message ?: "저장소 오류"}")
    }
}

private fun Context.requireReadableSharedFile(fileName: String): File {
    val directory = LocalFileServer.sharedDirectory(applicationContext.filesDir)
    val source = File(directory, fileName)
    Log.d(
        FILE_SHARE_LOG_TAG,
        "source file: path=${source.absolutePath}, exists=${source.exists()}, " +
                "length=${source.length()}, canRead=${source.canRead()}"
    )
    val validParent = runCatching {
        source.canonicalFile.parentFile == directory.canonicalFile
    }.getOrDefault(false)
    if (!validParent || !source.exists() || !source.isFile) {
        throw SharedFileAccessException("파일을 찾을 수 없습니다.")
    }
    if (!source.canRead()) {
        throw SharedFileAccessException("파일을 읽을 수 없습니다.")
    }
    return source
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun Context.resolveUniqueDownloadName(requestedName: String): String {
    val existingNames = mutableSetOf<String>()
    val collection = MediaStore.Downloads.getContentUri(
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    )
    contentResolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
        arrayOf("${Environment.DIRECTORY_DOWNLOADS}/"),
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            if (index >= 0) cursor.getString(index)?.let(existingNames::add)
        }
    }
    if (requestedName !in existingNames) return requestedName

    val dot = requestedName.lastIndexOf('.')
    val extension = if (dot > 0) requestedName.substring(dot) else ""
    val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
    var suffix = 1
    while ("$base ($suffix)$extension" in existingNames) suffix += 1
    return "$base ($suffix)$extension"
}

private fun String.toMimeType(): String {
    val extension = substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension)
        ?: if (extension in setOf("txt", "log", "md")) "text/plain"
        else "application/octet-stream"
}

private data class FileActionResult(
    val isSuccess: Boolean,
    val message: String
)

private class SharedFileAccessException(message: String) : FileNotFoundException(message)

private fun Long.toModifiedTimeText(): String =
    DateFormat.getDateInstance(DateFormat.SHORT)
        .format(Date(this))

private const val FILE_SHARE_LOG_TAG = "FileShare"
private const val MAX_SHARED_FILE_SIZE_BYTES = 50L * 1024 * 1024

fun Long.toFileSizeText(): String {
    return when {
        this >= 1024 * 1024 -> "%.1f MB".format(this / 1024.0 / 1024.0)
        this >= 1024 -> "%.1f KB".format(this / 1024.0)
        else -> "$this B"
    }
}


private fun SharedFileUi.isPreviewable(): Boolean {
    val lowerName = name.lowercase()

    return lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".mp4") ||
            lowerName.endsWith(".mkv") ||
            lowerName.endsWith(".webm") ||
            lowerName.endsWith(".mp3") ||
            lowerName.endsWith(".wav") ||
            lowerName.endsWith(".m4a") ||
            lowerName.endsWith(".aac") ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".log") ||
            lowerName.endsWith(".csv") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".kts") ||
            lowerName.endsWith(".c") ||
            lowerName.endsWith(".cpp") ||
            lowerName.endsWith(".cc") ||
            lowerName.endsWith(".h") ||
            lowerName.endsWith(".hpp") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts") ||
            lowerName.endsWith(".html") ||
            lowerName.endsWith(".css") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".gradle") ||
            lowerName.endsWith(".properties") ||
            lowerName.endsWith(".yml") ||
            lowerName.endsWith(".yaml") ||
            lowerName.endsWith(".sql") ||
            lowerName.endsWith(".sh") ||
            lowerName.endsWith(".bat") ||
            lowerName.endsWith(".go") ||
            lowerName.endsWith(".rs") ||
            lowerName.endsWith(".swift") ||
            lowerName.endsWith(".php") ||
            lowerName.endsWith(".rb") ||
            lowerName.endsWith(".ini") ||
            lowerName.endsWith(".conf")
}
