package com.yourssu.focuswave.server

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import com.yourssu.focuswave.server.data.TrustedDeviceEntity
import com.yourssu.focuswave.server.model.ChatMessage
import com.yourssu.focuswave.server.model.SharedFileOwner
import com.yourssu.focuswave.server.model.SharedSourceIdentity
import com.yourssu.focuswave.server.model.SharedSourceKind


class LocalServer(
    private val appFilesDirectory: File,
    port: Int = PORT,
    private val authCode: String,
    private val homePage: String? = null,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val onFilesChanged: (() -> Unit)? = null,
    private val onChatMessagesChanged: (() -> Unit)? = null,
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
    private val fileShareRoutes = FileShareRoutes(
        appFilesDirectory = appFilesDirectory,
        secureRandom = secureRandom,
        fileSaveLock = fileSaveLock,
        fileOwners = fileOwners,
        clientSessions = clientSessions,
        clientAesKeys = clientAesKeys,
        getRequestToken = ::getRequestToken,
        unauthorizedResponse = ::unauthorizedResponse,
        jsonError = ::jsonError,
        jsonResponse = ::jsonResponse,
        jsonString = ::jsonString,
        getDeviceFriendlyName = ::getDeviceFriendlyName,
        notifyFilesChanged = ::notifyFilesChanged,
        logDebug = ::logDebug
    )
    private val chatRoutes = ChatRoutes(
        secureRandom = secureRandom,
        clientSessions = clientSessions,
        clientAesKeys = clientAesKeys,
        getRequestToken = ::getRequestToken,
        unauthorizedResponse = ::unauthorizedResponse,
        jsonError = ::jsonError,
        jsonResponse = ::jsonResponse,
        jsonString = ::jsonString,
        getDeviceFriendlyName = ::getDeviceFriendlyName,
        onMessagesChanged = ::notifyChatMessagesChanged,
        logDebug = ::logDebug
    )

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
            // 웹 브라우저에 로컬 파일 공유/채팅 홈 화면을 내려준다.
            session.uri == "/" && session.method == Method.GET -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                homePage ?: FALLBACK_HOME_PAGE
            ).apply {
                addNoStoreHeaders()
            }

            // 브라우저가 서버 연결 상태를 확인할 때 사용한다.
            session.uri == "/ping" && session.method == Method.GET -> jsonResponse(
                Response.Status.OK,
                """{"success":true}"""
            )

            // 인증 코드로 새 세션 토큰을 발급한다.
            session.uri == "/auth" && session.method == Method.POST -> handleAuth(session)

            // 현재 클라이언트가 다운로드할 수 있는 공유 파일 목록을 조회한다.
            session.uri == "/list" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> fileShareRoutes.handleList(session)
                else -> methodNotAllowedResponse()
            }

            // PC 브라우저에서 보낸 암호화 파일을 서버 저장소에 업로드한다.
            session.uri == "/upload" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> fileShareRoutes.handleUpload(session)
                else -> methodNotAllowedResponse()
            }

            // 서버 저장소의 파일을 암호화 스트림으로 다운로드한다.
            session.uri.startsWith(FileShareRoutes.DOWNLOAD_ROUTE_PREFIX) -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> fileShareRoutes.handleDownload(session)
                else -> methodNotAllowedResponse()
            }

            // 서버 저장소의 파일을 미리보기용 암호화 스트림으로 내려준다.
            session.uri.startsWith(FileShareRoutes.PREVIEW_ROUTE_PREFIX) -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> fileShareRoutes.handlePreview(session)
                else -> methodNotAllowedResponse()
            }

            // 존재하는 경로지만 허용되지 않은 HTTP method 요청을 차단한다.
            session.uri == "/auth" || session.uri == "/ping" || session.uri == "/" ->
                methodNotAllowedResponse()

            // 채팅방에 저장된 최근 메시지 목록을 암호화해서 조회한다.
            session.uri == "/chat/messages" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> chatRoutes.handleMessages(session)
                else -> methodNotAllowedResponse()
            }

            // 클라이언트가 보낸 암호화 채팅 메시지를 복호화해 저장하고 broadcast한다.
            session.uri == "/chat/send" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> chatRoutes.handleSend(session)
                else -> methodNotAllowedResponse()
            }

            // 새 채팅 메시지를 실시간으로 받기 위한 SSE 연결을 연다.
            session.uri == "/chat/events" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> chatRoutes.handleEvents(session)
                else -> methodNotAllowedResponse()
            }

            // X25519 키 교환을 수행하고 클라이언트별 AES 키를 만든다.
            session.uri == "/crypto/exchange" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> handleCryptoExchange(session)
                else -> methodNotAllowedResponse()
            }

            // 신뢰 기기 승인 결과를 브라우저에 실시간으로 전달하는 SSE 연결을 연다.
            session.uri == "/trusted-device/events" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleTrustedDeviceEvents(session)
                else -> methodNotAllowedResponse()
            }

            // 승인된 신뢰 기기 토큰을 브라우저 쿠키로 저장한다.
            session.uri == "/trusted-device/claim" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> handleTrustedDeviceClaim(session)
                else -> methodNotAllowedResponse()
            }

            // 저장된 신뢰 기기 토큰으로 인증 코드 없이 세션을 발급한다.
            session.uri == "/auth/trusted" -> when {
                session.method == Method.POST -> handleTrustedAuth(session)
                else -> methodNotAllowedResponse()
            }

            // 알 수 없는 non-GET 요청은 method not allowed로 처리한다.
            session.method != Method.GET -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )

            // 어떤 라우트에도 매칭되지 않은 GET 요청은 not found로 처리한다.
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

        // 2. 브라우저 추출 (?? 순서가 매우 중요합니다!)
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

    fun grantTrustedDevice(sessionToken: String, trustedToken: String) {
        logDebug("trusted grant issued: sessionToken=$sessionToken")
        pendingTrustedTokenGrants[sessionToken] = trustedToken
        notifyTrustedDeviceApproved(sessionToken)
    }

    fun postHostChatMessage(message: String) {
        chatRoutes.postHostMessage(message)
    }

    fun getChatMessages(): List<ChatMessage> =
        chatRoutes.listMessages()

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

    private fun notifyChatMessagesChanged() {
        try {
            onChatMessagesChanged?.invoke()
        } catch (error: RuntimeException) {
            logDebug("chat message notification failed: ${error.message}")
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
        internal const val SHARED_DIRECTORY_NAME = "shared_files"

        internal fun sharedDirectory(appFilesDirectory: File): File =
            File(appFilesDirectory, SHARED_DIRECTORY_NAME)


        private const val TOKEN_BYTE_LENGTH = 32
        private const val TOKEN_HEADER_NAME = "x-focuswave-token"
        private const val TOKEN_COOKIE_NAME = "FocusWave-Token"
        private const val BEARER_PREFIX = "Bearer "
        private val AUTH_CODE_PATTERN = Regex(""""code"\s*:\s*"(\d{6})"""")
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


