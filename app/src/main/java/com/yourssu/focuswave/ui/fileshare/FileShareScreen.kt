package com.yourssu.focuswave.ui.fileshare

import android.net.Uri
import android.net.wifi.WifiManager
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
import java.net.InetAddress
import java.nio.ByteOrder
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import kotlin.random.Random


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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 다른 사람의 접근을 막는 일회성 인증 코드
    // TODO : 백엔드 서버에서 코드 생성, 검증, ui에 전달하는 로직으로 변경
    var connectionCode by remember {
        mutableStateOf(generateConnectionCode())
    }

    var receivedFiles by remember {
        mutableStateOf<List<SharedFileUi>>(emptyList())
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

    val clipboardManager = LocalClipboardManager.current
    val serverPort = 8080 // TODO: 백엔드 팀원이 실제 포트로 바꾸기
    val phoneIpAddress = remember { context.getPhoneIpAddress() }
    val pcAccessUrl = remember(phoneIpAddress, serverPort) {
        if (phoneIpAddress.isBlank()) "" else "http://$phoneIpAddress:$serverPort"
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

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

            ConnectionStatusCard()

            PcAccessInfoCard(
                pcAccessUrl = pcAccessUrl,
                connectionCode = connectionCode,
                onCopyClick = {
                    clipboardManager.setText(AnnotatedString(pcAccessUrl))
                },
                onRegenerateCodeClick = {
                    connectionCode = generateConnectionCode()
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
                            receivedFiles = receivedFiles,
                            onRefreshClick = {
                                // TODO
                            },
                            onSaveClick = { file ->
                                // TODO
                            }
                        )

                        1 -> PhoneToPcSection(
                            selectedFiles = selectedFiles,
                            onPickFileClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onRemoveFileClick = { targetFile ->
                                selectedFiles = selectedFiles.filterNot { it.id == targetFile.id }
                            },
                            onSendClick = {
                                // TODO
                            }
                        )
                    }
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
private fun ConnectionStatusCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🟡 연결 확인 대기 중",
            color = Color(0xFFFFD700),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PcToPhoneSection(
    receivedFiles: List<SharedFileUi>,
    onRefreshClick: () -> Unit,
    onSaveClick: (SharedFileUi) -> Unit
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

        Button(
            onClick = onRefreshClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF8A86E6),
                contentColor = Color.White
            )
        ) {
            Text("받은 파일 새로고침")
        }

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
                        actionText = "저장",
                        onActionClick = { onSaveClick(file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneToPcSection(
    selectedFiles: List<SharedFileUi>,
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
                        onActionClick = { onRemoveFileClick(file) }
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
    pcAccessUrl: String,
    connectionCode: String,
    onCopyClick: () -> Unit,
    onRegenerateCodeClick: () -> Unit
) {
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

        Text(
            text = if (pcAccessUrl.isBlank()) {
                "Wi-Fi 연결을 확인해주세요."
            } else {
                pcAccessUrl
            },
            color = Color(0xFF8A86E6),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "인증 코드",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = connectionCode,
                color = Color(0xFFFFD700),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "재발급",
                color = Color(0xFF8A86E6),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable {
                    onRegenerateCodeClick()
                }

            )
        }

        Text(
            text = "PC 브라우저에서 주소로 접속한 뒤 위 4자리 코드를 입력하세요.",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FileRow(
    file: SharedFileUi,
    actionText: String,
    onActionClick: () -> Unit
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

        TextButton(onClick = onActionClick) {
            Text(actionText)
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

private fun android.content.Context.getPhoneIpAddress(): String {
    val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
    val ipAddress = wifiManager.connectionInfo.ipAddress

    if (ipAddress == 0) return ""

    val fixedIpAddress = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
        Integer.reverseBytes(ipAddress)
    } else {
        ipAddress
    }

    return InetAddress.getByAddress(
        java.math.BigInteger.valueOf(fixedIpAddress.toLong()).toByteArray()
    ).hostAddress ?: ""
}


// TODO : 백엔드 서버에서 코드 재발급요청하는 것으로 변경
private fun generateConnectionCode(): String {
    return Random.nextInt(1000, 10000).toString()
}