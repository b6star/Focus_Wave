package com.yourssu.focuswave.server

import com.yourssu.focuswave.server.model.ChatMessage
import com.yourssu.focuswave.server.model.SharedSourceIdentity
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

class ChatRoutes(
    private val secureRandom: SecureRandom,
    private val clientSessions: ConcurrentHashMap<String, SharedSourceIdentity>,
    private val clientAesKeys: ConcurrentHashMap<String, SecretKey>,
    private val getRequestToken: (IHTTPSession) -> String?,
    private val unauthorizedResponse: () -> Response,
    private val jsonError: (Response.Status, String) -> Response,
    private val jsonResponse: (Response.Status, String) -> Response,
    private val jsonString: (String) -> String,
    private val getDeviceFriendlyName: (String?) -> String,
    private val onMessagesChanged: () -> Unit,
    private val logDebug: (String) -> Unit
) {
    private val messageLock = Any()
    private val messages = mutableListOf<ChatMessage>()
    private val eventStreams = ConcurrentHashMap<String, SseEventQueue>()
    private var nextSequence = 1L

    // 현재 서버 메모리에 남아있는 최근 채팅 목록을 요청 클라이언트 키로 암호화해 반환한다.
    fun handleMessages(session: IHTTPSession): Response {
        val token = getRequestToken(session) ?: return unauthorizedResponse()
        val aesKey = clientAesKeys[token]
            ?: return jsonError(Response.Status.UNAUTHORIZED, "Encryption key is missing")

        val snapshot = synchronized(messageLock) { messages.toList() }
        val encryptedPayload = encryptPayload(messagesToJson(snapshot).toString(), aesKey)

        return jsonResponse(
            Response.Status.OK,
            """{"success":true,"nonce":${jsonString(encryptedPayload.nonceBase64)},"payload":${jsonString(encryptedPayload.encryptedBase64)}}"""
        )
    }

    // 웹 클라이언트가 보낸 암호화 메시지를 복호화해 저장하고 모든 접속자에게 전파한다.
    fun handleSend(session: IHTTPSession): Response {
        val token = getRequestToken(session) ?: return unauthorizedResponse()
        val aesKey = clientAesKeys[token]
            ?: return jsonError(Response.Status.UNAUTHORIZED, "Encryption key is missing")

        val body = parseJsonBody(session)
            ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON request")

        val nonceBase64 = body.optString("nonce")
        val encryptedMessageBase64 = body.optString("message")
        if (nonceBase64.isBlank() || encryptedMessageBase64.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "Encrypted message is required")
        }

        val decryptedText = try {
            FileShareCrypto.decryptAesCbcText(
                encryptedBase64 = encryptedMessageBase64,
                nonceBytes = Base64.getDecoder().decode(nonceBase64),
                aesKey = aesKey
            ).trim()
        } catch (error: Exception) {
            logDebug("chat decrypt failed: ${error.message}")
            return jsonError(Response.Status.BAD_REQUEST, "Failed to decrypt message")
        }

        val decryptedPayload = parseDecryptedMessagePayload(decryptedText)
        val plainText = decryptedPayload.message
        if (plainText.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "Message is empty")
        }

        val source = clientSessions[token]
        val message = appendMessage(
            senderId = decryptedPayload.clientId ?: source?.sessionToken ?: token,
            senderName = source?.displayName ?: getDeviceFriendlyName(session.headers["user-agent"]),
            senderIpAddress = source?.ipAddress ?: session.remoteIpAddress,
            senderUserAgent = source?.userAgent ?: session.headers["user-agent"],
            plainText = plainText.take(MAX_MESSAGE_CHARACTERS)
        )

        broadcastMessage(message)
        onMessagesChanged()

        return jsonResponse(
            Response.Status.OK,
            """{"success":true,"sequence":${message.sequence},"id":${jsonString(message.id)}}"""
        )
    }

    // 새 채팅 메시지를 실시간으로 받기 위한 클라이언트 SSE 스트림을 등록한다.
    fun handleEvents(session: IHTTPSession): Response {
        val token = getRequestToken(session) ?: return unauthorizedResponse()
        if (!clientAesKeys.containsKey(token)) {
            return jsonError(Response.Status.UNAUTHORIZED, "Encryption key is missing")
        }

        val eventQueue = SseEventQueue()
        eventStreams[token] = eventQueue
        eventQueue.enqueueComment("connected")
        eventQueue.enqueueDefaultEvent("""{"type":"connected"}""")

        return SseResponse(eventQueue)
    }

    // 안드로이드 호스트 앱 내부에서 작성한 평문 메시지를 채팅방에 추가한다.
    fun postHostMessage(plainText: String): ChatMessage? {
        val trimmedMessage = plainText.trim()
        if (trimmedMessage.isBlank()) return null

        val message = appendMessage(
            senderId = HOST_SENDER_ID,
            senderName = HOST_SENDER_NAME,
            senderIpAddress = HOST_DEVICE_LABEL,
            senderUserAgent = HOST_USER_AGENT,
            plainText = trimmedMessage.take(MAX_MESSAGE_CHARACTERS)
        )

        broadcastMessage(message)
        onMessagesChanged()
        return message
    }

    // 앱 내부 UI가 현재 채팅 목록을 그릴 수 있도록 메모리 메시지 스냅샷을 반환한다.
    fun listMessages(): List<ChatMessage> =
        synchronized(messageLock) { messages.toList() }

    // 서버 기준 순서와 수신 시간을 부여해 메시지를 메모리에 저장한다.
    private fun appendMessage(
        senderId: String,
        senderName: String,
        senderIpAddress: String?,
        senderUserAgent: String?,
        plainText: String
    ): ChatMessage = synchronized(messageLock) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            sequence = nextSequence++,
            senderId = senderId,
            senderName = senderName,
            senderIpAddress = senderIpAddress,
            senderUserAgent = senderUserAgent,
            plainText = plainText,
            sentAtMillis = System.currentTimeMillis()
        )

        messages += message
        if (messages.size > MAX_MESSAGES) {
            messages.removeAt(0)
        }
        message
    }

    // 저장된 새 메시지를 각 클라이언트의 AES 키로 다시 암호화해 SSE로 전송한다.
    private fun broadcastMessage(message: ChatMessage) {
        eventStreams.forEach { (token, eventQueue) ->
            val aesKey = clientAesKeys[token] ?: return@forEach
            val envelope = encryptPayload(messageToJson(message).toString(), aesKey)
            val eventData =
                """{"nonce":${jsonString(envelope.nonceBase64)},"payload":${jsonString(envelope.encryptedBase64)}}"""

            runCatching {
                eventQueue.enqueueNamedEvent("chat-message", eventData)
            }.onFailure { error ->
                logDebug("chat event failed: token=$token, reason=${error.message}")
                eventStreams.remove(token)
            }
        }
    }

    // JSON 문자열을 AES-CBC로 암호화하고 전송용 nonce와 ciphertext를 만든다.
    private fun encryptPayload(plainJson: String, aesKey: SecretKey): EncryptedPayload {
        val nonceBytes = ByteArray(16)
        secureRandom.nextBytes(nonceBytes)
        return EncryptedPayload(
            nonceBase64 = Base64.getEncoder().encodeToString(nonceBytes),
            encryptedBase64 = FileShareCrypto.encryptAesCbcText(
                plainText = plainJson,
                nonceBytes = nonceBytes,
                aesKey = aesKey
            )
        )
    }

    // NanoHTTPD 요청 body에서 JSON 객체를 파싱한다.
    private fun parseJsonBody(session: IHTTPSession): JSONObject? = runCatching {
        val parsedBody = mutableMapOf<String, String>()
        session.parseBody(parsedBody)
        JSONObject(parsedBody["postData"].orEmpty())
    }.getOrNull()

    // 복호화된 채팅 payload에서 브라우저 고정 clientId와 메시지 본문을 분리한다.
    private fun parseDecryptedMessagePayload(decryptedText: String): DecryptedMessagePayload {
        val json = runCatching { JSONObject(decryptedText) }.getOrNull()
            ?: return DecryptedMessagePayload(clientId = null, message = decryptedText)

        val message = json.optString("message").trim()
        val clientId = json.optString("clientId")
            .trim()
            .takeIf { it.matches(CLIENT_ID_PATTERN) }

        return DecryptedMessagePayload(
            clientId = clientId,
            message = message
        )
    }

    // 메시지 목록을 클라이언트 전송용 JSON 배열로 변환한다.
    private fun messagesToJson(messages: List<ChatMessage>): JSONArray =
        JSONArray().apply {
            messages.forEach { put(messageToJson(it)) }
        }

    // 단일 메시지를 클라이언트 전송용 JSON 객체로 변환한다.
    private fun messageToJson(message: ChatMessage): JSONObject =
        JSONObject()
            .put("id", message.id)
            .put("sequence", message.sequence)
            .put("senderId", message.senderId)
            .put("senderName", message.senderName)
            .put("senderIpAddress", message.senderIpAddress)
            .put("senderUserAgent", message.senderUserAgent)
            .put("message", message.plainText)
            .put("sentAtMillis", message.sentAtMillis)

    private data class EncryptedPayload(
        val nonceBase64: String,
        val encryptedBase64: String
    )

    private data class DecryptedMessagePayload(
        val clientId: String?,
        val message: String
    )

    companion object {
        const val HOST_SENDER_ID = "host"
        private val CLIENT_ID_PATTERN = Regex("""[A-Za-z0-9_-]{16,80}""")
        private const val HOST_SENDER_NAME = "Host"
        private const val HOST_DEVICE_LABEL = "Host device"
        private const val HOST_USER_AGENT = "FocusWave Android"
        private const val MAX_MESSAGES = 300
        private const val MAX_MESSAGE_CHARACTERS = 5_000
    }
}
