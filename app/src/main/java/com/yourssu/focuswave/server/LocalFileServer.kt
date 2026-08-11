package com.yourssu.focuswave.server

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.SecretKey


class LocalFileServer(
    private val appFilesDirectory: File,
    port: Int = PORT,
    private val authCode: String,
    private val homePage: String? = null,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val onFilesChanged: (() -> Unit)? = null,
    private val findTrustedDevice: ((
            trustedToken: String,
            ipAddress: String?,
            userAgent: String?
            ) -> TrustedDeviceEntity?)? = null,
    private val onUntrustedClientAuthenticated: ((SharedSourceIdentity) -> Unit) ? = null
) : NanoHTTPD(port) {
    private val fileSaveLock = Any()
    private val activeTokens = ConcurrentHashMap.newKeySet<String>()

    private val fileOwners = ConcurrentHashMap<String, SharedFileOwner>()  // key-file name, value-owner
    private val clientSessions = ConcurrentHashMap<String, SharedSourceIdentity>()  // key-session token, value-id
    private val pendingTrustedTokenGrants = ConcurrentHashMap<String, String>() // key-session Token, value-trusted Token
    private val trustedDeviceEventStreams = ConcurrentHashMap<String, SseEventStream>() // key-session token, value-해당 브라우저의 sse 출력 스트림

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val serverKeyPair = FileShareCrypto.generateServerKeyPair()
    private val clientAesKeys = ConcurrentHashMap<String, SecretKey>()  // key : token, value : aes key
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
            ).apply {
                addNoStoreHeaders()
            }

            session.uri == "/ping" && session.method == Method.GET -> jsonResponse(
                Response.Status.OK,
                """{"success":true}"""
            )

            session.uri == "/auth" && session.method == Method.POST -> handleAuth(session)

            session.uri == "/list" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleList(session)
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

            session.uri == "/trusted-device/events" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleTrustedDeviceEvents(session)
                else -> methodNotAllowedResponse()
            }

            session.uri == "/trusted-device/claim" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> handleTrustedDeviceClaim(session)
                else -> methodNotAllowedResponse()
            }

            session.uri == "/auth/trusted" -> when {
                session.method == Method.POST -> handleTrustedAuth(session)
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

        val userAgent = session.headers["user-agent"] ?: "Unknown Device"

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

        val trustedToken = getTrustDeviceToken(session)
        val trustedDevice = trustedToken?.let {
            findTrustedDevice?.invoke(it, clientIp, userAgent)
        }

        return issueSessionResponse(
            identityKind = if (trustedDevice != null) {
                SharedSourceKind.TRUSTED_DEVICE
            } else {
                SharedSourceKind.SESSION_ONLY
            },
            trustedDevice = trustedDevice,
            clientIp = clientIp,
            userAgent = userAgent
        )
    }

    private fun handleTrustedAuth(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "unknown"
        val userAgent = session.headers["user-agent"] ?: "Unknown Device"

        val trustedToken = getTrustDeviceToken(session)
            ?: return unauthorizedResponse("Trusted device token is required").also {
                logDebug("trusted auth failed: missing trusted token")
            }

        val trustedDevice = findTrustedDevice?.invoke(
            trustedToken,
            clientIp,
            userAgent
        ) ?: return unauthorizedResponse("Trusted device is not recognized").also {
            logDebug("trusted auth failed: token not recognized")
        }

        logDebug("trusted auth succeeded: deviceId=${trustedDevice.id}")

        return issueSessionResponse(
            identityKind = SharedSourceKind.TRUSTED_DEVICE,
            trustedDevice = trustedDevice,
            clientIp = clientIp,
            userAgent = userAgent
        )
    }

    private fun issueSessionResponse(
        identityKind: SharedSourceKind,
        trustedDevice: TrustedDeviceEntity?,
        clientIp: String,
        userAgent: String
    ): Response {
        val token = generateToken()
        activeTokens.add(token)

        val clientIdentity = SharedSourceIdentity(
            kind = identityKind,
            sessionToken = token,
            trustedDeviceId = trustedDevice?.id,
            displayName = trustedDevice?.displayName ?: getDeviceFriendlyName(userAgent),
            ipAddress = clientIp,
            userAgent = userAgent
        )

        clientSessions[token] = clientIdentity

        if (clientIdentity.kind == SharedSourceKind.SESSION_ONLY) {
            onUntrustedClientAuthenticated?.invoke(clientIdentity)
        }

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


    private fun handleTrustedDeviceClaim(session: IHTTPSession): Response {
        val sessionToken = getRequestToken(session)
            ?: return unauthorizedResponse()

        val trustedToken = pendingTrustedTokenGrants.remove(sessionToken)
            ?: return jsonError(Response.Status.NOT_FOUND, "No trusted device grant is pending").also {
                logDebug("trusted device claim failed: no pending grant")
            }

        logDebug("trusted device claim succeeded")
        return jsonResponse(
            Response.Status.OK,
            """{"success":true}"""
        ).apply {
            addHeader(
                "Set-Cookie",
                "$TRUSTED_DEVICE_COOKIE_NAME=$trustedToken; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000"
            )
            addNoStoreHeaders()
        }
    }

    private fun handleTrustedDeviceEvents(session: IHTTPSession): Response {
        val sessionToken = getRequestToken(session)
            ?: return unauthorizedResponse()

        val stream = SseEventStream()
        trustedDeviceEventStreams[sessionToken] = stream
        logDebug("trusted event stream registered: sessionToken=$sessionToken")

        stream.sendComment("connected")
        stream.sendMessage("""{"type":"connected"}""")

        if (pendingTrustedTokenGrants.containsKey(sessionToken)) {
            notifyTrustedDeviceApproved(sessionToken)
        }

        return SseResponse(stream)
    }


    fun notifyTrustedDeviceApproved(sessionToken: String) {
        val stream = trustedDeviceEventStreams[sessionToken]
        if (stream == null) {
            logDebug("trusted approved event skipped: no stream for sessionToken=$sessionToken")
            return
        }
        runCatching {
            stream.sendEvent("trusted-device-approved", "{}")
            stream.sendMessage("""{"type":"trusted-device-approved"}""")
            logDebug("trusted approved event sent: sessionToken=$sessionToken")
        }.onFailure {
            logDebug("trusted approved event failed: ${it.message}")
            trustedDeviceEventStreams.remove(sessionToken)
        }
    }

    private fun getTrustDeviceToken(session: IHTTPSession): String? {
        return session.headers["cookie"]
            ?.split(';')
            ?.asSequence()
            ?.map {it.trim()  }
            ?.firstOrNull { it.startsWith("$TRUSTED_DEVICE_COOKIE_NAME=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun getDeviceFriendlyName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return "Unknown Device"

        // 1. 운영체제(OS) 추출
        val os = when {
            userAgent.contains("Windows", ignoreCase = true) -> "Windows"
            userAgent.contains("iPhone", ignoreCase = true) -> "iPhone"
            userAgent.contains("iPad", ignoreCase = true) -> "iPad"
            userAgent.contains("Mac OS", ignoreCase = true) ||
                    userAgent.contains("Macintosh", ignoreCase = true) -> "Mac"
            userAgent.contains("Android", ignoreCase = true) -> "Android"
            userAgent.contains("Linux", ignoreCase = true) -> "Linux"
            else -> "Unknown OS"
        }

        // 2. 브라우저 추출 (🚨 순서가 매우 중요합니다!)
        val browser = when {
            userAgent.contains("Edg", ignoreCase = true) -> "Edge"
            userAgent.contains("Whale", ignoreCase = true) -> "Whale" // 한국 환경 고려 (네이버 웨일)
            userAgent.contains("SamsungBrowser", ignoreCase = true) -> "Samsung Internet"
            userAgent.contains("Chrome", ignoreCase = true) ||
                    userAgent.contains("CriOS", ignoreCase = true) -> "Chrome"
            userAgent.contains("Firefox", ignoreCase = true) ||
                    userAgent.contains("FxiOS", ignoreCase = true) -> "Firefox"
            userAgent.contains("Safari", ignoreCase = true) -> "Safari"
            else -> "Unknown Browser"
        }

        return "$os $browser"
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

    private fun handleList(session: IHTTPSession): Response = try {
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

    private fun handleDownload(session: IHTTPSession): Response =
        handleFileResponse(session, DOWNLOAD_ROUTE_PREFIX, "download")

    private fun handlePreview(session: IHTTPSession): Response =
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
        val directory = sharedDirectory(appFilesDirectory)
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

    private fun handleUpload(session: IHTTPSession): Response {
        logDebug("upload request received (On-the-fly 실시간 스트림 모드)")
        val encryptedFileNameBase64 = session.headers["x-file-name-encrypted"]
        val metadataNonceBase64 = session.headers["x-meta-nonce"]
        val fileNonceBase64 = session.headers["x-focuswave-nonce"]

        // 💡 PC가 보내는 원본 파일의 정확한 바이트 크기를 가져옵니다.
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

                    // 🚀 임시 파일 경유 없이, 와이파이 스트림을 받자마자 바로 복호화해서 디스크에 때려 박습니다.
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


    fun grantTrustedDevice(sessionToken: String, trustedToken: String) {
        logDebug("trusted grant issued: sessionToken=$sessionToken")
        pendingTrustedTokenGrants[sessionToken] = trustedToken
        notifyTrustedDeviceApproved(sessionToken)
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

    private fun Response.addNoStoreHeaders() {
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        addHeader("Pragma", "no-cache")
        addHeader("Expires", "0")
    }





    companion object {
        const val PORT = 8080

        private const val LOG_TAG = "FileShare"
        private const val DOWNLOAD_ROUTE_PREFIX = "/download/"
        private const val PREVIEW_ROUTE_PREFIX = "/preview/"
        private val UPLOAD_FIELD_NAMES = listOf("file", "files")
        internal const val SHARED_DIRECTORY_NAME = "shared_files"

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
        private const val TRUSTED_DEVICE_COOKIE_NAME = "FocusWave-TrustedDevice"
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


private class SseResponse(
    private val stream: SseEventStream
) : NanoHTTPD.Response(
    NanoHTTPD.Response.Status.OK,
    "text/event-stream; charset=utf-8",
    null,
    -1
) {
    override fun send(outputStream: OutputStream) {
        try {
            outputStream.write(
                (
                    "HTTP/1.1 ${NanoHTTPD.Response.Status.OK.description} \r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache, no-transform\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "X-Accel-Buffering: no\r\n" +
                        "\r\n"
                    ).toByteArray(StandardCharsets.UTF_8)
            )
            outputStream.flush()

            while (true) {
                val chunk = stream.takeChunk()
                outputStream.write(Integer.toHexString(chunk.size).toByteArray(StandardCharsets.US_ASCII))
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.write(chunk)
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.flush()
            }
        } catch (_: IOException) {
            stream.close()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            stream.close()
        }
    }
}


private class SseEventStream : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var currentChunk = ByteArray(0)
    private var currentIndex = 0

    fun sendComment(comment: String) {
        enqueue(": $comment\n\n")
    }

    fun sendEvent(event: String, data: String) {
        enqueue("event: $event\ndata: $data\n\n")
    }

    fun sendMessage(data: String) {
        enqueue("data: $data\n\n")
    }

    @Throws(InterruptedException::class)
    fun takeChunk(): ByteArray = chunks.take()

    override fun read(): Int {
        while (currentIndex >= currentChunk.size) {
            currentChunk = chunks.take()
            currentIndex = 0
        }

        return currentChunk[currentIndex++].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val firstByte = read()
        if (firstByte < 0) return -1

        buffer[offset] = firstByte.toByte()
        var copied = 1

        while (copied < length && currentIndex < currentChunk.size) {
            buffer[offset + copied] = currentChunk[currentIndex++]
            copied++
        }

        return copied
    }

    private fun enqueue(payload: String) {
        chunks.offer(payload.toByteArray(Charsets.UTF_8))
    }
}


//  무한 대기(Deadlock) 방지용: 지정된 크기만큼만 데이터를 읽고 끊어버리는 스트림
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
