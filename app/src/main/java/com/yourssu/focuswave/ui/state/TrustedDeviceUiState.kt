package com.yourssu.focuswave.ui.state

import com.yourssu.focuswave.server.data.TrustedDeviceEntity
import com.yourssu.focuswave.server.model.SharedSourceIdentity

data class TrustedDeviceUiState(
    val pendingDevices: List<SharedSourceIdentity> = emptyList(),
    val namingDevice: SharedSourceIdentity? = null,
    val trustedDevices: List<TrustedDeviceEntity> = emptyList()
)
