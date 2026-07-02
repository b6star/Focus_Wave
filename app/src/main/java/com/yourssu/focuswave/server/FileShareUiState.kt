package com.yourssu.focuswave.server

data class FileShareUiState(
    val isRunning: Boolean = false,
    val serverAddress: String? = null,
    val authCode: String? = null,
    val statusText: String = "로컬 공유가 중지되었습니다.",
    val addressHint: String = "서버를 시작하면 접속 주소가 표시됩니다.",
    val errorMessage: String? = null
)
