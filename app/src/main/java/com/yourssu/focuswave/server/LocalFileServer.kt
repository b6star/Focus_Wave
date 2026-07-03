package com.yourssu.focuswave.server

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey


class LocalFileServer(
    private val appFilesDirectory: File,
    port: Int = PORT,
    private val authCode: String,
    private val homePage: String? = null,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val onFilesChanged: (() -> Unit)? = null
) : NanoHTTPD(port) {
    private val fileSaveLock = Any()
    private val activeTokens = ConcurrentHashMap.newKeySet<String>()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val serverKeyPair = FileShareCrypto.generateServerKeyPair()
    private val clientAesKeys = ConcurrentHashMap<String, SecretKey>()
    private val authAttempts = ConcurrentHashMap<String, AuthAttempt>()

    private data class AuthAttempt(
        var failCount: Int = 0,
        var blockedUntilMillis: Long = 0L,
        var banned: Boolean = false
    )

    override fun stop() {
        activeTokens.clear()
        clientAesKeys.clear()
        super.stop()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/" && session.method == Method.GET -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                homePage ?: FALLBACK_HOME_PAGE
            )

            session.uri == "/ping" && session.method == Method.GET -> jsonResponse(
                Response.Status.OK,
                """{"success":true}"""
            )

            session.uri == "/auth" && session.method == Method.POST -> handleAuth(session)

            session.uri == "/list" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleList()
                else -> methodNotAllowedResponse()
            }

            session.uri == "/upload" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> handleUpload(session)
                else -> methodNotAllowedResponse()
            }

            session.uri.startsWith(DOWNLOAD_ROUTE_PREFIX) -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleDownload(session)
                else -> methodNotAllowedResponse()
            }

            session.uri.startsWith(PREVIEW_ROUTE_PREFIX) -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handlePreview(session)
                else -> methodNotAllowedResponse()
            }

            session.uri == "/auth" || session.uri == "/ping" || session.uri == "/" ->
                methodNotAllowedResponse()

            session.uri == "/crypto/exchange" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> handleCryptoExchange(session)
                else -> methodNotAllowedResponse()
            }

            session.method != Method.GET -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found"
            )
        }
    }

    private fun handleAuth(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "unknown"
        val attempt = authAttempts.getOrPut(clientIp) { AuthAttempt() }
        val now = System.currentTimeMillis()

        if (attempt.banned) {
            return  jsonError(Response.Status.FORBIDDEN, "This IP is blocked until server restart")
        }

        if (attempt.blockedUntilMillis > now) {
            return jsonError(Response.Status.TOO_MANY_REQUESTS, "Too many failed attempts, Try again later")
        }

        val mediaType = session.headers["content-type"]
            .orEmpty()
            .substringBefore(';')
            .trim()
        if (!mediaType.equals("application/json", ignoreCase = true)) {
            return jsonError(Response.Status.BAD_REQUEST, "Content-Type must be application/json")
        }

        val parsedBody = mutableMapOf<String, String>()
        try {
            session.parseBody(parsedBody)
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON request")
        }

        val submittedCode = AUTH_CODE_PATTERN
            .find(parsedBody["postData"].orEmpty())
            ?.groupValues
            ?.get(1)
        if (submittedCode == null || !codesMatch(submittedCode, authCode)) {
            attempt.failCount++

            if (attempt.failCount >= 6) {
                attempt.banned = true
                return jsonError(Response.Status.FORBIDDEN, "This IP is blocked until server restart")
            }

            if (attempt.failCount == 3) {
                attempt.blockedUntilMillis = now + 60_000L
                return jsonError(Response.Status.TOO_MANY_REQUESTS, "Too many failed attempts. Try again after 1 minute")
            }

            return unauthorizedResponse("Invalid authentication code")
        }

        authAttempts.remove(clientIp)

        val token = generateToken()
        activeTokens.add(token)
        logDebug("authentication succeeded")
        return jsonResponse(
            Response.Status.OK,
            """{"success":true,"token":${jsonString(token)}}"""
        ).apply {
            addHeader(
                "Set-Cookie",
                "$TOKEN_COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict"
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handleCryptoExchange(session: IHTTPSession): Response {
        val mediaType = session.headers["content-type"]
            .orEmpty()
            .substringBefore(';')
            .trim()

        if (!mediaType.equals("application/json", ignoreCase = true)) {
            return jsonError(Response.Status.BAD_REQUEST, "Content-Type must be application/json")
        }

        val parsedBody = mutableMapOf<String, String>()

        try {
            session.parseBody(parsedBody)
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON request")
        }

        val clientPublicKeyBase64 = CLIENT_PUBLIC_KEY_PATTERN
            .find(parsedBody["postData"].orEmpty())
            ?.groupValues
            ?.get(1)
            ?: return jsonError(Response.Status.BAD_REQUEST, "Client public key is required")

        return try {
            val clientPublicKey =
                FileShareCrypto.decodeClientPublicKey(
                    clientPublicKeyBase64
                )

            val aesKey =
                FileShareCrypto.deriveAesKey(
                    serverKeyPair = serverKeyPair,
                    clientPublicKey = clientPublicKey
                )

            val token =
                getRequestToken(session)
                    ?: return unauthorizedResponse()

            clientAesKeys[token] = aesKey

            val serverPublicKeyBase64 =
                FileShareCrypto.publicKeyToBase64(
                    serverKeyPair.public
                )

            jsonResponse(
                Response.Status.OK,
                """{"success":true,"serverPublicKey":${jsonString(serverPublicKeyBase64)}}"""
            )
        } catch (error: Exception) {
        logDebug("crypto exchange failed: ${error::class.java.name}, ${error.message}")
        jsonError(Response.Status.BAD_REQUEST, "Invalid client public key: ${error.message}")
    }
    }



    private fun generateToken(): String {
        val tokenBytes = ByteArray(TOKEN_BYTE_LENGTH)
        secureRandom.nextBytes(tokenBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
    }

    private fun codesMatch(submittedCode: String, expectedCode: String): Boolean =
        MessageDigest.isEqual(
            submittedCode.toByteArray(Charsets.UTF_8),
            expectedCode.toByteArray(Charsets.UTF_8)
        )

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        val authorizationToken = session.headers["authorization"]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
        val customHeaderToken = session.headers[TOKEN_HEADER_NAME]?.trim()
        val cookieToken = session.headers["cookie"]
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$TOKEN_COOKIE_NAME=") }
            ?.substringAfter('=')
            ?.trim()
        val token = authorizationToken
            ?.takeIf { it.isNotEmpty() }
            ?: customHeaderToken?.takeIf { it.isNotEmpty() }
            ?: cookieToken?.takeIf { it.isNotEmpty() }
        return token != null && activeTokens.contains(token)
    }

    private fun getRequestToken(session: IHTTPSession): String? {
        val authorizationToken = session.headers["authorization"]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()

        val customHeaderToken = session.headers[TOKEN_HEADER_NAME]?.trim()

        val cookieToken = session.headers["cookie"]
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$TOKEN_COOKIE_NAME=") }
            ?.substringAfter('=')
            ?.trim()

        return authorizationToken
            ?.takeIf { it.isNotEmpty() }
            ?: customHeaderToken?.takeIf { it.isNotEmpty() }
            ?: cookieToken?.takeIf { it.isNotEmpty() }
    }
    private fun unauthorizedResponse(message: String = "Authentication required"): Response =
        jsonError(Response.Status.UNAUTHORIZED, message).apply {
            logDebug("authentication failed: $message")
            addHeader("WWW-Authenticate", "Bearer")
        }

    private fun methodNotAllowedResponse(): Response = newFixedLengthResponse(
        Response.Status.METHOD_NOT_ALLOWED,
        MIME_PLAINTEXT,
        "Method not allowed"
    )

    private fun handleList(): Response = try {
        val files = listSharedFiles()
        logDebug("file list requested: count=${files.size}")
        jsonResponse(
            Response.Status.OK,
            files.joinToString(prefix = "[", postfix = "]") { file -> jsonString(file.name) }
        )
    } catch (error: IOException) {
        logDebug("file list failed: ${error.message}")
        jsonError(Response.Status.INTERNAL_ERROR, "Failed to read file list")
    }

    private fun handleDownload(session: IHTTPSession): Response =
        handleFileResponse(session, DOWNLOAD_ROUTE_PREFIX, "attachment", "download")

    private fun handlePreview(session: IHTTPSession): Response =
        handleFileResponse(session, PREVIEW_ROUTE_PREFIX, "inline", "preview")

    private fun handleFileResponse(
        session: IHTTPSession,
        routePrefix: String,
        disposition: String,
        action: String
    ): Response {
        val requestedName = session.uri.removePrefix(routePrefix)
        val safeName = sanitizeFileName(requestedName)
        if (safeName == null || safeName != requestedName) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid file name")
        }

        val directory = sharedDirectory(appFilesDirectory)
        val file = File(directory, safeName)
        return try {
            if (
                file.canonicalFile.parentFile != directory.canonicalFile ||
                !file.isFile
            ) {
                return jsonError(Response.Status.NOT_FOUND, "File not found")
            }

            logDebug("$action requested: name=${file.name}")
            val mimeType = URLConnection.guessContentTypeFromName(file.name)
                ?: "application/octet-stream"
            val encodedName = URLEncoder.encode(file.name, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                FileInputStream(file),
                file.length()
            ).apply {
                addHeader(
                    "Content-Disposition",
                    "$disposition; filename*=UTF-8''$encodedName"
                )
            }
        } catch (error: IOException) {
            logDebug("$action failed: name=$safeName, reason=${error.message}")
            jsonError(Response.Status.INTERNAL_ERROR, "Failed to read file")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        logDebug("upload request received")
        val contentType = session.headers["content-type"].orEmpty()
        val mediaType = contentType.substringBefore(';').trim()
        if (!mediaType.equals("multipart/form-data", ignoreCase = true)) {
            return uploadError(Response.Status.BAD_REQUEST, "Content-Type must be multipart/form-data")
        }

        val contentLength = session.headers["content-length"]?.toLongOrNull()

        if (contentLength != null && contentLength > MAX_REQUEST_SIZE_BYTES) {
            return uploadError(Response.Status.PAYLOAD_TOO_LARGE, "File is too large")
        }

        val uploadedParts = mutableMapOf<String, String>()
        try {
            session.parseBody(uploadedParts)
        } catch (error: Exception) {
            return uploadError(
                Response.Status.BAD_REQUEST,
                error.localizedMessage ?: "Invalid multipart request"
            )
        }

        val uploadFieldName = UPLOAD_FIELD_NAMES.firstOrNull { fieldName ->
            !session.parms[fieldName].isNullOrBlank() && !uploadedParts[fieldName].isNullOrBlank()
        }
        val rawFileName = session.parameters["fileName"]?.firstOrNull()
            ?: session.parms["fileName"]
            ?: uploadFieldName?.let(session.parms::get)
        val temporaryPath = uploadFieldName?.let(uploadedParts::get)
        if (rawFileName.isNullOrBlank() || temporaryPath.isNullOrBlank()) {
            return uploadError(Response.Status.BAD_REQUEST, "File field is required")
        }
        val nonceBase64 = session.parms["nonce"]
            ?: return uploadError(Response.Status.BAD_REQUEST, "Nonce is required")

        val nonceBytes = try {
            Base64.getDecoder().decode(nonceBase64)
        } catch (_: Exception) {
            return uploadError(Response.Status.BAD_REQUEST, "Invalid nonce")
        }


        val safeFileName = sanitizeFileName(rawFileName)
            ?: return uploadError(Response.Status.BAD_REQUEST, "File name is empty")
        val temporaryFile = File(temporaryPath)
        if (!temporaryFile.isFile) {
            return uploadError(Response.Status.BAD_REQUEST, "Uploaded file is invalid")
        }

        if (temporaryFile.length() > MAX_FILE_SIZE_BYTES) {
            return uploadError(Response.Status.PAYLOAD_TOO_LARGE, "File is too large")
        }


        val token =
            getRequestToken(session)
                ?: return unauthorizedResponse()

        val aesKey =
            clientAesKeys[token]
                ?: return uploadError(Response.Status.UNAUTHORIZED, "Encryption key is missing")

        logDebug("upload received: name=$safeFileName, size=${temporaryFile.length()}")
        return try {
            val encryptedBytes =
                temporaryFile.readBytes()

            val plainBytes =
                FileShareCrypto.decryptAesGcm(
                    encryptedBytes = encryptedBytes,
                    nonceBytes = nonceBytes,
                    aesKey = aesKey
                )

            val storedFile =
                saveUploadedFileBytes(
                    plainBytes,
                    safeFileName
                )
            logDebug(
                "upload saved: path=${storedFile.absolutePath}, " +
                    "exists=${storedFile.exists()}, size=${storedFile.length()}"
            )
            notifyFilesChanged()
            jsonResponse(
                Response.Status.OK,
                """{"success":true,"fileName":${jsonString(storedFile.name)},"size":${storedFile.length()}}"""
            )
        } catch (error: IOException) {
            logDebug("upload save failed: reason=${error.message}")
            uploadError(
                Response.Status.INTERNAL_ERROR,
                "Failed to save file"
            )
        }
    }

    internal fun listSharedFiles(): List<File> {
        val directory = sharedDirectory(appFilesDirectory)
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
        val directory = sharedDirectory(appFilesDirectory)
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Shared storage path is not a directory")
            }
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create shared storage directory")
        }
        return directory
    }

    private fun getOrCreateReceivedDirectory(): File {
        val directory = receivedDirectory(appFilesDirectory)

        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Received storage path is not a directory")
            }
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create received storage directory")
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
            val directory = getOrCreateReceivedDirectory()
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

    private fun uploadError(status: Response.Status, message: String): Response {
        logDebug("upload failed: $message")
        return jsonError(status, message)
    }

    private fun jsonError(status: Response.Status, message: String): Response =
        jsonResponse(status, """{"success":false,"message":${jsonString(message)}}""")

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            body
        )

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun notifyFilesChanged() {
        try {
            onFilesChanged?.invoke()
        } catch (error: RuntimeException) {
            logDebug("file change notification failed: ${error.message}")
        }
    }

    private fun logDebug(message: String) {
        try {
            Log.d(LOG_TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log is unavailable in local JVM tests.
        }
    }





    companion object {
        const val PORT = 8080

        private const val LOG_TAG = "FileShare"
        private const val DOWNLOAD_ROUTE_PREFIX = "/download/"
        private const val PREVIEW_ROUTE_PREFIX = "/preview/"
        private val UPLOAD_FIELD_NAMES = listOf("file", "files")
        internal const val RECEIVED_DIRECTORY_NAME = "received_files"
        internal const val SHARED_DIRECTORY_NAME = "shared_files"

        internal fun receivedDirectory(appFilesDirectory: File): File =
            File(appFilesDirectory, RECEIVED_DIRECTORY_NAME)

        internal fun sharedDirectory(appFilesDirectory: File): File =
            File(appFilesDirectory, SHARED_DIRECTORY_NAME)
        private const val MAX_FILE_SIZE_BYTES = 5000L * 1024 * 1024  //최대 5GB
        private const val MAX_MULTIPART_OVERHEAD_BYTES = 1024L * 1024
        private const val MAX_REQUEST_SIZE_BYTES = MAX_FILE_SIZE_BYTES + MAX_MULTIPART_OVERHEAD_BYTES
        private const val MAX_FILE_NAME_CHARACTERS = 60
        private const val TOKEN_BYTE_LENGTH = 32
        private const val TOKEN_HEADER_NAME = "x-focuswave-token"
        private const val TOKEN_COOKIE_NAME = "FocusWave-Token"
        private const val BEARER_PREFIX = "Bearer "
        private val AUTH_CODE_PATTERN = Regex(""""code"\s*:\s*"(\d{6})"""")
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/:*?"<>|]""")
        private val CLIENT_PUBLIC_KEY_PATTERN = Regex(""""clientPublicKey"\s*:\s*"([^"]+)"""")
        private val FALLBACK_HOME_PAGE = """
            <!doctype html>
            <html lang="ko">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>FocusWave Local Share</title>
                <style>
                    body {
                        font-family: sans-serif;
                        max-width: 680px;
                        margin: 64px auto;
                        padding: 0 24px;
                        color: #202124;
                    }
                    .card {
                        border: 1px solid #dadce0;
                        border-radius: 16px;
                        padding: 24px;
                    }
                    form {
                        display: grid;
                        gap: 16px;
                        margin-top: 24px;
                    }
                    button {
                        width: fit-content;
                        padding: 10px 18px;
                        cursor: pointer;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>FocusWave Local Share</h1>
                    <p>같은 Wi-Fi에서 FocusWave 로컬 서버에 연결되었습니다.</p>
                    <p>최대 50MB의 파일을 업로드할 수 있습니다.</p>
                    <form method="post" enctype="multipart/form-data" action="/upload">
                        <input type="file" name="file" required>
                        <button type="submit">파일 업로드</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
