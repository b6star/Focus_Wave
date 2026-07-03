package com.yourssu.focuswave.server

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FileServerManager(application: Application) : AndroidViewModel(application) {
    private var server: LocalFileServer? = null
    private val secureRandom = SecureRandom()

    private val _uiState = MutableStateFlow(FileShareUiState())
    val uiState: StateFlow<FileShareUiState> = _uiState.asStateFlow()

    fun startServer() {
        if (server != null) return

        val authCode = generateAuthCode()
        val application = getApplication<Application>()
        val newServer = LocalFileServer(
            appFilesDirectory = application.filesDir,
            authCode = authCode,
            homePage = loadHomePage(),
            onFilesChanged = ::onSharedFilesChanged
        )
        try {
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = newServer

            val serverAddress = findWifiServerAddress()
            Log.d(
                LOG_TAG,
                "server started: address=${serverAddress ?: "unavailable"}, " +
                    "port=${LocalFileServer.PORT}"
            )
            _uiState.value = FileShareUiState(
                isRunning = true,
                serverAddress = serverAddress,
                authCode = authCode,
                statusText = "로컬 공유 서버가 실행 중입니다.",
                addressHint = if (serverAddress == null) {
                    "같은 Wi-Fi에 연결해주세요."
                } else {
                    "PC 브라우저에서 아래 주소로 접속하세요."
                }
            )
        } catch (error: Exception) {
            newServer.stop()
            _uiState.value = FileShareUiState(
                errorMessage = error.localizedMessage ?: "서버를 시작하지 못했습니다."
            )
        }
    }

    private fun onSharedFilesChanged() {
        _uiState.update { state ->
            state.copy(filesRevision = state.filesRevision + 1L)
        }
        Log.d(LOG_TAG, "shared files changed")
    }

    fun stopServer() {
        Log.d(LOG_TAG, "server stopped")
        server?.stop()
        server = null
        _uiState.value = FileShareUiState()
    }

    override fun onCleared() {
        server?.stop()
        server = null
        super.onCleared()
    }

    private fun generateAuthCode(): String =
        (MIN_AUTH_CODE + secureRandom.nextInt(AUTH_CODE_RANGE)).toString()

    private fun loadHomePage(): String? = try {
        getApplication<Application>().assets
            .open(HOME_PAGE_ASSET)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun findWifiServerAddress(): String? {
        val connectivityManager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        val address = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?: return null

        return "http://${address}:${LocalFileServer.PORT}"
    }

    companion object {
        private const val LOG_TAG = "FileShare"
        private const val MIN_AUTH_CODE = 1000
        private const val AUTH_CODE_RANGE = 9000
        private const val HOME_PAGE_ASSET = "index.html"
    }
}
