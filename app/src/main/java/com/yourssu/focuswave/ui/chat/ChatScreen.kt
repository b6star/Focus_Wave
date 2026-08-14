package com.yourssu.focuswave.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourssu.focuswave.server.FileServerManager
import com.yourssu.focuswave.ui.state.ChatMessageUi
import com.yourssu.focuswave.ui.state.ServerUiState
import com.yourssu.focuswave.ui.theme.WhiteText85
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatOverlay(
    serverUiState: ServerUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onConnectionInfoToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileServerManager: FileServerManager = viewModel()
    val chatUiState by fileServerManager.chatUiState.collectAsState()
    val pcAccessUrl = serverUiState.serverAddress.orEmpty()
    var draft by remember { mutableStateOf("") }
    var detailMessage by remember { mutableStateOf<ChatMessageUi?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 48.dp)
                .background(Color(0xFF1E1E2E), RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChatHeader(onDismiss = onDismiss)

            ChatServerPanel(
                uiState = serverUiState,
                pcAccessUrl = pcAccessUrl,
                onStartClick = onStartClick,
                onStopClick = onStopClick,
                onConnectionInfoToggle = onConnectionInfoToggle,
                onRefreshClick = fileServerManager::regenerateAuthCode
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 320.dp, max = 520.dp)
                    .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chatUiState.messages.isEmpty()) {
                    item {
                        ChatBubble(
                            message = ChatMessageUi(
                                id = "empty",
                                sequence = 0L,
                                senderName = "Focus Wave",
                                senderIpAddress = null,
                                senderUserAgent = null,
                                text = "채팅 연결 준비 중입니다.",
                                sentAtMillis = 0L,
                                isMine = false
                            ),
                            onClick = {}
                        )
                    }
                }

                items(chatUiState.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        onClick = { detailMessage = message }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("메시지")
                    },
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WhiteText85,
                        unfocusedTextColor = WhiteText85,
                        focusedBorderColor = Color(0xFF8A86E6),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                        cursorColor = Color(0xFFB8B5FF),
                        focusedPlaceholderColor = Color.White.copy(alpha = 0.46f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.46f),
                        focusedContainerColor = Color.White.copy(alpha = 0.08f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.08f)
                    )
                )

                ElevatedButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            fileServerManager.sendChatMessage(text)
                            draft = ""
                        }
                    },
                    enabled = serverUiState.isRunning && draft.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = Color(0xFF8A86E6),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.1f),
                        disabledContentColor = Color.White.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "보내기",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    detailMessage?.let { message ->
        ChatMessageDetailDialog(
            message = message,
            onDismiss = { detailMessage = null }
        )
    }
}

@Composable
private fun ChatHeader(
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Focus Wave Chat",
                color = WhiteText85,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "같은 네트워크에서 주고받는 임시 채팅",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "닫기",
                tint = Color.White.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
private fun ChatServerPanel(
    uiState: ServerUiState,
    pcAccessUrl: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onConnectionInfoToggle: () -> Unit,
    onRefreshClick: () -> Unit
) {
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
                    tint = if (uiState.isRunning) Color(0xFF8BE9A8) else Color.White.copy(alpha = 0.5f)
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
                onClick = onConnectionInfoToggle
            ) {
                Icon(
                    imageVector = if (uiState.isConnectionInfoExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (uiState.isConnectionInfoExpanded) "접기" else "펼치기",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        if (uiState.isConnectionInfoExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionContainer(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (pcAccessUrl.isBlank()) {
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
                        text = uiState.authCode,
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
                text = "PC 브라우저에서 위 주소로 접속한 뒤 인증 코드를 입력하세요.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessageUi,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!message.isMine) {
                Text(
                    text = message.senderName,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Surface(
                modifier = Modifier.clickable(onClick = onClick),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (message.isMine) 18.dp else 4.dp,
                    bottomEnd = if (message.isMine) 4.dp else 18.dp
                ),
                color = if (message.isMine) Color(0xFF8A86E6) else Color.White.copy(alpha = 0.12f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = WhiteText85,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ChatMessageDetailDialog(
    message: ChatMessageUi,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        },
        title = {
            Text("메시지 정보")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("이름: ${message.senderName}")
                Text("IP: ${message.senderIpAddress ?: "-"}")
                Text("환경: ${message.senderUserAgent ?: "-"}")
                Text("순서: ${message.sequence}")
                Text("서버 수신 시간: ${message.sentAtMillis.toReadableTime()}")
            }
        }
    )
}

private fun Long.toReadableTime(): String {
    if (this <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd h:mm:ss a", Locale.US).format(Date(this))
}
