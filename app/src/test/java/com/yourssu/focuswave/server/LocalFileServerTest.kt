package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileServerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: LocalFileServer
    private lateinit var baseUrl: String
    private lateinit var authToken: String
    private lateinit var authCookie: String
    private var filesChangedCount = 0

    @Before
    fun setUp() {
        filesChangedCount = 0
        server = LocalFileServer(
            appFilesDirectory = temporaryFolder.root,
            port = 0,
            authCode = AUTH_CODE,
            homePage = TEST_HOME_PAGE,
            onFilesChanged = { filesChangedCount += 1 }
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
        val authResponse = authenticate(AUTH_CODE)
        authToken = TOKEN_PATTERN.find(authResponse.body)?.groupValues?.get(1)
            ?: error("Authentication token was not returned")
        authCookie = authResponse.headers.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?: error("Authentication cookie was not returned")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun auth_withCorrectCode_returnsTokenAndCookie() {
        val response = authenticate(AUTH_CODE)
        val token = TOKEN_PATTERN.find(response.body)?.groupValues?.get(1).orEmpty()

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertTrue(response.body.contains(""""success":true"""))
        assertTrue(token.length >= 32)
        assertTrue(
            response.headers.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value
                ?.any { it.startsWith("FocusWave-Token=$token") } == true
        )
    }

    @Test
    fun auth_withWrongCode_returnsUnauthorized() {
        val response = authenticateRaw("9999")

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
        assertEquals(
            """{"success":false,"message":"Invalid authentication code"}""",
            response.body
        )
    }

    @Test
    fun auth_issuesDifferentTokens() {
        val firstToken = TOKEN_PATTERN.find(authenticate(AUTH_CODE).body)?.groupValues?.get(1)
        val secondToken = TOKEN_PATTERN.find(authenticate(AUTH_CODE).body)?.groupValues?.get(1)

        assertNotEquals(firstToken, secondToken)
    }

    @Test
    fun token_isInvalidAfterServerRestart() {
        val previousToken = authToken
        server.stop()
        server = LocalFileServer(
            appFilesDirectory = temporaryFolder.root,
            port = 0,
            authCode = AUTH_CODE,
            homePage = TEST_HOME_PAGE
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        baseUrl = "http://127.0.0.1:${server.listeningPort}"

        val response = get("/list", token = previousToken)

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun upload_withoutToken_returnsUnauthorized() {
        val response = upload("blocked.txt", "blocked".toByteArray(), token = null)

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
        assertFalse(sharedFile("blocked.txt").exists())
    }

    @Test
    fun list_withoutToken_returnsUnauthorized() {
        val response = get("/list")

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
        assertTrue(response.body.contains("Authentication required"))
    }

    @Test
    fun list_withCookieFromAuth_returnsOk() {
        val response = get("/list", cookie = authCookie)

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("[]", response.body)
    }

    @Test
    fun upload_thenListAndDownloadWithAuthCookie_roundTripsFile() {
        val uploadResponse = upload("round trip.txt", "hello from pc".toByteArray())

        val listResponse = get("/list", cookie = authCookie)
        val downloadResponse = get("/download/round%20trip.txt", cookie = authCookie)

        assertEquals(HttpURLConnection.HTTP_OK, uploadResponse.statusCode)
        assertEquals(HttpURLConnection.HTTP_OK, listResponse.statusCode)
        assertEquals("""["round trip.txt"]""", listResponse.body)
        assertEquals(HttpURLConnection.HTTP_OK, downloadResponse.statusCode)
        assertEquals("hello from pc", downloadResponse.body)
        assertTrue(
            downloadResponse.headers.entries
                .firstOrNull { it.key.equals("Content-Disposition", ignoreCase = true) }
                ?.value
                ?.any { it.contains("round%20trip.txt") } == true
        )
    }

    @Test
    fun download_withoutToken_returnsUnauthorized() {
        upload("private.txt", "private".toByteArray())

        val response = get("/download/private.txt")

        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.statusCode)
        assertTrue(response.body.contains("Authentication required"))
    }

    @Test
    fun list_withCustomTokenHeader_returnsOk() {
        val response = get("/list", token = authToken, useCustomHeader = true)

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
    }

    @Test
    fun root_servesProvidedHomePage() {
        val response = get("/")

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals(TEST_HOME_PAGE, response.body)
    }

    @Test
    fun upload_savesFileAndReturnsMetadata() {
        val response = upload("notes.txt", "focus wave".toByteArray())

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("focus wave", sharedFile("notes.txt").readText())
        assertTrue(response.body.contains("\"success\":true"))
        assertTrue(response.body.contains("\"fileName\":\"notes.txt\""))
        assertTrue(response.body.contains("\"size\":10"))
        assertEquals(1, filesChangedCount)
    }

    @Test
    fun upload_preservesExistingFileWithNumberedName() {
        upload("notes.txt", "first".toByteArray())
        val response = upload("notes.txt", "second".toByteArray())

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("first", sharedFile("notes.txt").readText())
        assertEquals("second", sharedFile("notes (1).txt").readText())
        assertTrue(response.body.contains("\"fileName\":\"notes (1).txt\""))
    }

    @Test
    fun upload_stripsDirectoriesFromFileName() {
        val response = upload("../../secret.txt", "safe".toByteArray())

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("safe", sharedFile("secret.txt").readText())
        assertFalse(File(temporaryFolder.root.parentFile, "secret.txt").exists())
    }

    @Test
    fun upload_rejectsNonMultipartRequest() {
        val connection = (URL("$baseUrl/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            setRequestProperty("Authorization", "Bearer $authToken")
        }
        connection.outputStream.use { it.write("not multipart".toByteArray()) }

        val response = readResponse(connection)

        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, response.statusCode)
        assertTrue(response.body.contains("\"success\":false"))
    }

    private fun authenticate(code: String): HttpResponse {
        val body = """{"code":"$code"}""".toByteArray(StandardCharsets.UTF_8)
        val connection = (URL("$baseUrl/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun authenticateRaw(code: String): HttpResponse {
        val body = """{"code":"$code"}"""
        val rawResponse = Socket("127.0.0.1", server.listeningPort).use { socket ->
            val request = buildString {
                append("POST /auth HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val headerEnd = rawResponse.indexOf("\r\n\r\n")
        val statusCode = rawResponse
            .lineSequence()
            .first()
            .split(' ')[1]
            .toInt()
        val responseBody = if (headerEnd >= 0) rawResponse.substring(headerEnd + 4) else ""
        return HttpResponse(statusCode, responseBody, emptyMap())
    }

    private fun get(
        path: String,
        token: String? = null,
        cookie: String? = null,
        useCustomHeader: Boolean = false
    ): HttpResponse {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        token?.let {
            if (useCustomHeader) {
                connection.setRequestProperty("X-FocusWave-Token", it)
            } else {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
        }
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        return readResponse(connection)
    }

    private fun upload(
        fileName: String,
        content: ByteArray,
        token: String? = authToken
    ): HttpResponse {
        val boundary = "----FocusWaveTestBoundary"
        val header = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream().use { output ->
            output.write(header)
            output.write(content)
            output.write(footer)
            output.toByteArray()
        }

        val connection = (URL("$baseUrl/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val statusCode = connection.responseCode
        val stream = if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            connection.errorStream
        } else {
            connection.inputStream
        }
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        val headers = connection.headerFields.entries
            .filter { it.key != null }
            .associate { it.key!! to it.value.orEmpty() }
        connection.disconnect()
        return HttpResponse(statusCode, body, headers)
    }

    private fun sharedFile(name: String): File =
        File(File(temporaryFolder.root, "shared_files"), name)

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, List<String>>
    )

    companion object {
        private const val AUTH_CODE = "1234"
        private const val TEST_HOME_PAGE = "<html><body>FocusWave test page</body></html>"
        private val TOKEN_PATTERN = Regex(""""token":"([^"]+)"""")
    }
}
