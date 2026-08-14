package com.yourssu.focuswave.server

import com.yourssu.focuswave.server.model.SharedFileOwner
import com.yourssu.focuswave.server.model.SharedSourceIdentity
import com.yourssu.focuswave.server.model.SharedSourceKind
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

class FileShareRoutes(
    private val appFilesDirectory: File,
    private val secureRandom: SecureRandom,
    private val fileSaveLock: Any,
    private val fileOwners: ConcurrentHashMap<String, SharedFileOwner>,
    private val clientSessions: ConcurrentHashMap<String, SharedSourceIdentity>,
    private val clientAesKeys: ConcurrentHashMap<String, SecretKey>,
    private val getRequestToken: (IHTTPSession) -> String?,
    private val unauthorizedResponse: () -> Response,
    private val jsonError: (Response.Status, String) -> Response,
    private val jsonResponse: (Response.Status, String) -> Response,
    private val jsonString: (String) -> String,
    private val getDeviceFriendlyName: (String?) -> String,
    private val notifyFilesChanged: () -> Unit,
    private val logDebug: (String) -> Unit
) {
fun handleList(session: IHTTPSession): Response = try {
    val requestSource = getRequestSource(session)

    val files = listSharedFiles()
        .filter { file ->
            val owner = fileOwners[file.name]
            !isSameSource(owner?.source, requestSource)
                    && !file.name.endsWith(".tmp")
        }

    logDebug("file list requested: count=${files.size}")
    jsonResponse(
        Response.Status.OK,
        files.joinToString(prefix = "[", postfix = "]") { file -> jsonString(file.name) }
    )
} catch (error: IOException) {
    logDebug("file list failed: ${error.message}")
    jsonError(Response.Status.INTERNAL_ERROR, "Failed to read file list")
}

private fun getRequestSource(session: IHTTPSession): SharedSourceIdentity? {
    val token = getRequestToken(session) ?: return null
    return clientSessions[token]
}

private fun isSameSource(
    first: SharedSourceIdentity?,
    second: SharedSourceIdentity?
): Boolean {
    if (first == null || second == null) return false
    if (first.trustedDeviceId != null && second.trustedDeviceId != null) {
        return first.trustedDeviceId == second.trustedDeviceId
    }
    if (first.sessionToken != null && second.sessionToken != null) {
        return first.sessionToken == second.sessionToken
    }
    return false
}

fun handleDownload(session: IHTTPSession): Response =
    handleFileResponse(session, DOWNLOAD_ROUTE_PREFIX, "download")

fun handlePreview(session: IHTTPSession): Response =
    handleFileResponse(session, PREVIEW_ROUTE_PREFIX, "preview")

private fun handleFileResponse(
    session: IHTTPSession,
    routePrefix: String,
    action: String
): Response {
    val encryptedFileNameBase64 = session.uri.removePrefix(routePrefix)
    val metaNonceBase64 = session.parameters["nonce"]?.firstOrNull()
    if (encryptedFileNameBase64.isEmpty() || metaNonceBase64.isNullOrEmpty()) {
        return jsonError(Response.Status.BAD_REQUEST, "Missing cryptographic parameters for File Name")
    }

    val token =
        getRequestToken(session)
            ?: return unauthorizedResponse()
    val aesKey =
        clientAesKeys[token]
            ?: return jsonError(Response.Status.UNAUTHORIZED, "Encryption key is missing")

    val actualFileName = decryptRequestedFileName(
        encryptedFileNameBase64 = encryptedFileNameBase64,
        metaNonceBase64 = metaNonceBase64,
        aesKey = aesKey
    ) ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid encrypted file name")

    val safeName = sanitizeFileName(actualFileName)
    if (safeName == null || safeName != actualFileName) {
        return jsonError(Response.Status.BAD_REQUEST, "Invalid file name")
    }

    return try {
        val file = resolveSharedFile(safeName)
            ?: return jsonError(Response.Status.NOT_FOUND, "File not found")
        createEncryptedFileResponse(
            file = file,
            aesKey = aesKey,
            action = action
        )
    } catch (error: IOException) {
        logDebug("$action failed: name=$safeName, reason=${error.message}")
        jsonError(Response.Status.INTERNAL_ERROR, "Failed to read file")
    }
}

private fun decryptRequestedFileName(
    encryptedFileNameBase64: String,
    metaNonceBase64: String,
    aesKey: SecretKey
): String? = runCatching {
    FileShareCrypto.decryptAesCbcString(
        encryptedFileNameBase64,
        Base64.getDecoder().decode(metaNonceBase64),
        aesKey
    )
}.getOrNull()

private fun resolveSharedFile(safeName: String): File? {
    val directory = LocalServer.sharedDirectory(appFilesDirectory)
    val file = File(directory, safeName)
    return file.takeIf {
        it.canonicalFile.parentFile == directory.canonicalFile && it.isFile
    }
}

private fun createEncryptedFileResponse(
    file: File,
    aesKey: SecretKey,
    action: String
): Response {
    val dummyMimeType = "application/octet-stream"

    val metaNonceBytes = ByteArray(16)
    secureRandom.nextBytes(metaNonceBytes)
    val encryptedFileNameBase64 =
        FileShareCrypto.encryptAesCbcStringWith256Padding(file.name, metaNonceBytes, aesKey)
    val metaNonceBase64 = Base64.getEncoder().encodeToString(metaNonceBytes)

    val nonceBytes = ByteArray(16)
    secureRandom.nextBytes(nonceBytes)
    val expectedEncryptedSize = (file.length() + 16) / 16 * 16

    return object : Response(
        Response.Status.OK,
        dummyMimeType,
        null,  // 실시간 스트리밍을 위해 send를 override
        expectedEncryptedSize
    ) {
        override fun send(outputStream: OutputStream) {
            try {
                outputStream.write(
                    buildString {
                        append("HTTP/1.1 ${Response.Status.OK.description}\r\n")
                        append("Content-Type: $dummyMimeType\r\n")
                        append("Content-Length: $expectedEncryptedSize\r\n")
                        append("Content-Disposition: attachment; filename=\"encrypted_data.bin\"\r\n")
                        append("X-FocusWave-Encrypted: true\r\n")
                        append("X-FocusWave-Nonce: ${Base64.getEncoder().encodeToString(nonceBytes)}\r\n")
                        append("X-File-Name-Encrypted: $encryptedFileNameBase64\r\n")
                        append("X-Meta-Nonce: $metaNonceBase64\r\n")
                        append("\r\n")
                    }.toByteArray(StandardCharsets.UTF_8)
                )

                file.inputStream().use { plainInput ->
                    FileShareCrypto.encryptAesCbcStream(
                        plainInputStream = plainInput,
                        encryptedOutputStream = outputStream,
                        nonceBytes = nonceBytes,
                        aesKey = aesKey
                    )
                }
            } catch (error: IOException) {
                logDebug("$action response failed: ${error.message}")
            }
        }
    }
}

fun handleUpload(session: IHTTPSession): Response {
    logDebug("upload request received (On-the-fly 실시간 스트림 모드)")
    val encryptedFileNameBase64 = session.headers["x-file-name-encrypted"]
    val metadataNonceBase64 = session.headers["x-meta-nonce"]
    val fileNonceBase64 = session.headers["x-focuswave-nonce"]

    // ?? PC가 보내는 원본 파일의 정확한 바이트 크기를 가져옵니다.
    val contentLength = session.headers["content-length"]?.toLongOrNull()


    if (
        fileNonceBase64.isNullOrBlank() ||
        encryptedFileNameBase64.isNullOrBlank() ||
        metadataNonceBase64.isNullOrBlank()
        ) {
        return uploadError(Response.Status.BAD_REQUEST, "필수 헤더가 누락되었습니다.")
    }

    if (contentLength == null || contentLength <= 0) {
        return uploadError(Response.Status.BAD_REQUEST, "Content-Length를 알 수 없습니다.")
    }


    val token = getRequestToken(session) ?: return unauthorizedResponse()
    val aesKey = clientAesKeys[token] ?: return uploadError(Response.Status.UNAUTHORIZED, "암호화 키가 없습니다.")

    val rawFileName = try{
        val metaNonceBytes = Base64.getDecoder().decode(metadataNonceBase64)

        FileShareCrypto.decryptAesCbcString(
            encryptedFileNameBase64,
            metaNonceBytes,
            aesKey
        )
    } catch (error: Exception) {
        logDebug("파일명 복호화 실패: ${error.message}")
        return uploadError(Response.Status.BAD_REQUEST, "파일명 복호화에 실패했습니다.")
    }

    val safeFileName = sanitizeFileName(rawFileName)
        ?: return uploadError(Response.Status.BAD_REQUEST, "파일명이 비어있습니다.")

    val fileNonceBytes = try {
        Base64.getDecoder().decode(fileNonceBase64)
    } catch (_: Exception) {
        return uploadError(Response.Status.BAD_REQUEST, "올바르지 않은 nonce 형식입니다.")
    }

    return try {
        // contentLength 크기만큼만 읽고 종료하는 스트림
        val boundedStream = BoundedInputStream(session.inputStream, contentLength)

        val token =
            getRequestToken(session)
                ?: return unauthorizedResponse()

        val storedFile = saveUploadedFileStream(
            networkInputStream = boundedStream, // 안전하게 씌워진 스트림을 넘김
            safeFileName = safeFileName,
            nonceBytes = fileNonceBytes,
            aesKey = aesKey
        )

        val source = clientSessions[token] ?: SharedSourceIdentity(
            kind = SharedSourceKind.UNKNOWN,
            sessionToken = token,
            trustedDeviceId = null,
            displayName = getDeviceFriendlyName(session.headers["user-agent"]),
            ipAddress = session.remoteIpAddress,
            userAgent = session.headers["user-agent"]
        )

        fileOwners[storedFile.name] = SharedFileOwner(
            source = source,
            receivedAtMillis = System.currentTimeMillis()
        )

        logDebug("upload saved on-the-fly: path=${storedFile.absolutePath}, size=${storedFile.length()}")
        notifyFilesChanged()


        jsonResponse(
            Response.Status.OK,
            """{"success":true,"fileName":${jsonString(storedFile.name)},"size":${storedFile.length()}}"""
        )
    } catch (error: Exception) {
        logDebug("upload save failed: reason=${error.message}")
        uploadError(
            Response.Status.INTERNAL_ERROR,
            "파일 저장 실패: ${error.message}"
        )
    }
}

private fun uploadError(status: Response.Status, message: String): Response {
    logDebug("upload failed: $message")
    return jsonError(status, message)
}

private fun saveUploadedFileStream(
    networkInputStream: InputStream,
    safeFileName: String,
    nonceBytes: ByteArray,
    aesKey: SecretKey
): File = synchronized(fileSaveLock) {
    val directory = getOrCreateSharedDirectory()
    val destination = resolveUniqueFile(directory, safeFileName)

    if (destination.canonicalFile.parentFile != directory.canonicalFile) {
        throw IOException("Invalid file path")
    }

    // 쓰기가 완전히 끝나기 전까지 UI 리스트에 노출되어 깨지는 것을 막기 위해 여전히 .tmp 확장자를 씁니다.
    val tempDestination = File(directory, "${destination.name}.tmp")

    try {
        //  256KB 대용량 버퍼를 네트워크 스트림과 디스크 출력 스트림 양쪽에 씌웁니다.
        networkInputStream.buffered(256 * 1024).use { bufferedNetworkInput ->
            tempDestination.outputStream().buffered(256 * 1024).use { decryptedOutput ->

                // ?? 임시 파일 경유 없이, 와이파이 스트림을 받자마자 바로 복호화해서 디스크에 때려 박습니다.
                FileShareCrypto.decryptAesCbcStream(
                    encryptedInputStream = bufferedNetworkInput,
                    decryptedOutputStream = decryptedOutput,
                    nonceBytes = nonceBytes,
                    aesKey = aesKey
                )
            }
        } // 이 블록을 빠져나오는 순간 네트워크 소켓과 파일이 완벽히 close() 됩니다.

        if (!tempDestination.isFile) {
            throw IOException("Stored file verification failed")
        }

        //  디스크에 실시간 복호화 쓰기가 끝나자마자 원본 이름으로 교체
        if (!tempDestination.renameTo(destination)) {
            throw IOException("Failed to rename temp file to final destination")
        }

        destination
    } catch (error: Exception) {
        tempDestination.delete()
        destination.delete()
        throw IOException("실시간 스트림 복호화 실패: ${error.message}", error)
    }
}

fun listSharedFiles(): List<File> {
    val directory = LocalServer.sharedDirectory(appFilesDirectory)
    if (!directory.exists()) return emptyList()
    if (!directory.isDirectory) {
        throw IOException("Shared storage path is not a directory")
    }
    return directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile }
        ?.sortedBy { it.name.lowercase() }
        ?.toList()
        ?: throw IOException("Failed to read shared storage directory")
}

