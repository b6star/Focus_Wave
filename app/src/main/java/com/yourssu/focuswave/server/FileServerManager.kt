package com.yourssu.focuswave.server

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.yourssu.focuswave.server.data.TrustedDeviceRepository
import com.yourssu.focuswave.server.model.SharedSourceIdentity
import com.yourssu.focuswave.server.model.SharedSourceKind
import com.yourssu.focuswave.server.model.SharedSourceUi
import com.yourssu.focuswave.ui.fileshare.SharedFileUi
import com.yourssu.focuswave.ui.state.ChatMessageUi
import com.yourssu.focuswave.ui.state.ChatUiState
import com.yourssu.focuswave.ui.state.FileShareUiState
import com.yourssu.focuswave.ui.state.ServerUiState
import com.yourssu.focuswave.ui.state.TrustedDeviceUiState
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.NetworkInterface
import java.text.Normalizer

class FileServerManager(application: Application) : AndroidViewModel(application) {
    private var server: LocalServer? = null
    private val secureRandom = SecureRandom()

    private val _serverUiState = MutableStateFlow(ServerUiState())
    val serverUiState: StateFlow<ServerUiState> = _serverUiState.asStateFlow()
    private val _fileShareUiState = MutableStateFlow(FileShareUiState())
    val fileShareUiState: StateFlow<FileShareUiState> = _fileShareUiState.asStateFlow()
    private val _chatUiState = MutableStateFlow(ChatUiState())
    val chatUiState: StateFlow<ChatUiState> = _chatUiState.asStateFlow()
    private val trustedDeviceRepository =
        TrustedDeviceRepository.getInstance(application)
    private val _trustedDeviceUiState = MutableStateFlow(TrustedDeviceUiState())
    val trustedDeviceUiState: StateFlow<TrustedDeviceUiState> =
        _trustedDeviceUiState.asStateFlow()

    fun startServer() {
        if (server != null) return

        clearFileShareRecords()

        val authCode = generateAuthCode()
        val application = getApplication<Application>()
        val newServer = LocalServer(
            appFilesDirectory = application.filesDir,
            authCode = authCode,
            homePage = loadHomePage(),
            onFilesChanged = ::onSharedFilesChanged,
            onChatMessagesChanged = ::onChatMessagesChanged,
            findTrustedDevice = { trustedToken, ipAddress, userAgent ->
                kotlinx.coroutines.runBlocking {
                    trustedDeviceRepository.findTrustedDeviceByToken(
                        trustedToken = trustedToken,
                        ipAddress = ipAddress,
                        userAgent = userAgent
                    )
                }
            },
            onUntrustedClientAuthenticated = ::onUntrustedClientAuthenticated
        )
        try {
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = newServer

            logNetworkInterfaces()

            val serverAddress = findWifiServerAddress()

            _serverUiState.value = ServerUiState(
                isRunning = true,
                serverAddress = serverAddress,
                authCode = authCode,
                statusText = "로컬 공유 서버가 실행 중입니다.",
                addressHint = if (serverAddress == null) {
                    "같은 Wi-Fi에 연결해주세요."
                } else {
                    "PC 브라우저에서 아래 주소로 접속하세요."
                },
                isConnectionInfoExpanded = _serverUiState.value.isConnectionInfoExpanded
            )
            _fileShareUiState.value = FileShareUiState(uploadedFiles = getUploadedFiles())
        } catch (error: Exception) {
            newServer.stop()
            _serverUiState.value = ServerUiState(
                errorMessage = error.localizedMessage ?: "서버를 시작하지 못했습니다.",
                isConnectionInfoExpanded = _serverUiState.value.isConnectionInfoExpanded
            )
        }
    }



    private fun logNetworkInterfaces() {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .forEach { networkInterface ->
                networkInterface.inetAddresses
                    .asSequence()
                    .forEach { address ->
                        Log.d(
                            LOG_TAG,
                            "interface=${networkInterface.name}, " +
                                    "up=${networkInterface.isUp}, " +
                                    "loopback=${networkInterface.isLoopback}, " +
                                    "address=${address.hostAddress}"
                        )
                    }
            }
    }
    private fun onSharedFilesChanged() {
        _fileShareUiState.update { state ->
            state.copy(
                filesRevision = state.filesRevision + 1L,
                uploadedFiles = getUploadedFiles()
            )
        }
        Log.d(LOG_TAG, "shared files changed")
    }

    fun stopServer() {
        Log.d(LOG_TAG, "server stopped")
        server?.stop()
        server = null
        clearFileShareRecords()
        _serverUiState.value = ServerUiState(
            isConnectionInfoExpanded = _serverUiState.value.isConnectionInfoExpanded
        )
        _fileShareUiState.value = FileShareUiState()
        _chatUiState.value = ChatUiState()
    }


