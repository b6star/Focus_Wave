package com.yourssu.focuswave.server

data class TrustedDeviceUiState(
    val pendingDevices: List<SharedSourceIdentity> = emptyList(),
    val namingDevice: SharedSourceIdentity? = null,
    val trustedDevices: List<TrustedDeviceEntity> = emptyList()
)