private fun getOrCreateSharedDirectory(): File {
    val directory = LocalServer.sharedDirectory(appFilesDirectory)
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
    if (!initialFile.exists()) return initialFile

    val dotIndex = safeFileName.lastIndexOf('.')
    val extension = if (dotIndex > 0) safeFileName.substring(dotIndex) else ""
    val baseName = if (dotIndex > 0) safeFileName.substring(0, dotIndex) else safeFileName

    var suffixNumber = 1
    while (true) {
        val suffix = " ($suffixNumber)"
        val shortenedBase = baseName
            .take((MAX_FILE_NAME_CHARACTERS - extension.length - suffix.length).coerceAtLeast(1))
            .trimEnd { Character.isHighSurrogate(it) }
        val candidate = File(directory, shortenedBase + suffix + extension)
        if (!candidate.exists()) return candidate
        suffixNumber++
    }
}



private fun saveUploadedFile(temporaryFile: File, safeFileName: String): File =
    synchronized(fileSaveLock) {
        val directory = getOrCreateSharedDirectory()
        val destination = resolveUniqueFile(directory, safeFileName)

        if (destination.canonicalFile.parentFile != directory.canonicalFile) {
            throw IOException("Invalid file path")
        }

        try {
            temporaryFile.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                    output.flush()
                }
            }
            if (!destination.isFile || destination.length() != temporaryFile.length()) {
                throw IOException("Stored file verification failed")
            }
            destination
        } catch (error: IOException) {
            destination.delete()
            throw error
        }
    }