    fun getUploadedFiles(): List<SharedFileUi> {
        val directory = LocalServer.sharedDirectory(getApplication<Application>().filesDir)

        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }

        return directory
            .listFiles()
            ?.filter { it.isFile }
            ?.map { file ->
                SharedFileUi(
                    id = file.name,
                    name = file.name,
                    sizeBytes = file.length(),
                    mimeType = null,
                    uriString = file.toURI().toString(),
                    lastModified = file.lastModified()
                )
            } ?: emptyList()
    }

    // 폰 내부 파일을 서버 shared_files 에 복사
    fun shareFiles(
        files: List<SharedFileUi>,
        onProgress: (fileId: String, percent: Int) -> Unit
    ) {
        val application = getApplication<Application>()
        val directory = getOrCreateSharedDirectory()

        files.forEach { file ->
            val uriString = file.uriString ?: return@forEach
            val sourceUri = Uri.parse(uriString)

            val safeFileName = sanitizeFileName(file.name)
                ?: return@forEach

            val destination = resolveUniqueFile(directory, safeFileName)

            val totalBytes = file.sizeBytes.takeIf { it > 0 }
                ?: application.contentResolver.openFileDescriptor(sourceUri, "r")?.use {
                    it.statSize
                }
                ?: -1L

            var copiedBytes = 0L
            val buffer = ByteArray(1024 * 1024)

            application.contentResolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output ->
                    while (true) {
                        val readBytes = input.read(buffer)
                        if (readBytes == -1) break

                        output.write(buffer, 0, readBytes)
                        copiedBytes += readBytes

                        if (totalBytes > 0) {
                            val percent = ((copiedBytes * 100) / totalBytes)
                                .toInt()
                                .coerceIn(0, 100)

                            onProgress(file.id, percent)
                        }
                    }

                    output.flush()
                }
            }

            onProgress(file.id, 100)
        }
        onSharedFilesChanged()
    }

    private fun getOrCreateSharedDirectory(): File {
        val directory = File(getApplication<Application>().filesDir, SHARED_DIRECTORY_NAME)

        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Shared storage path is not a directory")
            }
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create shared storage directory")
        }

        return directory
    }

    private fun sanitizeFileName(rawFileName: String): String? {
        val leafName = rawFileName
            .replace('\\', '/')
            .substringAfterLast('/')

        val normalizedName = Normalizer.normalize(leafName, Normalizer.Form.NFC)

        val cleanedName = normalizedName
            .replace(CONTROL_CHARACTERS, "")
            .replace(UNSAFE_FILE_NAME_CHARACTERS, "_")
            .trim()
            .trim('.')

        if (cleanedName.isBlank()) return null

        val dotIndex = cleanedName.lastIndexOf('.')

        val extension = if (dotIndex > 0) {
            cleanedName.substring(dotIndex)
        } else {
            ""
        }

        val baseName = if (dotIndex > 0) {
            cleanedName.substring(0, dotIndex)
        } else {
            cleanedName
        }

        val maxBaseLength = (MAX_FILE_NAME_CHARACTERS - extension.length)
            .coerceAtLeast(1)

        val safeBaseName = baseName
            .take(maxBaseLength)
            .trimEnd()
            .trimEnd { Character.isHighSurrogate(it) }

        return (safeBaseName + extension).ifBlank { null }
    }

    private fun resolveUniqueFile(directory: File, safeFileName: String): File {
        val initialFile = File(directory, safeFileName)

        if (!initialFile.exists()) {
            return initialFile
        }

        val dotIndex = safeFileName.lastIndexOf('.')
        val extension = if (dotIndex > 0) safeFileName.substring(dotIndex) else ""
        val baseName = if (dotIndex > 0) safeFileName.substring(0, dotIndex) else safeFileName

        var suffixNumber = 1

        while (true) {
            val candidate = File(directory, "$baseName ($suffixNumber)$extension")

            if (!candidate.exists()) {
                return candidate
            }

            suffixNumber++
        }
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
        findReachablePrivateIpv4Address()
            ?.let { return "http://${it}:${LocalServer.PORT}" }
        findReachableGlobalIpv6Address()
            ?.let { return "http://[${it}]:${LocalServer.PORT}" }
        return null
    }


    private fun findReachablePrivateIpv4Address(): String? {
        return NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter {
                it.isUp && !it.isLoopback
            }.flatMap { networkInterface ->
                networkInterface.inetAddresses
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !
                    it.isLoopbackAddress }
                    .map { address ->
                        networkInterface.name to address.hostAddress }
            }
            .filter { (_, address) ->
                address.isPrivateIpv4()
            }.sortedBy { (interfaceName, _) ->
                when {
                    interfaceName.startsWith("wlan") -> 0
                            interfaceName.startsWith("ap") -> 1
                            interfaceName.startsWith("swlan") -> 2
                            interfaceName.startsWith("rndis") -> 3
                        else -> 10
                }
            }
            .firstOrNull()
            ?.second
    }

    private fun findReachableGlobalIpv6Address(): String? {
        return NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { it.name.startsWith("dummy") }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet6Address>()
                    .filter { !it.isLoopbackAddress }
                    .filter { !it.isLinkLocalAddress }
                    .map { it.hostAddress.substringBefore('%') }
            }
            .firstOrNull()
    }

    private fun String.isPrivateIpv4(): Boolean
            = startsWith("10.") ||
            startsWith("192.168.") ||
            Regex("""^172\.(1[6-9]|2\d|3[0-1])\.""").containsMatchIn(this)


    fun saveUploadedFileToUri(file: SharedFileUi, destinationUri: Uri) {
        val application = getApplication<Application>()

        val sourceFile = File(LocalServer.sharedDirectory(application.filesDir), file.name)

        if (!sourceFile.exists()) return

        application.contentResolver.openOutputStream(destinationUri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    fun sendChatMessage(message: String) {
        server?.postHostChatMessage(message)
    }

    private fun onChatMessagesChanged() {
        val messages = server
            ?.getChatMessages()
            ?.map { message ->
                ChatMessageUi(
                    id = message.id,
                    sequence = message.sequence,
                    senderName = message.senderName,
                    senderIpAddress = message.senderIpAddress,
                    senderUserAgent = message.senderUserAgent,
                    text = message.plainText,
                    sentAtMillis = message.sentAtMillis,
                    isMine = message.senderId == ChatRoutes.HOST_SENDER_ID
                )
            }
            .orEmpty()

        _chatUiState.value = ChatUiState(messages = messages)
    }

    private fun onUntrustedClientAuthenticated(source: SharedSourceIdentity) {
        _trustedDeviceUiState.update { state ->
            if (state.pendingDevices.any { it.sessionToken == source.sessionToken }) {
                state
            } else {
                val updatedPendingDevices = state.pendingDevices.toMutableList()
                updatedPendingDevices.add(source)

                state.copy(
                    pendingDevices = updatedPendingDevices
                )
            }
        }
    }

    fun denyTrustedDevice(source: SharedSourceIdentity) {
        _trustedDeviceUiState.update { state ->
            val updatedPendingDevices = state.pendingDevices.toMutableList()
            updatedPendingDevices.removeAll {
                it.sessionToken == source.sessionToken
            }
            state.copy(
                pendingDevices = updatedPendingDevices
            )
        }
    }

    fun startTrustedDeviceNaming(source: SharedSourceIdentity) {
        _trustedDeviceUiState.update { state ->
            val updatedPendingDevices = state.pendingDevices.toMutableList()
            updatedPendingDevices.removeAll {
                it.sessionToken == source.sessionToken
            }
            state.copy(
                pendingDevices = updatedPendingDevices,
                namingDevice = source
            )

        }
    }

    fun skipTrustedDeviceName() {
        val source = _trustedDeviceUiState.value.namingDevice ?: return
        saveTrustedDevice(source, source.displayName)
    }

    fun confirmTrustedDeviceName(displayName: String) {
        val source = _trustedDeviceUiState.value.namingDevice ?: return
        val trimmedName = displayName.trim()
        saveTrustedDevice(
            source = source,
            displayName = trimmedName.ifBlank { source.displayName }
        )
    }

    private fun saveTrustedDevice(source: SharedSourceIdentity, displayName: String) {
        viewModelScope.launch {
            val trustedToken = TrustedDeviceTokens.generateToken(secureRandom)

            trustedDeviceRepository.trustDevice(
                trustedToken = trustedToken,
                displayName = displayName,
                userAgent = source.userAgent,
                ipAddress = source.ipAddress
            )

            source.sessionToken?.let { sessionToken ->
                server?.grantTrustedDevice(sessionToken, trustedToken)
            }

            _trustedDeviceUiState.update { state ->
                state.copy(namingDevice = null)
            }
        }
    }

    fun toggleConnectionInfoExpanded() {
        _serverUiState.update { state ->
            state.copy(isConnectionInfoExpanded = !state.isConnectionInfoExpanded)
        }
    }

    fun regenerateAuthCode() {
        stopServer()
        startServer()
    }

    private fun clearFileShareRecords() {
        val appFilesDir = getApplication<Application>().filesDir

        File(appFilesDir,  LocalServer.SHARED_DIRECTORY_NAME)
            .takeIf { it.exists() }
            ?.deleteRecursively()
    }

    companion object {
        private const val LOG_TAG = "FileShare"
        private const val MIN_AUTH_CODE = 100000
        private const val AUTH_CODE_RANGE = 900000
        private const val HOME_PAGE_ASSET = "index.html"
        private const val SHARED_DIRECTORY_NAME = "shared_files"
        private const val MAX_FILE_NAME_CHARACTERS = 60
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/:*?"<>|]""")
    }
}
