package com.yourssu.focuswave.server

import com.yourssu.focuswave.ui.fileshare.SharedFileUi

data class FileShareUiState(
    val isRunning: Boolean = false,
    val serverAddress: String? = null,
    val authCode: String? = null,
    val statusText: String = "로컬 공유 서버가 중지되어 있습니다.",
    val addressHint: String = "",
    val errorMessage: String? = null,
    val uploadedFiles: List<SharedFileUi> = emptyList()
)