private fun saveUploadedFileBytes(
    fileBytes: ByteArray,
    safeFileName: String
): File =
    synchronized(fileSaveLock) {
        val directory = getOrCreateSharedDirectory()
        val destination = resolveUniqueFile(directory, safeFileName)

        if (destination.canonicalFile.parentFile != directory.canonicalFile) {
            throw IOException("Invalid file path")
        }

        try {
            destination.outputStream().use { output ->
                output.write(fileBytes)
                output.flush()
            }

            if (!destination.isFile || destination.length() != fileBytes.size.toLong()) {
                throw IOException("Stored file verification failed")
            }

            destination
        } catch (error: IOException) {
            destination.delete()
            throw error
        }
    }

    companion object {
        const val DOWNLOAD_ROUTE_PREFIX = "/download/"
        const val PREVIEW_ROUTE_PREFIX = "/preview/"
        private const val MAX_FILE_NAME_CHARACTERS = 60
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/:*?"<>|]""")
    }
}

private class BoundedInputStream(
    private val inStream: java.io.InputStream,
    private val limit: Long
) : java.io.InputStream() {
    private var left: Long = limit

    override fun read(): Int {
        if (left <= 0) return -1
        val result = inStream.read()
        if (result != -1) left--
        return result
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val bytesToRead = minOf(len.toLong(), left).toInt()
        val result = inStream.read(b, off, bytesToRead)
        if (result != -1) left -= result
        return result
    }

    override fun close() {
        // inStream.close()
    }
}
