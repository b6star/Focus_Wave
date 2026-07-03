package com.yourssu.focuswave.ui.fileshare

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourssu.focuswave.server.FileServerManager
import com.yourssu.focuswave.server.FileShareUiState
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File


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

    var fileToSave by remember { mutableStateOf<SharedFileUi?>(null) }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri ->
        if (destinationUri != null && fileToSave != null) {
            fileServerManager.saveUploadedFileToUri(fileToSave!!, destinationUri)
        }
    }

    var selectedFiles by remember {
        mutableStateOf<List<SharedFileUi>>(emptyList())
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

    var previewFile by remember { mutableStateOf<SharedFileUi?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
                .background(Color(0xFF1E1E2E), RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderSection(onDismiss = onDismiss)

            ConnectionStatusCard(
                uiState = uiState,
                onStartClick = onStartClick,
                onStopClick = {
                    onStopClick()
                    selectedFiles = emptyList()
                }
            )

            PcAccessInfoCard(
                uiState = uiState,
                pcAccessUrl = pcAccessUrl,
                onCopyClick = {
                    clipboardManager.setText(AnnotatedString(pcAccessUrl))
                },
                onRefreshClick = {
                    fileServerManager.regenerateAuthCode()
                }

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
                        text = "파일 받기",
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
                        text = "파일 보내기",
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

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 230.dp, max = 480.dp)
                ) { page ->
                    when (page) {
                        0 -> PcToPhoneSection(
                            receivedFiles = uiState.uploadedFiles.reversed(),
                            onPreviewClick = { file ->
                                previewFile = file
                            },
                            onSaveClick = { file ->
                                fileToSave = file
                                saveFileLauncher.launch(file.name)
                            }
                        )

                        1 -> PhoneToPcSection(
                            selectedFiles = selectedFiles,
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
                                    selectedFiles.forEach { file ->
                                        sendProgressMap = sendProgressMap + (file.id to 0)
                                    }

                                    fileServerManager.shareFiles(selectedFiles) { fileId, percent ->
                                        sendProgressMap = sendProgressMap + (fileId to percent)
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
                    onSaveClick = { // 미리보기 창에서 다운로드 버튼을 눌렀을 때 다운
                            fileToSave = file
                            saveFileLauncher.launch(file.name)
                    }
                )
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
private fun ConnectionStatusCard(
    uiState: FileShareUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = uiState.statusText,
            color = if (uiState.isRunning) Color(0xFF8BE9A8) else Color(0xFFFFD700),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = uiState.addressHint,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStartClick,
                enabled = !uiState.isRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("서버 시작")
            }
            Button(
                onClick = onStopClick,
                enabled = uiState.isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E3B46))
            ) {
                Text("서버 중지")
            }
        }
        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PcToPhoneSection(
    receivedFiles: List<SharedFileUi>,
    onSaveClick: (SharedFileUi) -> Unit,
    onPreviewClick: (SharedFileUi) -> Unit
) {
    SectionCard {

        Text(
            text = "💻 PC → 📱 폰",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "PC 웹페이지에서 폰으로 보낸 파일입니다.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )

        if (receivedFiles.isEmpty()) {
            EmptyText("아직 PC에서 받은 파일이 없습니다.")
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
private fun PhoneToPcSection(
    selectedFiles: List<SharedFileUi>,
    sendProgressMap: Map<String, Int>,
    onPickFileClick: () -> Unit,
    onRemoveFileClick: (SharedFileUi) -> Unit,
    onSendClick: () -> Unit
) {
    SectionCard {

        Text(
            text = "📱 폰 → 💻 PC",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "PC로 보낼 파일을 선택합니다.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
            if (!selectedFiles.isEmpty()) {
                Text(
                    text = "${selectedFiles.size} files",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

        }


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
                        onActionClick = { onRemoveFileClick(file) },
                        onPreviewClick = {}
                    )
                }
            }

            Button(
                onClick = onSendClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8A86E6),
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "PC로 전송",
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
private fun PcAccessInfoCard(
    uiState: FileShareUiState,
    pcAccessUrl: String,
    onCopyClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    var isCodeVisible by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = "PC 접속 정보",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (pcAccessUrl.isBlank()) {
                    "서버를 시작하면 접속 주소가 표시됩니다."
                } else {
                    pcAccessUrl
                },
                modifier = Modifier.weight(1f),
                color = Color(0xFF8A86E6),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(
                onClick = onCopyClick,
                enabled = pcAccessUrl.isNotBlank()
            ) {
                Text("복사")
            }
        }


        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "인증 코드",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (isCodeVisible) uiState.authCode.toString() else "****",
                color = Color(0xFFFFD700),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = if (isCodeVisible)
                    Icons.Default.VisibilityOff
                else
                    Icons.Default.Visibility,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        isCodeVisible = !isCodeVisible
                    }
            )

            Spacer(modifier = Modifier.width(48.dp))
            Text(
                text = "재발급",
                color = Color(0xFF8A86E6),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable {
                    onRefreshClick()
                }

            )
        }




        Text(
            text = "PC 브라우저에서 같은 Wi-Fi의 위 주소로 접속하세요.",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FileRow(
    file: SharedFileUi,
    showPreviewButton: Boolean = false,
    actionText: String,
    progress: Int? = null,
    onActionClick: () -> Unit,
    onPreviewClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = file.sizeBytes.toFileSizeText(),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        progress?.let {
            Text(
                text = if (it >= 100) "완료" else "$it%",
                color = Color(0xFFFFD700),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        if (showPreviewButton && onPreviewClick != null) {
            TextButton(onClick = onPreviewClick) {
                Text("열기")
            }
        }

        TextButton(onClick = onActionClick) {
            Text(actionText)
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
    context: android.content.Context,
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

