package com.yourssu.focuswave.ui.state

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList()
)

data class ChatMessageUi(
    val id: String,
    val sequence: Long,
    val senderName: String,
    val senderIpAddress: String?,
    val senderUserAgent: String?,
    val text: String,
    val sentAtMillis: Long,
    val isMine: Boolean
)
