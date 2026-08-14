package com.yourssu.focuswave.ui.state

import com.yourssu.focuswave.ui.fileshare.SharedFileUi

data class FileShareUiState(
    val filesRevision: Long = 0L,
    val uploadedFiles: List<SharedFileUi> = emptyList()
)
