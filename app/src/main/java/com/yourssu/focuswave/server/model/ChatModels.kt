package com.yourssu.focuswave.server.model

data class ChatMessage(
    val id: String,
    val sequence: Long,
    val senderId: String,
    val senderName: String,
    val senderIpAddress: String?,
    val senderUserAgent: String?,
    val plainText: String,
    val sentAtMillis: Long
)
