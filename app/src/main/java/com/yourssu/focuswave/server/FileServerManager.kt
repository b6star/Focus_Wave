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
import androidx.core.content.FileProvider
import com.yourssu.focuswave.ui.fileshare.SharedFileUi
import java.io.File
import java.io.IOException
import java.text.Normalizer

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
            homePage = loadHomePage()
        )
        try {
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = newServer

            val serverAddress = findWifiServerAddress()
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

    fun stopServer() {
        server?.stop()
        server = null
        clearFileShareRecords()
        _uiState.value = FileShareUiState()
    }

    fun getUploadedFiles(): List<SharedFileUi> {
        val directory = File(getApplication<Application>().filesDir, UPLOAD_DIRECTORY_NAME)

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

    fun shareFiles(files: List<SharedFileUi>) {
        val application = getApplication<Application>()
        val directory = getOrCreateSharedDirectory()

        files.forEach { file ->
            val uriString = file.uriString ?: return@forEach
            val sourceUri = Uri.parse(uriString)

            val safeFileName = sanitizeFileName(file.name)
                ?: return@forEach

            val destination = resolveUniqueFile(directory, safeFileName)

            application.contentResolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Cannot open selected file")
        }
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
            .take(MAX_FILE_NAME_CHARACTERS)

        return cleanedName.ifBlank { null }
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

    fun saveUploadedFileToUri(file: SharedFileUi, destinationUri: Uri) {
        val application = getApplication<Application>()

        val sourceFile = File(
            File(application.filesDir, UPLOAD_DIRECTORY_NAME),
            file.name
        )

        if (!sourceFile.exists()) return

        application.contentResolver.openOutputStream(destinationUri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }


    fun regenerateAuthCode() {
        stopServer()
        startServer()
    }

    private fun clearFileShareRecords() {
        val appFilesDir = getApplication<Application>().filesDir

        listOf(
            UPLOAD_DIRECTORY_NAME,
            SHARED_DIRECTORY_NAME
        ).forEach { directoryName ->
            File(appFilesDir, directoryName)
                .takeIf { it.exists() }
                ?.deleteRecursively()
        }
    }


    companion object {
        private const val MIN_AUTH_CODE = 1000
        private const val AUTH_CODE_RANGE = 9000
        private const val HOME_PAGE_ASSET = "index.html"
        private const val UPLOAD_DIRECTORY_NAME = "uploaded_files"
        private const val SHARED_DIRECTORY_NAME = "shared_files"
        private const val MAX_FILE_NAME_CHARACTERS = 60
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/:*?"<>|]""")
    }
}
